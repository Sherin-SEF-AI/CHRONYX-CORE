package com.chronyx.core.model

/** Which physical quantity and processing variant an [ImuSample] carries. */
enum class ImuChannel {
    /** Raw uncalibrated accelerometer (`TYPE_ACCELEROMETER_UNCALIBRATED`), no vendor bias removed. */
    ACCEL_RAW,

    /** Vendor-calibrated accelerometer (`TYPE_ACCELEROMETER`). */
    ACCEL_CAL,

    /** Estimated accelerometer bias reported alongside the uncalibrated event. */
    ACCEL_BIAS,

    /** Raw uncalibrated gyroscope (`TYPE_GYROSCOPE_UNCALIBRATED`), no vendor drift removed. */
    GYRO_RAW,

    /** Vendor-calibrated gyroscope (`TYPE_GYROSCOPE`). */
    GYRO_CAL,

    /** Estimated gyroscope drift/bias reported alongside the uncalibrated event. */
    GYRO_BIAS,
}

/**
 * A single tri-axis inertial sample on the BOOTTIME axis. VIO consumers must be able to choose raw
 * vs. calibrated, so every variant is emitted as its own sample rather than being merged.
 *
 * Units: accel m/s², gyro rad/s — exactly as Android `SensorEvent.values` reports them.
 *
 * @param tBoot BOOTTIME nanoseconds of the sample, after IMU-base detection/correction.
 */
data class ImuSample(
    val tBoot: Long,
    val channel: ImuChannel,
    val x: Float,
    val y: Float,
    val z: Float,
)
