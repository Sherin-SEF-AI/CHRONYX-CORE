package com.chronyx.mcap

import com.chronyx.core.api.SessionMetadata
import com.chronyx.core.api.Sink
import com.chronyx.core.model.AudioBuffer
import com.chronyx.core.model.CameraCalibration
import com.chronyx.core.model.CameraFrameMeta
import com.chronyx.core.model.DeviceInfo
import com.chronyx.core.model.EncodedVideoFrame
import com.chronyx.core.model.GnssFix
import com.chronyx.core.model.GnssRawEpoch
import com.chronyx.core.model.ImuChannel
import com.chronyx.core.model.ImuIntrinsics
import com.chronyx.core.model.ImuSample
import com.chronyx.core.model.SyncDiagnostics
import com.chronyx.mcap.internal.InternalMcapWriter
import com.chronyx.mcap.internal.McapCompression
import com.chronyx.mcap.internal.ProtoEncoders
import com.chronyx.mcap.internal.SchemaRegistry
import android.os.SystemClock
import timber.log.Timber
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The default [Sink]: serializes full-rate per-channel data into a chunked, compressed MCAP file that
 * opens directly in Foxglove Studio on the BOOTTIME axis.
 *
 * Design points that matter for unattended 30-minute captures:
 *  - All `write*` calls are non-blocking. They hand a copy-safe model object to a single writer
 *    thread via a bounded queue; if the disk can't keep up the queue fills and the oldest write for
 *    that channel is dropped with a counter (we never stall capture, and we never silently lose data
 *    without telemetry).
 *  - A periodic flusher forces the open chunk to disk so a crash loses seconds, not the whole file.
 *  - File rotation by size/duration and a free-space floor that finalizes the file cleanly before the
 *    disk fills.
 *
 * @param fileFactory produces the file for rotation index `n` (n=0 is the first file).
 * @param maxBytesPerFile rotate when the current file reaches this size; 0 disables size rotation.
 * @param maxDurationNanos rotate after this much wall time; 0 disables duration rotation.
 * @param freeSpaceFloorBytes stop recording cleanly when usable space drops below this.
 */
class McapSink @JvmOverloads constructor(
    private val fileFactory: (Int) -> File,
    private val compression: McapCompression = McapCompression.NONE,
    private val maxBytesPerFile: Long = 0,
    private val maxDurationNanos: Long = 0,
    private val freeSpaceFloorBytes: Long = 256L * 1024 * 1024,
    queueCapacity: Int = 8192,
) : Sink {

    /** Single-file convenience constructor (no rotation). */
    @JvmOverloads
    constructor(file: File, compression: McapCompression = McapCompression.NONE) :
        this(fileFactory = { file }, compression = compression)

    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity),
        { r -> Thread(r, "chronyx-mcap-writer").apply { priority = Thread.NORM_PRIORITY } },
    )
    private val flushTicker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "chronyx-mcap-flush")
    }

    private val started = AtomicBoolean(false)
    private val closedFlag = AtomicBoolean(false)
    @Volatile private var spaceExhausted = false

    private val finalizedBytes = AtomicLong(0)
    @Volatile private var writer: InternalMcapWriter? = null
    private var fileIndex = 0
    private var fileStartBootNanos = 0L
    private lateinit var metadata: SessionMetadata

    // Per-channel state lives on the writer thread; rebuilt on each rotation.
    private class Channels(
        val video: Int, val cameraMeta: Int, val imuAccel: Int, val imuGyro: Int,
        val gnssFix: Int, val gnssRaw: Int, val audio: Int, val diag: Int,
        val cameraCalibration: Int, val deviceInfo: Int, val imuIntrinsics: Int, val markers: Int,
    )
    private var channels: Channels? = null
    private val seq = HashMap<Int, Long>()

    // Cached one-shot self-describing records (writer-thread confined), re-emitted into each rotated file.
    @Volatile private var lastDeviceInfo: Pair<DeviceInfo, Long>? = null
    @Volatile private var lastImuIntrinsics: Pair<ImuIntrinsics, Long>? = null
    @Volatile private var lastCalibration: Pair<CameraCalibration, Long>? = null

    // Drop counters (surfaced to diagnostics).
    val droppedVideo = AtomicLong(0)
    val droppedImu = AtomicLong(0)
    val droppedGnss = AtomicLong(0)
    val droppedAudio = AtomicLong(0)
    val droppedDiagnostics = AtomicLong(0)

    override val bytesWritten: Long
        get() = finalizedBytes.get() + (writer?.bytesWritten ?: 0L)

    override val sinkDrops: com.chronyx.core.api.SinkDrops
        get() = com.chronyx.core.api.SinkDrops(
            video = droppedVideo.get(), imu = droppedImu.get(), gnss = droppedGnss.get(),
            audio = droppedAudio.get(), diagnostics = droppedDiagnostics.get(),
        )

    override fun open(metadata: SessionMetadata) {
        check(started.compareAndSet(false, true)) { "McapSink already opened" }
        this.metadata = metadata
        submit(droppedDiagnostics) { recoverPendingFiles() }
        submit(droppedDiagnostics) { openNewFile() }
        flushTicker.scheduleAtFixedRate(
            { submit(droppedDiagnostics) { periodicMaintenance() } },
            1, 1, TimeUnit.SECONDS,
        )
    }

    override fun writeVideo(frame: EncodedVideoFrame) = submit(droppedVideo) {
        val ch = channels ?: return@submit
        emit(ch.video, frame.tMidBoot, ProtoEncoders.video(frame))
    }

    override fun writeCameraMeta(meta: CameraFrameMeta) = submit(droppedVideo) {
        val ch = channels ?: return@submit
        emit(ch.cameraMeta, meta.tMidBoot, ProtoEncoders.cameraMeta(meta))
    }

    override fun writeDeviceInfo(info: DeviceInfo) = submit(droppedDiagnostics) {
        lastDeviceInfo = info to SystemClock.elapsedRealtimeNanos()
        val ch = channels ?: return@submit
        emit(ch.deviceInfo, lastDeviceInfo!!.second, ProtoEncoders.deviceInfo(info))
        writer?.writeMetadata("chronyx", deviceMetadata(info))
    }

    /** Key self-describing facts as a string map → an MCAP Metadata record (surfaced by `mcap info`). */
    private fun deviceMetadata(info: DeviceInfo): Map<String, String> = linkedMapOf(
        "session_id" to info.sessionId,
        "device" to "${info.deviceManufacturer} ${info.deviceModel}",
        "os" to "Android ${info.osVersion} (sdk ${info.sdkInt})",
        "clock_base" to info.clockBaseName,
        "camera_id" to info.cameraId,
        "camera_resolution" to "${info.calibration.width}x${info.calibration.height}",
        "intrinsics_source" to info.calibration.source.name,
        "intrinsics_K" to info.calibration.k.joinToString(",") { "%.3f".format(it) },
        "distortion_model" to info.calibration.distortionModel,
        "camera_imu_extrinsic" to if (info.extrinsics.available) info.extrinsics.referenceName else "unavailable",
    )

    override fun writeCameraCalibration(calibration: CameraCalibration, tBoot: Long) = submit(droppedVideo) {
        lastCalibration = calibration to tBoot
        val ch = channels ?: return@submit
        emit(ch.cameraCalibration, tBoot, ProtoEncoders.cameraCalibration(calibration, tBoot))
    }

    override fun writeImuIntrinsics(intrinsics: ImuIntrinsics, tBoot: Long) = submit(droppedImu) {
        lastImuIntrinsics = intrinsics to tBoot
        val ch = channels ?: return@submit
        emit(ch.imuIntrinsics, tBoot, ProtoEncoders.imuIntrinsics(intrinsics))
    }

    override fun writeMarker(tBoot: Long, index: Long, label: String) = submit(droppedDiagnostics) {
        val ch = channels ?: return@submit
        emit(ch.markers, tBoot, ProtoEncoders.marker(tBoot, index, label))
    }

    override fun writeImu(sample: ImuSample) = submit(droppedImu) {
        val ch = channels ?: return@submit
        val channelId = if (sample.channel.isGyro()) ch.imuGyro else ch.imuAccel
        emit(channelId, sample.tBoot, ProtoEncoders.imu(sample))
    }

    override fun writeGnssFix(fix: GnssFix) = submit(droppedGnss) {
        val ch = channels ?: return@submit
        emit(ch.gnssFix, fix.tBoot, ProtoEncoders.gnssFix(fix))
    }

    override fun writeGnssRaw(epoch: GnssRawEpoch) = submit(droppedGnss) {
        val ch = channels ?: return@submit
        emit(ch.gnssRaw, epoch.tBoot, ProtoEncoders.gnssRaw(epoch))
    }

    override fun writeAudio(buffer: AudioBuffer) = submit(droppedAudio) {
        val ch = channels ?: return@submit
        emit(ch.audio, buffer.tFirstSampleBoot, ProtoEncoders.audio(buffer))
    }

    override fun writeDiagnostics(diagnostics: SyncDiagnostics) = submit(droppedDiagnostics) {
        val ch = channels ?: return@submit
        emit(ch.diag, diagnostics.tBoot, ProtoEncoders.diagnostics(diagnostics))
    }

    override fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        flushTicker.shutdownNow()
        // Drain remaining writes then finalize on the writer thread.
        executor.execute { finalizeCurrentFile() }
        executor.shutdown()
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            Timber.w("MCAP writer did not finish within 10s; forcing shutdown")
            executor.shutdownNow()
        }
    }

    private fun ImuChannel.isGyro() =
        this == ImuChannel.GYRO_RAW || this == ImuChannel.GYRO_CAL || this == ImuChannel.GYRO_BIAS

    // ---- writer-thread operations ----

    private fun emit(channelId: Int, logTimeBoot: Long, payload: ByteArray) {
        val w = writer ?: return
        val s = (seq[channelId] ?: 0L)
        seq[channelId] = s + 1
        w.writeMessage(channelId, s, logTimeBoot, logTimeBoot, payload, payload.size)
        if (maxBytesPerFile > 0 && w.bytesWritten >= maxBytesPerFile) rotate()
    }

    private fun openNewFile() {
        val file = fileFactory(fileIndex)
        file.parentFile?.mkdirs()
        val w = InternalMcapWriter(file, compression)
        w.start(profile = "chronyx", library = "chronyx-mcap/0.1.0")
        writer = w
        fileStartBootNanos = SystemClock.elapsedRealtimeNanos()
        seq.clear()

        val ds = SchemaRegistry.descriptorSet
        val sVideo = w.addSchema(SchemaRegistry.COMPRESSED_VIDEO, ds)
        val sMeta = w.addSchema(SchemaRegistry.CAMERA_META, ds)
        val sImu = w.addSchema(SchemaRegistry.IMU_SAMPLE, ds)
        val sFix = w.addSchema(SchemaRegistry.LOCATION_FIX, ds)
        val sRaw = w.addSchema(SchemaRegistry.GNSS_RAW, ds)
        val sAudio = w.addSchema(SchemaRegistry.AUDIO_CHUNK, ds)
        val sDiag = w.addSchema(SchemaRegistry.DIAGNOSTICS, ds)
        val sCalib = w.addSchema(SchemaRegistry.CAMERA_CALIBRATION, ds)
        val sDevice = w.addSchema(SchemaRegistry.DEVICE_INFO, ds)
        val sImuIntr = w.addSchema(SchemaRegistry.IMU_INTRINSICS, ds)
        val sMarker = w.addSchema(SchemaRegistry.MARKER, ds)

        val clockMeta = mapOf("clock_base" to metadata.clockBaseName, "session_id" to metadata.sessionId)
        channels = Channels(
            video = w.addChannel("/camera/video", sVideo, clockMeta),
            cameraMeta = w.addChannel("/camera/meta", sMeta, clockMeta),
            imuAccel = w.addChannel("/imu/accel", sImu, clockMeta),
            imuGyro = w.addChannel("/imu/gyro", sImu, clockMeta),
            gnssFix = w.addChannel("/gnss/fix", sFix, clockMeta),
            gnssRaw = w.addChannel("/gnss/raw", sRaw, clockMeta),
            audio = w.addChannel("/audio/pcm", sAudio, clockMeta),
            diag = w.addChannel("/diag/sync", sDiag, clockMeta),
            cameraCalibration = w.addChannel("/camera/calibration", sCalib, clockMeta),
            deviceInfo = w.addChannel("/device/info", sDevice, clockMeta),
            imuIntrinsics = w.addChannel("/imu/intrinsics", sImuIntr, clockMeta),
            markers = w.addChannel("/markers", sMarker, clockMeta),
        )
        // Re-emit the one-shot self-describing records into every (rotated) file so each opens
        // standalone with full calibration.
        lastDeviceInfo?.let {
            emit(channels!!.deviceInfo, it.second, ProtoEncoders.deviceInfo(it.first))
            w.writeMetadata("chronyx", deviceMetadata(it.first))
        }
        lastImuIntrinsics?.let { emit(channels!!.imuIntrinsics, it.second, ProtoEncoders.imuIntrinsics(it.first)) }
        lastCalibration?.let { emit(channels!!.cameraCalibration, it.second, ProtoEncoders.cameraCalibration(it.first, it.second)) }
        Timber.i("MCAP file opened: ${file.absolutePath}")
    }

    private fun rotate() {
        finalizeCurrentFile()
        fileIndex++
        openNewFile()
    }

    /** Finalize any unfinalized MCAP left by a previous crashed run in the output directory. */
    private fun recoverPendingFiles() {
        val dir = fileFactory(0).parentFile ?: return
        dir.listFiles { f -> f.extension == "mcap" }?.forEach { f ->
            try {
                if (McapRecovery.needsRecovery(f)) McapRecovery.recover(f)
            } catch (t: Throwable) {
                Timber.e(t, "Recovery failed for ${f.name}")
            }
        }
    }

    private fun finalizeCurrentFile() {
        val w = writer ?: return
        try {
            w.close()
        } catch (t: Throwable) {
            Timber.e(t, "Error finalizing MCAP file")
        }
        finalizedBytes.addAndGet(w.bytesWritten)
        writer = null
        channels = null
    }

    private fun periodicMaintenance() {
        val w = writer ?: return
        w.flush()
        // Free-space guard first: finalize cleanly before the disk fills.
        val usable = fileFactory(fileIndex).parentFile?.usableSpace ?: Long.MAX_VALUE
        if (!spaceExhausted && usable < freeSpaceFloorBytes) {
            spaceExhausted = true
            Timber.w("Free space below floor ($usable < $freeSpaceFloorBytes); finalizing MCAP file.")
            finalizeCurrentFile()
            return
        }
        // Duration-based rotation on the BOOTTIME axis (same axis the baseline was taken on).
        if (maxDurationNanos > 0 && (SystemClock.elapsedRealtimeNanos() - fileStartBootNanos) >= maxDurationNanos) {
            rotate()
        }
    }

    private inline fun submit(dropCounter: AtomicLong, crossinline task: () -> Unit) {
        if (closedFlag.get() || spaceExhausted) {
            dropCounter.incrementAndGet()
            return
        }
        try {
            executor.execute { task() }
        } catch (e: RejectedExecutionException) {
            dropCounter.incrementAndGet()
        }
    }
}
