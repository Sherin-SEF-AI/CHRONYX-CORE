package com.chronyx.core.model

import com.chronyx.core.clock.xdevice.BootToUtcMapper

// Diagnostics lives in the model package so both the api surface and the mcap sink can reference it
// without a dependency cycle.

/** Camera channel health, refreshed every diagnostics tick. */
data class CameraDiagnostics(
    val timestampSource: CameraTimestampSource,
    val achievedFps: Double,
    val currentRollingShutterSkewNanos: Long,
    val currentExposureNanos: Long,
    val tdSeedNanos: Long?,
    val tdConfidence: Double,
    /** Offset-estimator confidence for an UNKNOWN-source camera; 1.0 when REALTIME. */
    val clockOffsetConfidence: Double,
    val encoderQueueDepth: Int,
    val droppedFrames: Long,
)

/** IMU channel health. */
data class ImuDiagnostics(
    /** The clock base detected for `SensorEvent.timestamp` at startup. */
    val detectedBase: String,
    val achievedRateHz: Double,
    val targetRateHz: Double,
    val droppedSamples: Long,
    /** Offset applied (ns) if the detected base was not BOOTTIME; 0 otherwise. */
    val appliedOffsetNanos: Long,
)

/** GNSS channel health. */
data class GnssDiagnostics(
    val fixAgeMillis: Long,
    val satellitesInFix: Int,
    val satellitesVisible: Int,
    val satellitesUsed: Int,
    val meanCn0DbHz: Double,
    val rawMeasurementsSupported: Boolean,
    val rawMeasurementsActive: Boolean,
    /** elapsedRealtime ↔ UTC residual, the key cross-device alignment number. */
    val utcResidualNanos: Long,
    val bootToUtc: BootToUtcMapper.MapParams?,
    val droppedEpochs: Long,
)

/** Audio channel health. */
data class AudioDiagnostics(
    val sampleRate: Int,
    val source: String,
    /** Age of the last BOOTTIME anchor refresh; stale anchors mean drifting per-sample stamps. */
    val anchorAgeMillis: Long,
    val droppedBuffers: Long,
)

/** Sync engine + sink health. */
data class EngineDiagnostics(
    val bundleRateHz: Double,
    val droppedBundles: Long,
    val mcapWriteThroughputBytesPerSec: Long,
    val mcapBytesWritten: Long,
)

/** Thermal + storage health. */
data class ResourceDiagnostics(
    /** `PowerManager.THERMAL_STATUS_*`. */
    val thermalStatus: Int,
    val activeDegradation: String?,
    val batteryTemperatureCelsius: Float,
    val freeStorageBytes: Long,
)

/**
 * A self-describing snapshot of capture health on the BOOTTIME axis, emitted periodically on
 * `session.diagnostics` and written to `/diag/sync` so the recorded file explains its own quality.
 *
 * The diagnostics screen's whole job is to make a bad clock source visible at a glance — so the
 * operator can tell, before recording, whether the IMU spike and the visual event will land on the
 * same instant.
 */
data class SyncDiagnostics(
    val tBoot: Long,
    val recording: Boolean,
    val syncLocked: Boolean,
    val camera: CameraDiagnostics?,
    val imu: ImuDiagnostics?,
    val gnss: GnssDiagnostics?,
    val audio: AudioDiagnostics?,
    val engine: EngineDiagnostics,
    val resources: ResourceDiagnostics,
)
