package com.chronyx.core.api

import com.chronyx.core.model.AudioBuffer
import com.chronyx.core.model.CameraCalibration
import com.chronyx.core.model.DeviceInfo
import com.chronyx.core.model.EncodedVideoFrame
import com.chronyx.core.model.CameraFrameMeta
import com.chronyx.core.model.GnssFix
import com.chronyx.core.model.GnssRawEpoch
import com.chronyx.core.model.ImuIntrinsics
import com.chronyx.core.model.ImuSample
import com.chronyx.core.model.SyncDiagnostics

/**
 * Service-provider interface for a recording destination. This is the seam that keeps `chronyx-core`
 * free of any file-format dependency: the core capture sources push **full-rate** per-channel data
 * here, and an implementation (e.g. `McapSink` in `chronyx-mcap`) serializes it.
 *
 * Two consumption paths coexist and must not be confused:
 *  - The [Sink] records every channel at its native rate for the file (no downsampling to bundles).
 *  - `CaptureSession.bundles` delivers time-aligned [com.chronyx.core.sync.SyncedBundle]s for live
 *    perception. A head that only needs the file ignores the bundle flow, and vice versa.
 *
 * All `write*` methods are called from capture/encoder threads and MUST NOT block — an implementation
 * buffers and flushes on its own dispatcher. Timestamps are BOOTTIME ns and become the message
 * `log_time`.
 */
interface Sink : AutoCloseable {

    /** Called once before any `write*`, with immutable session metadata for the file header. */
    fun open(metadata: SessionMetadata)

    fun writeVideo(frame: EncodedVideoFrame)
    fun writeCameraMeta(meta: CameraFrameMeta)

    /** One-shot, self-describing session header (device, intrinsics provenance, extrinsics). */
    fun writeDeviceInfo(info: DeviceInfo)

    /** Camera intrinsics + distortion for the captured resolution; emitted at start and on change. */
    fun writeCameraCalibration(calibration: CameraCalibration, tBoot: Long)

    /** Inertial sensor specs; emitted once at start. */
    fun writeImuIntrinsics(intrinsics: ImuIntrinsics, tBoot: Long)

    /** An operator-inserted event marker at [tBoot] (BOOTTIME). */
    fun writeMarker(tBoot: Long, index: Long, label: String)

    fun writeImu(sample: ImuSample)
    fun writeGnssFix(fix: GnssFix)
    fun writeGnssRaw(epoch: GnssRawEpoch)
    fun writeAudio(buffer: AudioBuffer)
    fun writeDiagnostics(diagnostics: SyncDiagnostics)

    /** Flushes, finalizes the file (indexes, statistics, footer), and releases resources. Idempotent. */
    override fun close()

    /** Current bytes written, for engine diagnostics. */
    val bytesWritten: Long

    /**
     * Per-channel counts of messages the sink had to DROP because its write queue overflowed — i.e.
     * genuine file-integrity loss. Zero on a healthy capture. This is distinct from the live-consumer
     * bundle-window cache (which evicts old samples by design and is NOT data loss).
     */
    val sinkDrops: SinkDrops
}

/** File-write drop counts. All zero means the recorded file lost nothing. */
data class SinkDrops(
    val video: Long = 0,
    val cameraMeta: Long = 0,
    val imu: Long = 0,
    val gnss: Long = 0,
    val audio: Long = 0,
    val diagnostics: Long = 0,
)

/**
 * Immutable description of a capture session, written into the file header so a recording is
 * self-describing offline.
 *
 * @param clockBaseName always "BOOTTIME" for valid sessions.
 * @param startBootNanos session start on the master axis.
 * @param startWallMillis session start UTC, for human-readable correlation only.
 */
data class SessionMetadata(
    val sessionId: String,
    val deviceModel: String,
    val clockBaseName: String,
    val startBootNanos: Long,
    val startWallMillis: Long,
    val cameraWidth: Int,
    val cameraHeight: Int,
    val cameraFps: Int,
    val videoCodecName: String,
    val imuRateHz: Int,
    val audioSampleRate: Int,
)
