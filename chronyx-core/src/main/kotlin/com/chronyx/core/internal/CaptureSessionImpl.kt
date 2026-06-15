package com.chronyx.core.internal

import android.content.Context
import android.os.Build
import com.chronyx.core.api.CaptureSession
import com.chronyx.core.api.CaptureState
import com.chronyx.core.api.ChronyxConfig
import com.chronyx.core.api.SessionMetadata
import com.chronyx.core.calib.CalibrationController
import com.chronyx.core.capture.AudioSource
import com.chronyx.core.capture.CameraSource
import com.chronyx.core.capture.GnssSource
import com.chronyx.core.capture.ImuSource
import com.chronyx.core.capture.SourceSink
import com.chronyx.core.clock.MasterClock
import com.chronyx.core.clock.SystemMasterClock
import com.chronyx.core.clock.xdevice.BootToUtcMapper
import com.chronyx.core.model.AudioBuffer
import com.chronyx.core.model.AudioDiagnostics
import com.chronyx.core.model.CameraDiagnostics
import com.chronyx.core.model.CameraFrame
import com.chronyx.core.model.CameraFrameMeta
import com.chronyx.core.model.DeviceInfo
import com.chronyx.core.model.EngineDiagnostics
import com.chronyx.core.model.EncodedVideoFrame
import com.chronyx.core.model.GnssDiagnostics
import com.chronyx.core.model.GnssFix
import com.chronyx.core.model.GnssRawEpoch
import com.chronyx.core.model.ImuDiagnostics
import com.chronyx.core.model.ImuSample
import com.chronyx.core.model.ResourceDiagnostics
import com.chronyx.core.model.SyncDiagnostics
import com.chronyx.core.perf.DegradationState
import com.chronyx.core.perf.ThermalManager
import com.chronyx.core.sync.CameraImuOffsetSeed
import com.chronyx.core.sync.SyncEngine
import com.chronyx.core.sync.SyncedBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * The one capture session object behind both consumption models. It owns the clock, the per-channel
 * sources, the [SyncEngine], thermal management, and the diagnostics loop, and routes every sample to
 * BOTH the full-rate [com.chronyx.core.api.Sink] and the engine.
 */
internal class CaptureSessionImpl(
    private val context: Context,
    private val config: ChronyxConfig,
) : CaptureSession, SourceSink {

    private val masterClock: MasterClock = SystemMasterClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bootToUtc = BootToUtcMapper()

    private val engine = SyncEngine(config.bundling)
    private val thermal = ThermalManager(context, config.thermalPolicy, ::onDegradation)

    private val deviceKey = buildString {
        append(Build.MODEL.replace(' ', '_'))
        config.camera?.let { append("_${it.resolution.width}x${it.resolution.height}@${it.fps}") }
    }
    private val calibration = if (config.requireCalibration) CalibrationController(context, deviceKey) else null
    @Volatile private var tdSeed: CameraImuOffsetSeed =
        calibration?.asSeed() ?: CameraImuOffsetSeed(0L, 0.0)

    private var cameraSource: CameraSource? = null
    private var imuSource: ImuSource? = null
    private var gnssSource: GnssSource? = null
    private var audioSource: AudioSource? = null

    private val stateFlow = MutableStateFlow(CaptureState.STARTING)
    private val diagnosticsFlow = MutableSharedFlow<SyncDiagnostics>(replay = 1, extraBufferCapacity = 4)

    @Volatile private var bundleListenerJob: Job? = null
    @Volatile private var degradation: DegradationState = DegradationState.NONE
    @Volatile private var stopped = false

    private val sessionStartBoot = masterClock.nowBoot()
    private val outputDir: File = context.getExternalFilesDir(null) ?: context.filesDir

    // Markers + sync-quality accounting for the session summary / manifest.
    private val markerCounter = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var syncSnapTotal = 0L
    @Volatile private var syncSnapLocked = 0L
    private val startWallMillis = masterClock.nowWallMillis()

    // Crash-safety: persistent log + best-effort finalization of the MCAP on uncaught crash / shutdown.
    private var logTree: FileLoggingTree? = null
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null
    private var shutdownHook: Thread? = null
    @Volatile private var finalized = false

    override val bundles: Flow<SyncedBundle> get() = engine.bundles
    override val diagnostics: Flow<SyncDiagnostics> get() = diagnosticsFlow
    override val state = stateFlow.asStateFlow()

    fun start() {
        installCrashSafety()
        engine.setTdSeed(tdSeed)
        val metadata = SessionMetadata(
            sessionId = "chronyx-${sessionStartBoot}",
            deviceModel = Build.MODEL,
            clockBaseName = config.clockBase.name,
            startBootNanos = sessionStartBoot,
            startWallMillis = masterClock.nowWallMillis(),
            cameraWidth = config.camera?.resolution?.width ?: 0,
            cameraHeight = config.camera?.resolution?.height ?: 0,
            cameraFps = config.camera?.fps ?: 0,
            videoCodecName = config.camera?.codec?.name ?: "NONE",
            imuRateHz = config.imu?.rateHz ?: 0,
            audioSampleRate = if (config.audio.enabled) config.audio.sampleRate else 0,
        )
        config.sink.open(metadata)
        thermal.start()

        if (config.requireCalibration && (calibration?.load() == null)) {
            Timber.w("requireCalibration=true but no stored calibration for $deviceKey; running with unseeded td. " +
                "Run the calibration gesture first for VIO-grade td.")
        }

        startSources()
        emitSelfDescribingRecords()
        startDiagnosticsLoop()
        stateFlow.value = CaptureState.RECORDING
    }

    /**
     * Emit the one-shot calibration/intrinsics/device records so the file is self-describing. Camera
     * calibration is computed synchronously inside [CameraSource.start], so it is available here.
     */
    private fun emitSelfDescribingRecords() {
        val now = masterClock.nowBoot()
        imuSource?.let { config.sink.writeImuIntrinsics(it.intrinsics(), now) }
        val cam = cameraSource
        val calib = cam?.calibration
        if (cam != null && calib != null) {
            config.sink.writeCameraCalibration(calib, now)
            config.sink.writeDeviceInfo(
                DeviceInfo(
                    sessionId = "chronyx-$sessionStartBoot",
                    deviceManufacturer = Build.MANUFACTURER,
                    deviceModel = Build.MODEL,
                    osVersion = Build.VERSION.RELEASE ?: "?",
                    sdkInt = Build.VERSION.SDK_INT,
                    clockBaseName = config.clockBase.name,
                    cameraId = cam.resolvedCameraId,
                    sensorPhysicalWidthMm = cam.sensorPhysicalSizeMm[0],
                    sensorPhysicalHeightMm = cam.sensorPhysicalSizeMm[1],
                    activeArrayWidthPx = cam.activeArraySizePx[0],
                    activeArrayHeightPx = cam.activeArraySizePx[1],
                    calibration = calib,
                    extrinsics = cam.extrinsics,
                ),
            )
        }
    }

    private fun startSources() {
        config.imu?.let { imuSource = ImuSource(context, it, this).also { s -> s.start() } }
        config.camera?.let { cam ->
            cameraSource = CameraSource(context, cam, { tdSeed }, this).also { s -> s.start() }
        }
        if (config.gnss.enabled) {
            gnssSource = GnssSource(context, config.gnss, bootToUtc, this).also { s -> s.start() }
        }
        if (config.audio.enabled) {
            audioSource = AudioSource(config.audio, this).also { s -> s.start() }
        }
    }

    // ---- SourceSink fan-out: every sample goes to the file AND the live engine ----

    override fun onCameraFrame(frame: CameraFrame) = engine.onCameraFrame(frame)

    override fun onCameraMeta(meta: CameraFrameMeta) {
        config.sink.writeCameraMeta(meta)
        engine.onCameraMeta(meta)
    }

    override fun onEncodedVideo(frame: EncodedVideoFrame) = config.sink.writeVideo(frame)

    override fun onImu(sample: ImuSample) {
        config.sink.writeImu(sample)
        engine.onImu(sample)
    }

    override fun onGnssFix(fix: GnssFix) {
        config.sink.writeGnssFix(fix)
        engine.onGnssFix(fix)
    }

    override fun onGnssRaw(epoch: GnssRawEpoch) = config.sink.writeGnssRaw(epoch)

    override fun onAudio(buffer: AudioBuffer) {
        config.sink.writeAudio(buffer)
        engine.onAudio(buffer)
    }

    private fun onDegradation(state: DegradationState) {
        degradation = state
        // The encoder bitrate is the live knob; resolution/fps steps are logged here and would require
        // a capture-session reconfigure, which the host can trigger by restarting capture if desired.
        Timber.w("Applying degradation level ${state.level}: ${state.description}")
    }

    private fun startDiagnosticsLoop() {
        scope.launch {
            var lastBytes = 0L
            var lastBoot = masterClock.nowBoot()
            while (isActive && !stopped) {
                delay(500)
                val nowBoot = masterClock.nowBoot()
                val bytes = config.sink.bytesWritten
                val dt = (nowBoot - lastBoot).coerceAtLeast(1)
                val throughput = (bytes - lastBytes) * 1_000_000_000L / dt
                lastBytes = bytes; lastBoot = nowBoot

                val d = buildDiagnostics(nowBoot, throughput, bytes)
                syncSnapTotal++
                if (d.syncLocked) syncSnapLocked++
                diagnosticsFlow.tryEmit(d)
                config.sink.writeDiagnostics(d)
            }
        }
    }

    private fun buildDiagnostics(nowBoot: Long, throughput: Long, bytes: Long): SyncDiagnostics {
        // Per-channel "dropped" reflects genuine FILE loss (sink write-queue overflow), NOT the
        // live-bundle window cache, which evicts old samples by design and is not data loss. On a
        // healthy capture these are all zero even though the bundle window churns continuously.
        val drops = config.sink.sinkDrops
        val cam = cameraSource?.let { c ->
            CameraDiagnostics(
                timestampSource = c.currentTimestampSource,
                achievedFps = c.achievedFps,
                currentRollingShutterSkewNanos = c.rollingShutterSkewNanos,
                currentExposureNanos = c.exposureNanos,
                tdSeedNanos = if (tdSeed.confidence > 0) tdSeed.nanos else null,
                tdConfidence = tdSeed.confidence,
                clockOffsetConfidence = c.clockOffsetConfidence,
                encoderQueueDepth = c.encoderDepth,
                droppedFrames = drops.video,
            )
        }
        val imu = imuSource?.let { i ->
            ImuDiagnostics(
                detectedBase = i.detectedBase,
                achievedRateHz = i.achievedRateHz,
                targetRateHz = i.targetRateHz,
                droppedSamples = drops.imu,
                appliedOffsetNanos = i.appliedOffset,
            )
        }
        val gnss = gnssSource?.let { g ->
            GnssDiagnostics(
                fixAgeMillis = if (g.lastFixBootNanos == 0L) Long.MAX_VALUE else (nowBoot - g.lastFixBootNanos) / 1_000_000,
                satellitesInFix = g.satellitesInFix,
                satellitesVisible = g.satellitesVisible,
                satellitesUsed = g.satellitesUsed,
                meanCn0DbHz = g.meanCn0DbHz,
                rawMeasurementsSupported = g.rawSupported,
                rawMeasurementsActive = g.rawActive,
                utcResidualNanos = g.lastUtcResidualNanos,
                bootToUtc = bootToUtc.snapshot(),
                droppedEpochs = drops.gnss,
            )
        }
        val audio = audioSource?.let { a ->
            AudioDiagnostics(
                sampleRate = config.audio.sampleRate,
                source = a.resolvedSource,
                anchorAgeMillis = a.anchorAgeMillis,
                droppedBuffers = drops.audio,
            )
        }
        val engineDiag = EngineDiagnostics(
            bundleRateHz = engine.bundleRateHz,
            droppedBundles = engine.droppedBundles.get(),
            mcapWriteThroughputBytesPerSec = throughput,
            mcapBytesWritten = bytes,
        )
        val resources = ResourceDiagnostics(
            thermalStatus = thermal.thermalStatus,
            activeDegradation = if (degradation.level == 0) null else degradation.description,
            batteryTemperatureCelsius = thermal.batteryTemperatureCelsius(),
            freeStorageBytes = thermal.freeStorageBytes(outputDir),
        )
        return SyncDiagnostics(
            tBoot = nowBoot,
            recording = stateFlow.value == CaptureState.RECORDING,
            syncLocked = computeSyncLocked(cam, imu),
            camera = cam, imu = imu, gnss = gnss, audio = audio,
            engine = engineDiag, resources = resources,
        )
    }

    private fun computeSyncLocked(cam: CameraDiagnostics?, imu: ImuDiagnostics?): Boolean {
        val cameraOk = cam == null || cam.clockOffsetConfidence >= 0.5
        val imuOk = imu == null || imu.detectedBase != "DETECTING"
        return cameraOk && imuOk && stateFlow.value == CaptureState.RECORDING
    }

    override fun setBundleListener(listener: CaptureSession.BundleListener?) {
        bundleListenerJob?.cancel()
        if (listener == null) return
        bundleListenerJob = scope.launch {
            engine.bundles.collect { listener.onBundle(it) }
        }
    }

    override fun mark(label: String) {
        if (stopped) return
        config.sink.writeMarker(masterClock.nowBoot(), markerCounter.getAndIncrement(), label)
        Timber.i("Marker #${markerCounter.get()} '$label'")
    }

    override fun summary(): com.chronyx.core.api.SessionSummary {
        val now = masterClock.nowBoot()
        val calib = cameraSource?.calibration
        val channels = buildList {
            config.camera?.let { add("/camera/video"); add("/camera/meta"); add("/camera/calibration") }
            config.imu?.let { add("/imu/accel"); add("/imu/gyro"); add("/imu/intrinsics") }
            if (config.gnss.enabled) { add("/gnss/fix"); add("/gnss/raw") }
            if (config.audio.enabled) add("/audio/pcm")
            add("/diag/sync"); add("/device/info"); add("/markers")
        }
        return com.chronyx.core.api.SessionSummary(
            sessionId = "chronyx-$sessionStartBoot",
            deviceModel = Build.MODEL,
            startWallMillis = startWallMillis,
            startBootNanos = sessionStartBoot,
            durationNanos = now - sessionStartBoot,
            syncLockedFraction = if (syncSnapTotal > 0) syncSnapLocked.toDouble() / syncSnapTotal else 0.0,
            markerCount = markerCounter.get().toInt(),
            channels = channels,
            intrinsicsSource = calib?.source?.name,
            calibrationK = calib?.k,
            mcapBytes = config.sink.bytesWritten,
            gnssFixCount = gnssSource?.fixCount ?: 0,
            georeferenceable = (gnssSource?.fixCount ?: 0) > 0,
            imuAchievedRateHz = imuSource?.achievedRateHz ?: 0.0,
        )
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        stateFlow.value = CaptureState.STOPPING
        try {
            // Re-emit IMU intrinsics with the MEASURED achieved rate (the start-of-session record had
            // no rate yet), so the file's authoritative rate matches what was actually delivered.
            imuSource?.let { config.sink.writeImuIntrinsics(it.intrinsics(), masterClock.nowBoot()) }
            cameraSource?.stop()
            imuSource?.stop()
            gnssSource?.stop()
            audioSource?.stop()
            thermal.stop()
            engine.clear()
            finalizeFile()
        } catch (t: Throwable) {
            Timber.e(t, "Error during stop()")
            stateFlow.value = CaptureState.ERROR
        } finally {
            removeCrashSafety()
            scope.cancel()
            stateFlow.value = CaptureState.STOPPED
        }
    }

    // ---- crash safety ----

    private fun installCrashSafety() {
        logTree = FileLoggingTree(File(outputDir, "chronyx_${sessionStartBoot}.log")).also { Timber.plant(it) }
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "Uncaught exception on ${thread.name}; finalizing capture")
            try { finalizeFile() } catch (_: Throwable) {}
            logTree?.close()
            previousUncaughtHandler?.uncaughtException(thread, throwable)
        }
        shutdownHook = Thread { try { finalizeFile() } catch (_: Throwable) {}; logTree?.close() }
            .also { Runtime.getRuntime().addShutdownHook(it) }
    }

    /** Finalize the MCAP exactly once (footer written), from stop() or a crash path. */
    private fun finalizeFile() {
        if (finalized) return
        finalized = true
        config.sink.close()
    }

    private fun removeCrashSafety() {
        shutdownHook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
        shutdownHook = null
        previousUncaughtHandler?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
        logTree?.let { Timber.uproot(it); it.close() }
        logTree = null
    }
}
