package com.chronyx.core.model

/** Where the intrinsics came from — recorded so a consumer knows how much to trust them. */
enum class IntrinsicsSource {
    /** Device reported `LENS_INTRINSIC_CALIBRATION` (factory-calibrated). */
    REPORTED,

    /** Derived pinhole model from focal length + sensor physical size (no per-unit calibration). */
    DERIVED_FROM_FOCAL_LENGTH,
}

/**
 * Pinhole camera intrinsics + distortion for the captured resolution, plus provenance. Emitted on
 * `/camera/calibration` as `foxglove.CameraCalibration` so Foxglove and downstream heads can project.
 *
 * Most consumer phones (incl. the Galaxy A17) do NOT expose `LENS_INTRINSIC_CALIBRATION` or
 * `LENS_DISTORTION`, so [source] is usually [IntrinsicsSource.DERIVED_FROM_FOCAL_LENGTH] and [distortion]
 * is empty (model "none") — honest, and still the right starting point; a VIO head refines distortion
 * via offline calibration of the recorded video+IMU.
 *
 * @param k row-major 3×3 intrinsic matrix `[fx,0,cx, 0,fy,cy, 0,0,1]` for [width]×[height].
 * @param distortion distortion coefficients (empty when unavailable).
 * @param distortionModel e.g. "rational_polynomial", "brown_conrady", or "none".
 */
data class CameraCalibration(
    val width: Int,
    val height: Int,
    val k: DoubleArray,
    val distortion: DoubleArray,
    val distortionModel: String,
    val source: IntrinsicsSource,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Camera↔IMU extrinsic from `LENS_POSE_TRANSLATION`/`LENS_POSE_ROTATION` when the device reports them
 * (`LENS_POSE_REFERENCE` tells you the reference frame; GYROSCOPE means this IS the camera↔IMU pose).
 * [available] is false on the many devices that don't expose pose — the consumer must obtain extrinsics
 * via offline calibration in that case.
 */
data class CameraExtrinsics(
    val available: Boolean,
    val translationMeters: FloatArray,   // 3
    val rotationQuaternion: FloatArray,  // 4 (x,y,z,w)
    val referenceName: String,           // "GYROSCOPE" | "PRIMARY_CAMERA" | "UNDEFINED"
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        val UNAVAILABLE = CameraExtrinsics(false, FloatArray(3), floatArrayOf(0f, 0f, 0f, 1f), "UNDEFINED")
    }
}

/**
 * Inertial sensor specs from the Android `Sensor` objects, emitted once on `/imu/intrinsics`. Android
 * does not expose noise density / random walk, so those are NOT included — a consumer estimates them
 * offline (Allan variance) from the recorded raw IMU stream. We record what the platform provides.
 */
data class ImuIntrinsics(
    val accelName: String,
    val accelVendor: String,
    val accelResolution: Float,        // m/s² per LSB
    val accelMaxRangeMps2: Float,
    val gyroName: String,
    val gyroVendor: String,
    val gyroResolution: Float,         // rad/s per LSB
    val gyroMaxRangeRadPerSec: Float,
    val targetRateHz: Int,
    val achievedRateHz: Double,
)

/**
 * One-shot self-describing session header, emitted on `/device/info`. Makes a recording reusable
 * offline without any side files.
 */
data class DeviceInfo(
    val sessionId: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val osVersion: String,
    val sdkInt: Int,
    val clockBaseName: String,
    val cameraId: String,
    val sensorPhysicalWidthMm: Float,
    val sensorPhysicalHeightMm: Float,
    val activeArrayWidthPx: Int,
    val activeArrayHeightPx: Int,
    val calibration: CameraCalibration,
    val extrinsics: CameraExtrinsics,
)
