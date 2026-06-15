package com.chronyx.core.model

import android.hardware.HardwareBuffer
import android.media.Image

/**
 * The camera timestamp source as read from `CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE`.
 * This single bit decides whether `SENSOR_TIMESTAMP` is directly comparable to the IMU/GNSS axis.
 */
enum class CameraTimestampSource {
    /** `SENSOR_TIMESTAMP` is already on the BOOTTIME axis — use directly. */
    REALTIME,

    /** Camera clock is not comparable to BOOTTIME; mapped via a [com.chronyx.core.clock.ClockOffsetEstimator]. */
    UNKNOWN,
}

/**
 * Per-frame camera metadata, on the BOOTTIME axis. Written to `/camera/meta` and attached to the
 * live bundle. Rolling-shutter skew is a first-class field, not a footnote: downstream VIO needs it
 * to model per-row exposure time.
 *
 * @param tSensorBoot frame start-of-exposure (`SENSOR_TIMESTAMP`), normalized to BOOTTIME.
 * @param tMidBoot mid-exposure reference `tSensorBoot + exposureNanos/2` — the VIO timestamp convention.
 * @param exposureNanos `SENSOR_EXPOSURE_TIME`.
 * @param rollingShutterSkewNanos `SENSOR_ROLLING_SHUTTER_SKEW` (top-to-bottom readout, ~20–30 ms typical).
 * @param tdSeedNanos coarse camera↔IMU offset seed from calibration (VIO refines online); null if uncalibrated.
 * @param tdConfidence confidence of [tdSeedNanos] in `[0,1]`; 0 if unseeded.
 * @param offsetConfidence for [CameraTimestampSource.UNKNOWN], the offset-estimator confidence; 1.0 for REALTIME.
 */
data class CameraFrameMeta(
    val frameIndex: Long,
    val tSensorBoot: Long,
    val tMidBoot: Long,
    val exposureNanos: Long,
    val rollingShutterSkewNanos: Long,
    val timestampSource: CameraTimestampSource,
    val tdSeedNanos: Long?,
    val tdConfidence: Double,
    val offsetConfidence: Double,
    // Photometric / focus state per frame — needed so downstream can reason about exposure changes
    // and confirm focus stayed fixed (stable focus ⇒ stable intrinsics for VIO/mapping).
    val iso: Int,
    val focusDistanceDiopters: Float,
    val focalLengthMm: Float,
    val afState: Int,
    val aeState: Int,
)

/**
 * A camera frame as delivered to the live [com.chronyx.core.sync.SyncedBundle]: the decoded image
 * is HardwareBuffer-backed (YUV, not converted to RGB in the capture path) so a GPU perception head
 * can sample it directly. The caller MUST [close] the frame promptly to return the buffer to the
 * ImageReader pool — holding frames stalls capture.
 */
class CameraFrame internal constructor(
    val meta: CameraFrameMeta,
    private val image: Image?,
    /** The underlying HardwareBuffer handle for zero-copy GPU sampling / IPC, when available. */
    val hardwareBuffer: HardwareBuffer?,
    private val onClose: (CameraFrame) -> Unit,
) : AutoCloseable {

    @Volatile private var closed = false

    /** The raw YUV [Image]; valid only until [close]. Null if delivered as a buffer handle only. */
    val rawImage: Image? get() = if (closed) null else image

    val tMidBoot: Long get() = meta.tMidBoot

    override fun close() {
        if (closed) return
        closed = true
        try {
            image?.close()
        } finally {
            hardwareBuffer?.close()
            onClose(this)
        }
    }
}

/** Codec of an [EncodedVideoFrame]. Mirrors the public `Codec` config enum. */
enum class VideoCodec { HEVC, AVC }

/**
 * An encoded video access unit destined for the `/camera/video` MCAP channel. `log_time` for the
 * message is [tMidBoot]. The encoder runs off the capture thread; this record is what crosses the
 * bounded encoder→sink queue.
 *
 * @param data the encoded access unit bytes (may be a slice of a reused buffer — consumers must copy
 *   if they retain it past the sink call).
 */
data class EncodedVideoFrame(
    val frameIndex: Long,
    val tMidBoot: Long,
    val keyframe: Boolean,
    val codec: VideoCodec,
    val data: ByteArray,
    val size: Int,
) {
    // Identity equals/hashCode: these are large mutable-ish payloads, never used as map keys.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
