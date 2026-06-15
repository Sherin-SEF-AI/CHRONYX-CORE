package com.chronyx.core.api

import com.chronyx.core.model.SyncDiagnostics
import com.chronyx.core.sync.SyncedBundle
import kotlinx.coroutines.flow.Flow

/** Lifecycle state of a capture session, surfaced for UIs and the foreground service notification. */
enum class CaptureState { STARTING, CALIBRATING, ARMED, RECORDING, STOPPING, STOPPED, ERROR }

/**
 * A running capture session. Returned by [Chronyx.start]; the caller (or the foreground service)
 * owns its lifecycle and must call [stop] to flush the file and release hardware.
 *
 * The same `CaptureSession` object underlies both consumption models: embedding the AAR and owning
 * the lifecycle directly, or letting `chronyx-service` own it. The service is a thin lifecycle owner,
 * not a reimplementation.
 */
interface CaptureSession {
    /** Time-aligned bundles for live perception. Cold per-collector is NOT assumed: this is a hot
     *  shared flow; a slow collector drops whole bundles (with telemetry) rather than stalling capture. */
    val bundles: Flow<SyncedBundle>

    /** Periodic self-describing health snapshots (also written to `/diag/sync`). */
    val diagnostics: Flow<SyncDiagnostics>

    /** Current lifecycle state. */
    val state: Flow<CaptureState>

    /** Java-friendly callback alternative to collecting [bundles]. Pass null to clear. */
    fun setBundleListener(listener: BundleListener?)

    /** Drop a timestamped operator marker (BOOTTIME now) onto the `/markers` channel. */
    fun mark(label: String)

    /** A point-in-time summary of the session, for manifests / file browsers. */
    fun summary(): SessionSummary

    /** Flushes the MCAP file, releases camera/mic/GNSS, and drops the wakelock. Idempotent. */
    fun stop()

    /** Java SAM-friendly listener. */
    fun interface BundleListener {
        fun onBundle(bundle: SyncedBundle)
    }
}

/**
 * A self-describing summary of a capture session, serialized to a JSON manifest sidecar so a file
 * browser / pipeline can show duration, channels, and sync quality without parsing the MCAP.
 */
data class SessionSummary(
    val sessionId: String,
    val deviceModel: String,
    val startWallMillis: Long,
    val startBootNanos: Long,
    val durationNanos: Long,
    /** Fraction of diagnostics snapshots where sync was locked, in `[0,1]`. */
    val syncLockedFraction: Double,
    val markerCount: Int,
    val channels: List<String>,
    val intrinsicsSource: String?,
    val calibrationK: DoubleArray?,
    val mcapBytes: Long,
    /** Cooked GNSS fixes received. */
    val gnssFixCount: Long,
    /** True only if GNSS produced fixes — the downstream mapping gate for georeferencing. */
    val georeferenceable: Boolean,
    /** Authoritative achieved IMU rate (Hz); the configured rate is only a request the HAL rounds. */
    val imuAchievedRateHz: Double,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
