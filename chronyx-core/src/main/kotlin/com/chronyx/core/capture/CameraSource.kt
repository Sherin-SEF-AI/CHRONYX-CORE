package com.chronyx.core.capture

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.chronyx.core.api.CameraConfig
import com.chronyx.core.api.Codec
import com.chronyx.core.api.FocusMode
import com.chronyx.core.clock.ClockOffsetEstimator
import com.chronyx.core.model.CameraCalibration
import com.chronyx.core.model.CameraExtrinsics
import com.chronyx.core.model.CameraFrame
import com.chronyx.core.model.CameraFrameMeta
import com.chronyx.core.model.CameraTimestampSource
import com.chronyx.core.model.EncodedVideoFrame
import com.chronyx.core.model.IntrinsicsSource
import com.chronyx.core.model.VideoCodec
import com.chronyx.core.sync.CameraImuOffsetSeed
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Camera2 capture with per-frame timestamp fidelity. Camera2 (not CameraX) is used because we need
 * `CaptureResult` metadata — `SENSOR_TIMESTAMP`, `SENSOR_EXPOSURE_TIME`, `SENSOR_ROLLING_SHUTTER_SKEW`,
 * `SENSOR_INFO_TIMESTAMP_SOURCE` — that CameraX abstracts away.
 *
 * The capture session drives two output surfaces simultaneously from one repeating request:
 *  1. an [ImageReader] (YUV_420_888, HardwareBuffer-backed) feeding live perception frames, and
 *  2. a [MediaCodec] input surface encoding HEVC/AVC for storage.
 *
 * Both surfaces share the camera `SENSOR_TIMESTAMP`, so encoded access units (whose presentation
 * time the camera stamps in the sensor domain) are matched back to the precise per-frame metadata.
 *
 * Timestamp normalization to BOOTTIME:
 *  - `REALTIME` source → `SENSOR_TIMESTAMP` is already BOOTTIME; used directly.
 *  - `UNKNOWN` source → each `onCaptureCompleted` pairs `SENSOR_TIMESTAMP` with a fresh
 *    `elapsedRealtimeNanos()` to drive a [ClockOffsetEstimator]; frames are mapped through it.
 *
 * Frame reference time is mid-exposure (`t_mid = SENSOR_TIMESTAMP + exposure/2`), the VIO convention.
 */
class CameraSource(
    private val context: Context,
    private val config: CameraConfig,
    private val tdSeedProvider: () -> CameraImuOffsetSeed,
    private val out: SourceSink,
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null

    @Volatile private var timestampSource = CameraTimestampSource.UNKNOWN
    private val offsetEstimator = ClockOffsetEstimator()

    // Live ImageReader frames recover their metadata by sensor timestamp (image.timestamp IS the
    // CaptureResult SENSOR_TIMESTAMP — same BOOTTIME domain). Bounded by eviction.
    private val pendingMetaByPtsUs = ConcurrentHashMap<Long, CameraFrameMeta>()

    // Encoded access units are paired with metadata by FIFO capture order, NOT by presentation time:
    // on many devices the camera stamps the MediaCodec INPUT SURFACE buffers in a different clock
    // domain than CaptureResult.SENSOR_TIMESTAMP (observed ~96000 s offset on SM-A176B), so a
    // pts-keyed match is unreliable. Live HEVC output is in capture order (no B-frames), so FIFO is
    // correct and gives every encoded frame the precise BOOTTIME t_mid from its capture result.
    private val encoderMetaFifo = java.util.concurrent.ConcurrentLinkedQueue<CameraFrameMeta>()
    private val frameIndex = AtomicLong(0)

    // Diagnostics.
    private val droppedFrames = AtomicLong(0)
    private val encoderQueueDepth = AtomicInteger(0)
    @Volatile private var lastSkewNanos = 0L
    @Volatile private var lastExposureNanos = 0L
    @Volatile private var fpsAnchorBoot = 0L
    @Volatile private var fpsFrameCount = 0L
    @Volatile private var achievedFpsValue = 0.0

    private var codecConfig: ByteArray? = null

    // Calibration, computed once at open from CameraCharacteristics (reported or derived).
    @Volatile var calibration: CameraCalibration? = null; private set
    @Volatile var extrinsics: CameraExtrinsics = CameraExtrinsics.UNAVAILABLE; private set
    @Volatile var resolvedCameraId: String = ""; private set
    @Volatile var sensorPhysicalSizeMm: FloatArray = floatArrayOf(0f, 0f); private set
    @Volatile var activeArraySizePx: IntArray = intArrayOf(0, 0); private set

    // Per-frame photometric/focus state (latest), for diagnostics.
    @Volatile private var lastIso = 0
    @Volatile private var lastFocusDiopters = 0f
    @Volatile private var lastFocalMm = 0f

    val currentTimestampSource: CameraTimestampSource get() = timestampSource
    val achievedFps: Double get() = achievedFpsValue
    val rollingShutterSkewNanos: Long get() = lastSkewNanos
    val exposureNanos: Long get() = lastExposureNanos
    val clockOffsetConfidence: Double
        get() = if (timestampSource == CameraTimestampSource.REALTIME) 1.0 else offsetEstimator.confidence
    val droppedFrameCount: Long get() = droppedFrames.get()
    val encoderDepth: Int get() = encoderQueueDepth.get()

    fun start(cameraId: String? = null) {
        val id = cameraId ?: pickBackCamera()
            ?: throw IllegalStateException("No camera available")
        resolvedCameraId = id
        val chars = cameraManager.getCameraCharacteristics(id)
        timestampSource = when (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> CameraTimestampSource.REALTIME
            else -> CameraTimestampSource.UNKNOWN
        }
        computeCalibration(chars)
        Timber.i("Camera $id timestamp source = $timestampSource; calibration source=${calibration?.source}")

        cameraThread = HandlerThread("chronyx-camera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        encoderThread = HandlerThread("chronyx-encoder").also { it.start() }
        encoderHandler = Handler(encoderThread!!.looper)

        setupEncoder()
        setupImageReader()
        openCamera(id)
    }

    /**
     * Builds [calibration] + [extrinsics] for the captured resolution. Uses the device's reported
     * `LENS_INTRINSIC_CALIBRATION`/`LENS_DISTORTION`/`LENS_POSE_*` when available; otherwise derives a
     * square-pixel pinhole K from focal length + sensor physical size (the common case on consumer
     * phones — e.g. the A17 reports none). Provenance is flagged so consumers know what they're getting.
     */
    private fun computeCalibration(chars: CameraCharacteristics) {
        val capW = config.resolution.width
        val capH = config.resolution.height
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val preCorr = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: active
        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        activeArraySizePx = intArrayOf(active?.width() ?: capW, active?.height() ?: capH)
        sensorPhysicalSizeMm = floatArrayOf(physical?.width ?: 0f, physical?.height ?: 0f)
        lastFocalMm = focal

        val reported = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val k: DoubleArray
        val source: IntrinsicsSource
        if (reported != null && reported.size >= 4 && preCorr != null) {
            // [fx, fy, cx, cy, s] relative to the pre-correction active array → scale to capture size.
            val sx = capW.toDouble() / preCorr.width()
            val sy = capH.toDouble() / preCorr.height()
            k = doubleArrayOf(
                reported[0] * sx, 0.0, reported[2] * sx,
                0.0, reported[1] * sy, reported[3] * sy,
                0.0, 0.0, 1.0,
            )
            source = IntrinsicsSource.REPORTED
        } else {
            // Derived square-pixel pinhole. pixelPitch from physical size / full pixel array (square),
            // focal length → focal in full-array pixels → scaled to capture width.
            val pixArrayW = pixelArray?.width ?: active?.width() ?: capW
            val physW = if (sensorPhysicalSizeMm[0] > 0f) sensorPhysicalSizeMm[0] else 1f
            val pixelPitchMm = physW / pixArrayW
            val fFullPx = if (pixelPitchMm > 0f && focal > 0f) focal / pixelPitchMm else capW.toFloat()
            val scale = capW.toDouble() / (active?.width() ?: pixArrayW)
            val f = fFullPx * scale
            k = doubleArrayOf(f, 0.0, capW / 2.0, 0.0, f, capH / 2.0, 0.0, 0.0, 1.0)
            source = IntrinsicsSource.DERIVED_FROM_FOCAL_LENGTH
        }

        // Distortion (API 28+). Android's model is a 5-coeff rational radial; pass through honestly.
        val distortion: DoubleArray
        val distModel: String
        val dist = if (Build.VERSION.SDK_INT >= 28) chars.get(CameraCharacteristics.LENS_DISTORTION) else null
        if (dist != null && dist.isNotEmpty()) {
            distortion = DoubleArray(dist.size) { dist[it].toDouble() }
            distModel = "android_rational"
        } else {
            distortion = DoubleArray(0)
            distModel = "none"
        }
        calibration = CameraCalibration(capW, capH, k, distortion, distModel, source)

        // Camera↔IMU extrinsic (often unavailable on consumer phones).
        val poseT = chars.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
        val poseR = chars.get(CameraCharacteristics.LENS_POSE_ROTATION)
        extrinsics = if (poseT != null && poseR != null && poseT.size == 3 && poseR.size == 4) {
            val ref = if (Build.VERSION.SDK_INT >= 28) {
                when (chars.get(CameraCharacteristics.LENS_POSE_REFERENCE)) {
                    CameraMetadata.LENS_POSE_REFERENCE_GYROSCOPE -> "GYROSCOPE"
                    CameraMetadata.LENS_POSE_REFERENCE_PRIMARY_CAMERA -> "PRIMARY_CAMERA"
                    else -> "UNDEFINED"
                }
            } else "UNDEFINED"
            CameraExtrinsics(true, poseT.copyOf(), poseR.copyOf(), ref)
        } else {
            CameraExtrinsics.UNAVAILABLE
        }
    }

    private fun pickBackCamera(): String? = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: cameraManager.cameraIdList.firstOrNull()

    private fun setupImageReader() {
        // The usage-flag ImageReader factory (HardwareBuffer-backed) is API 29+; fall back to the
        // plain factory on 26–28 (frames still carry a HardwareBuffer via Image.getHardwareBuffer()).
        imageReader = if (Build.VERSION.SDK_INT >= 29) {
            val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN
            ImageReader.newInstance(config.resolution.width, config.resolution.height, ImageFormat.YUV_420_888, 4, usage)
        } else {
            ImageReader.newInstance(config.resolution.width, config.resolution.height, ImageFormat.YUV_420_888, 4)
        }.apply {
            setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireLatestImage() } catch (t: Throwable) { null }
                if (image == null) { droppedFrames.incrementAndGet(); return@setOnImageAvailableListener }
                val ptsUs = image.timestamp / 1000
                val meta = pendingMetaByPtsUs.remove(ptsUs)
                // Evict any metas whose live image never arrived, to bound the map.
                if (pendingMetaByPtsUs.size > 16) {
                    pendingMetaByPtsUs.keys.filter { it < ptsUs - 1_000_000 }.forEach { pendingMetaByPtsUs.remove(it) }
                }
                if (meta == null) {
                    // Metadata not yet delivered (callback ordering); drop the live frame rather than
                    // emit an unstamped one. Encoded path still records it for the file.
                    image.close()
                    droppedFrames.incrementAndGet()
                    return@setOnImageAvailableListener
                }
                val hb = if (Build.VERSION.SDK_INT >= 28) image.hardwareBuffer else null
                val frame = CameraFrame(meta, image, hb) { /* onClose */ }
                out.onCameraFrame(frame)
            }, cameraHandler)
        }
    }

    private fun setupEncoder() {
        val mime = if (config.codec == Codec.HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, config.resolution.width, config.resolution.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, defaultBitrate())
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSeconds)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        encoder = MediaCodec.createEncoderByType(mime).apply {
            setCallback(encoderCallback, encoderHandler)
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = createInputSurface()
            start()
        }
    }

    private fun defaultBitrate(): Int {
        // ~0.1 bpp·fps heuristic, clamped; thermal policy steps this down at runtime.
        val pixels = config.resolution.width * config.resolution.height
        return (pixels.toLong() * config.fps / 10).coerceIn(2_000_000, 40_000_000).toInt()
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { /* surface input; unused */ }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val buffer: ByteBuffer? = try { codec.getOutputBuffer(index) } catch (t: Throwable) { null }
            if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buffer.get(bytes)

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // Codec-specific data (VPS/SPS/PPS). Cache and prepend to the next keyframe so the
                    // recorded stream is independently decodable.
                    codecConfig = bytes
                } else {
                    emitEncoded(bytes, info)
                }
            }
            try { codec.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Timber.e(e, "Encoder error (transient=${e.isTransient}, recoverable=${e.isRecoverable})")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Timber.i("Encoder output format: $format")
        }
    }

    private fun emitEncoded(au: ByteArray, info: MediaCodec.BufferInfo) {
        // Pair by capture order (see encoderMetaFifo rationale) so the BOOTTIME t_mid is correct even
        // when the encoder's presentation-time clock differs from the sensor timestamp clock.
        val meta = encoderMetaFifo.poll()
        val tMid = meta?.tMidBoot ?: SystemClock.elapsedRealtimeNanos()
        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val cfg = codecConfig
        val payload = if (keyframe && cfg != null) cfg + au else au
        out.onEncodedVideo(
            EncodedVideoFrame(
                frameIndex = meta?.frameIndex ?: frameIndex.get(),
                tMidBoot = tMid,
                keyframe = keyframe,
                codec = if (config.codec == Codec.HEVC) VideoCodec.HEVC else VideoCodec.AVC,
                data = payload,
                size = payload.size,
            ),
        )
    }

    private fun openCamera(id: String) {
        // Permission is the host's responsibility; CameraManager throws SecurityException if absent,
        // which the session surfaces as a terminal diagnostic.
        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                Timber.w("Camera disconnected"); camera.close(); device = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Timber.e("Camera error $error"); camera.close(); device = null
            }
        }, cameraHandler)
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice) {
        val surfaces = listOfNotNull(imageReader?.surface, encoderSurface)
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    imageReader?.surface?.let { addTarget(it) }
                    encoderSurface?.let { addTarget(it) }
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(config.fps, config.fps))
                    applyFocus(this)
                    if (config.aeLock) set(CaptureRequest.CONTROL_AE_LOCK, true)
                }
                s.setRepeatingRequest(req.build(), captureCallback, cameraHandler)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                Timber.e("Camera capture session configuration failed")
            }
        }, cameraHandler)
    }

    private fun applyFocus(req: CaptureRequest.Builder) {
        when (config.focusMode) {
            FocusMode.AUTO ->
                req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            FocusMode.FIXED_INFINITY -> {
                req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                req.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f) // 0 diopters == infinity
            }
            FocusMode.FIXED -> {
                req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                req.set(CaptureRequest.LENS_FOCUS_DISTANCE, config.fixedFocusDiopters)
            }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val arrivalBoot = SystemClock.elapsedRealtimeNanos()
            if (timestampSource == CameraTimestampSource.UNKNOWN) {
                offsetEstimator.update(foreignNanos = sensorTs, bootNanos = arrivalBoot)
            }
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val skew = if (config.wantRollingShutterSkew) {
                result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L
            } else 0L
            lastExposureNanos = exposure
            lastSkewNanos = skew

            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            val focalLen = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: lastFocalMm
            val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: -1
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: -1
            lastIso = iso; lastFocusDiopters = focusDist; lastFocalMm = focalLen

            val tSensorBoot = bootFromSensorNanos(sensorTs)
            val tMid = tSensorBoot + exposure / 2
            val seed = tdSeedProvider()
            val meta = CameraFrameMeta(
                frameIndex = frameIndex.getAndIncrement(),
                tSensorBoot = tSensorBoot,
                tMidBoot = tMid,
                exposureNanos = exposure,
                rollingShutterSkewNanos = skew,
                timestampSource = timestampSource,
                tdSeedNanos = if (seed.confidence > 0.0) seed.nanos else null,
                tdConfidence = seed.confidence,
                offsetConfidence = clockOffsetConfidence,
                iso = iso,
                focusDistanceDiopters = focusDist,
                focalLengthMm = focalLen,
                afState = afState,
                aeState = aeState,
            )
            pendingMetaByPtsUs[sensorTs / 1000] = meta
            encoderMetaFifo.add(meta)
            // Bound the FIFO if the encoder ever falls behind (it normally drains 1:1).
            while (encoderMetaFifo.size > 120) encoderMetaFifo.poll()
            out.onCameraMeta(meta)
            updateFps(arrivalBoot)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailureCompat) {
            droppedFrames.incrementAndGet()
        }
    }

    /** Maps a raw camera sensor timestamp (ns) to BOOTTIME per the detected source. */
    private fun bootFromSensorNanos(sensorNanos: Long): Long =
        if (timestampSource == CameraTimestampSource.REALTIME) sensorNanos
        else offsetEstimator.toBoot(sensorNanos)

    private fun updateFps(nowBoot: Long) {
        fpsFrameCount++
        if (fpsAnchorBoot == 0L) { fpsAnchorBoot = nowBoot; return }
        val elapsed = nowBoot - fpsAnchorBoot
        if (elapsed >= 1_000_000_000L) {
            achievedFpsValue = fpsFrameCount * 1e9 / elapsed
            fpsAnchorBoot = nowBoot
            fpsFrameCount = 0
        }
    }

    fun stop() {
        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { device?.close() } catch (_: Throwable) {}
        device = null
        try { encoder?.stop(); encoder?.release() } catch (_: Throwable) {}
        encoder = null
        encoderSurface?.release(); encoderSurface = null
        imageReader?.close(); imageReader = null
        cameraThread?.quitSafely(); cameraThread = null
        encoderThread?.quitSafely(); encoderThread = null
        pendingMetaByPtsUs.clear()
        encoderMetaFifo.clear()
    }
}

// CaptureFailure type alias kept explicit to avoid an unused import warning on some SDKs.
private typealias CaptureFailureCompat = android.hardware.camera2.CaptureFailure
