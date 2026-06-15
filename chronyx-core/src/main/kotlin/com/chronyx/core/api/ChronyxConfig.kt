package com.chronyx.core.api

import android.util.Size

/** Thrown by [Chronyx.start] when a configuration is internally inconsistent or unsatisfiable. */
class ChronyxConfigException(message: String) : IllegalArgumentException(message)

/** Camera capture configuration. */
data class CameraConfig(
    val resolution: Size,
    val fps: Int,
    val codec: Codec,
    val wantRollingShutterSkew: Boolean,
    val keyframeIntervalSeconds: Int,
    val focusMode: FocusMode,
    /** Diopters for [FocusMode.FIXED] (1/metres; 0 == infinity). Ignored otherwise. */
    val fixedFocusDiopters: Float,
    val aeLock: Boolean,
)

/** IMU capture configuration. */
data class ImuConfig(
    /**
     * REQUESTED sample rate. This is a hint: the Android HAL rounds it to its nearest supported ODR
     * and may deliver a different (uniform) rate — e.g. a 200 Hz request on an LSM6DSV lands at 247 Hz
     * (990 Hz ODR ÷ 4). CHRONYX logs every delivered sample losslessly; the AUTHORITATIVE achieved rate
     * is recorded in `/imu/intrinsics` (re-emitted at stop) and every `/diag/sync` snapshot. Downstream
     * must read the achieved rate / per-sample timestamps, not assume this value.
     */
    val rateHz: Int,
    /** Request `*_UNCALIBRATED` sensors as the primary stream (VIO must not inherit hidden bias). */
    val uncalibrated: Boolean,
    /** Also subscribe to calibrated variants for heads that prefer them. */
    val alsoCalibrated: Boolean,
    /** `maxReportLatencyUs` for batching; 0 disables. Power knob, not a correctness knob. */
    val maxReportLatencyUs: Long,
)

/** GNSS capture configuration. */
data class GnssConfig(
    val enabled: Boolean,
    /** Request raw `GnssMeasurement` callbacks; silently degraded if the device lacks support. */
    val rawMeasurements: Boolean,
    val fixIntervalMillis: Long,
)

/** Microphone capture configuration. */
data class AudioConfig(
    val enabled: Boolean,
    val sampleRate: Int,
    val source: AudioSource,
    val bufferDurationMillis: Int,
)

/** Bundle-assembly configuration. */
data class BundlingConfig(
    val mode: Bundling,
    /** Half-width of the attached IMU window around `t_ref`, in ms. */
    val imuWindowMs: Int,
    /** For [Bundling.FixedRate] only. */
    val bundleRateHz: Int,
    /** Beyond this age a GNSS fix is marked stale rather than interpolated/extrapolated. */
    val gnssMaxStalenessMs: Int,
)

/**
 * Immutable, validated capture configuration. Construct via [Builder]. Invalid combinations throw a
 * [ChronyxConfigException] from [Builder.build] with an actionable message; unsupported-but-degradable
 * requests (e.g. raw GNSS on a device without it) are NOT errors — they are warned and degraded at
 * runtime by the relevant subsystem.
 */
class ChronyxConfig private constructor(
    val camera: CameraConfig?,
    val imu: ImuConfig?,
    val gnss: GnssConfig,
    val audio: AudioConfig,
    val clockBase: ClockBase,
    val bundling: BundlingConfig,
    val sink: Sink,
    val thermalPolicy: ThermalPolicy,
    /** VIO heads run the calibration gesture; non-VIO heads skip it. */
    val requireCalibration: Boolean,
) {
    class Builder {
        private var camera: CameraConfig? = null
        private var imu: ImuConfig? = null
        private var gnss: GnssConfig = GnssConfig(enabled = false, rawMeasurements = false, fixIntervalMillis = 1000)
        private var audio: AudioConfig =
            AudioConfig(enabled = false, sampleRate = 48_000, source = AudioSource.UNPROCESSED, bufferDurationMillis = 20)
        private var clockBase: ClockBase = ClockBase.BOOTTIME
        private var bundling: BundlingConfig =
            BundlingConfig(Bundling.PerFrame, imuWindowMs = 40, bundleRateHz = 30, gnssMaxStalenessMs = 1500)
        private var sink: Sink? = null
        private var thermalPolicy: ThermalPolicy = ThermalPolicy.ADAPTIVE
        private var requireCalibration: Boolean = false

        @JvmOverloads
        fun camera(
            resolution: Size,
            fps: Int = 30,
            codec: Codec = Codec.HEVC,
            wantRollingShutterSkew: Boolean = true,
            keyframeIntervalSeconds: Int = 1,
            focusMode: FocusMode = FocusMode.FIXED_INFINITY,
            fixedFocusDiopters: Float = 0f,
            aeLock: Boolean = false,
        ) = apply {
            camera = CameraConfig(
                resolution, fps, codec, wantRollingShutterSkew, keyframeIntervalSeconds,
                focusMode, fixedFocusDiopters, aeLock,
            )
        }

        @JvmOverloads
        fun imu(
            rateHz: Int = 200,
            uncalibrated: Boolean = true,
            alsoCalibrated: Boolean = true,
            maxReportLatencyUs: Long = 0,
        ) = apply { imu = ImuConfig(rateHz, uncalibrated, alsoCalibrated, maxReportLatencyUs) }

        @JvmOverloads
        fun gnss(rawMeasurements: Boolean = true, fixIntervalMillis: Long = 1000) = apply {
            gnss = GnssConfig(enabled = true, rawMeasurements = rawMeasurements, fixIntervalMillis = fixIntervalMillis)
        }

        @JvmOverloads
        fun audio(
            sampleRate: Int = 48_000,
            source: AudioSource = AudioSource.UNPROCESSED,
            bufferDurationMillis: Int = 20,
        ) = apply { audio = AudioConfig(enabled = true, sampleRate, source, bufferDurationMillis) }

        fun clockBase(base: ClockBase) = apply { clockBase = base }

        @JvmOverloads
        fun bundling(mode: Bundling, imuWindowMs: Int = 40, bundleRateHz: Int = 30, gnssMaxStalenessMs: Int = 1500) =
            apply { bundling = BundlingConfig(mode, imuWindowMs, bundleRateHz, gnssMaxStalenessMs) }

        fun sink(sink: Sink) = apply { this.sink = sink }
        fun thermalPolicy(policy: ThermalPolicy) = apply { thermalPolicy = policy }
        fun requireCalibration(require: Boolean) = apply { requireCalibration = require }

        fun build(): ChronyxConfig {
            val resolvedSink = sink
                ?: throw ChronyxConfigException("No sink configured. Call .sink(McapSink(file)) before build().")
            if (clockBase != ClockBase.BOOTTIME) {
                throw ChronyxConfigException("clockBase must be BOOTTIME; it is the only axis that survives deep sleep.")
            }
            camera?.let {
                if (it.fps !in 1..240) throw ChronyxConfigException("camera fps out of range (1..240): ${it.fps}")
                if (it.resolution.width <= 0 || it.resolution.height <= 0) {
                    throw ChronyxConfigException("camera resolution must be positive: ${it.resolution}")
                }
                if (it.keyframeIntervalSeconds < 1) {
                    throw ChronyxConfigException("keyframeIntervalSeconds must be >= 1: ${it.keyframeIntervalSeconds}")
                }
            }
            imu?.let {
                if (it.rateHz !in 1..1000) throw ChronyxConfigException("imu rateHz out of range (1..1000): ${it.rateHz}")
            }
            if (audio.enabled && audio.sampleRate !in intArrayOf(8000, 16000, 22050, 44100, 48000)) {
                throw ChronyxConfigException("audio sampleRate must be a standard rate: ${audio.sampleRate}")
            }
            if (bundling.mode == Bundling.PerFrame && camera == null) {
                throw ChronyxConfigException("Bundling.PerFrame requires a camera; configure .camera(...) or pick another mode.")
            }
            if (bundling.mode == Bundling.PerImuSample && imu == null) {
                throw ChronyxConfigException("Bundling.PerImuSample requires the IMU; configure .imu(...).")
            }
            if (bundling.mode == Bundling.FixedRate && bundling.bundleRateHz !in 1..240) {
                throw ChronyxConfigException("FixedRate bundleRateHz out of range (1..240): ${bundling.bundleRateHz}")
            }
            if (requireCalibration && (camera == null || imu == null)) {
                throw ChronyxConfigException("Calibration needs both camera and IMU (td/extrinsics are otherwise unobservable).")
            }
            return ChronyxConfig(
                camera, imu, gnss, audio, clockBase, bundling, resolvedSink, thermalPolicy, requireCalibration,
            )
        }
    }
}
