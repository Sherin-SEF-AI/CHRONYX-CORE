package com.chronyx.core.sync

import com.chronyx.core.model.AudioBuffer
import com.chronyx.core.model.CameraFrame
import com.chronyx.core.model.ImuSample

/**
 * A camera↔IMU time-offset seed. CHRONYX computes only a **coarse** `td` (via gyro-vs-image rotation
 * cross-correlation during calibration). Online per-frame refinement of `td` is a filter state owned
 * by the VIO head (VINS-Mono / OpenVINS model it explicitly); CHRONYX deliberately does not pretend
 * to estimate it per frame.
 *
 * @param nanos seed estimate of `t_camera − t_imu`.
 * @param confidence `[0,1]`; 0 means unseeded (no calibration), treat the value as unusable.
 */
data class CameraImuOffsetSeed(val nanos: Long, val confidence: Double)

/**
 * A cooked GNSS fix interpolated to the bundle reference instant `t_ref`.
 *
 * @param stale true when the bracketing fixes are older than the configured max staleness — the
 *   position was NOT extrapolated; treat lat/lon as last-known, not as a fix at `t_ref`.
 */
data class InterpolatedFix(
    val tRef: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeMeters: Double,
    val horizontalAccuracyMeters: Float,
    val stale: Boolean,
    val ageMillis: Long,
)

/**
 * A set of sensor observations co-registered on the BOOTTIME axis at reference instant [tRef].
 * This is the unit a perception head consumes; it never touches Camera2 or `SensorManager` itself.
 *
 * Ownership: [frame] is HardwareBuffer-backed and MUST be [CameraFrame.close]d by the consumer
 * promptly (it returns the image to the capture pool). The engine applies backpressure by dropping
 * whole bundles if the consumer is slow, so a slow consumer loses bundles but never stalls capture.
 *
 * @param imuWindow all IMU samples in `[tRef − W, tRef + W]`, sorted ascending, so the head can
 *   integrate across the frame interval.
 * @param audio PCM buffer(s) overlapping the bundle interval.
 */
class SyncedBundle internal constructor(
    val tRef: Long,
    val frame: CameraFrame?,
    val imuWindow: List<ImuSample>,
    val gnss: InterpolatedFix?,
    val audio: List<AudioBuffer>,
    val cameraImuOffsetSeed: CameraImuOffsetSeed,
) {
    /** Releases the camera frame (and its HardwareBuffer). Call when done with the bundle. */
    fun release() {
        frame?.close()
    }
}
