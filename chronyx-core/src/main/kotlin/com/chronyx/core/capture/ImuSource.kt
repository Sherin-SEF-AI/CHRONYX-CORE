package com.chronyx.core.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.chronyx.core.api.ImuConfig
import com.chronyx.core.clock.ClockOffsetEstimator
import com.chronyx.core.model.ImuChannel
import com.chronyx.core.model.ImuSample
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Inertial capture. Registers the **uncalibrated** accelerometer and gyroscope as the primary VIO
 * stream (so no hidden vendor bias correction is inherited) plus the calibrated variants and the
 * reported bias channels, letting downstream choose.
 *
 * `SensorEvent.timestamp` is *nominally* BOOTTIME ns, but some devices report it on the uptime base
 * (which pauses in deep sleep). At startup we pair incoming timestamps against `elapsedRealtimeNanos()`
 * at receive to estimate the offset; if it exceeds a threshold we classify the base as non-BOOTTIME
 * and apply the correction to every sample. The detected base is logged prominently.
 */
class ImuSource(
    private val context: Context,
    private val config: ImuConfig,
    private val out: SourceSink,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    private val offsetEstimator = ClockOffsetEstimator(windowSize = 128)
    @Volatile private var baseDetected = false
    @Volatile private var appliedOffsetNanos = 0L
    @Volatile private var detectedBaseName = "DETECTING"
    private var warmupSamples = 0

    private val droppedSamples = AtomicLong(0)
    @Volatile private var fpsAnchorBoot = 0L
    @Volatile private var fpsCount = 0L
    @Volatile private var achievedRate = 0.0

    val detectedBase: String get() = detectedBaseName
    val appliedOffset: Long get() = appliedOffsetNanos
    val achievedRateHz: Double get() = achievedRate
    val targetRateHz: Double get() = config.rateHz.toDouble()
    val droppedSampleCount: Long get() = droppedSamples.get()

    private companion object {
        // If the robust boot↔event offset exceeds this, the event base is not BOOTTIME.
        const val BASE_THRESHOLD_NANOS = 10_000_000L // 10 ms
        const val WARMUP_SAMPLES = 64
    }

    fun start() {
        thread = HandlerThread("chronyx-imu").also { it.start() }
        handler = Handler(thread!!.looper)

        val periodUs = (1_000_000 / config.rateHz).coerceAtLeast(0)
        val latencyUs = config.maxReportLatencyUs.toInt().coerceAtLeast(0)

        val toRegister = buildList {
            if (config.uncalibrated) {
                add(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
                add(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
            }
            if (config.alsoCalibrated || !config.uncalibrated) {
                add(Sensor.TYPE_ACCELEROMETER)
                add(Sensor.TYPE_GYROSCOPE)
            }
        }
        for (type in toRegister) {
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor == null) {
                Timber.w("IMU sensor type $type unavailable on this device")
                continue
            }
            if (type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED ||
                (type == Sensor.TYPE_ACCELEROMETER && accelSensor == null)
            ) accelSensor = sensor
            if (type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED ||
                (type == Sensor.TYPE_GYROSCOPE && gyroSensor == null)
            ) gyroSensor = sensor
            val ok = sensorManager.registerListener(this, sensor, periodUs, latencyUs, handler)
            if (!ok) {
                // Fall back to FASTEST if the explicit rate was rejected.
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, latencyUs, handler)
            }
        }
    }

    /** Platform-reported inertial specs for `/imu/intrinsics`. Noise density is NOT provided by Android. */
    fun intrinsics(): com.chronyx.core.model.ImuIntrinsics {
        val a = accelSensor
        val g = gyroSensor
        return com.chronyx.core.model.ImuIntrinsics(
            accelName = a?.name ?: "unknown",
            accelVendor = a?.vendor ?: "unknown",
            accelResolution = a?.resolution ?: 0f,
            accelMaxRangeMps2 = a?.maximumRange ?: 0f,
            gyroName = g?.name ?: "unknown",
            gyroVendor = g?.vendor ?: "unknown",
            gyroResolution = g?.resolution ?: 0f,
            gyroMaxRangeRadPerSec = g?.maximumRange ?: 0f,
            targetRateHz = config.rateHz,
            achievedRateHz = achievedRate,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        val arrivalBoot = SystemClock.elapsedRealtimeNanos()
        if (!baseDetected) detectBase(event.timestamp, arrivalBoot)

        val tBoot = event.timestamp + appliedOffsetNanos
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                out.onImu(ImuSample(tBoot, ImuChannel.ACCEL_RAW, event.values[0], event.values[1], event.values[2]))
                if (event.values.size >= 6) {
                    out.onImu(ImuSample(tBoot, ImuChannel.ACCEL_BIAS, event.values[3], event.values[4], event.values[5]))
                }
            }
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                out.onImu(ImuSample(tBoot, ImuChannel.GYRO_RAW, event.values[0], event.values[1], event.values[2]))
                if (event.values.size >= 6) {
                    out.onImu(ImuSample(tBoot, ImuChannel.GYRO_BIAS, event.values[3], event.values[4], event.values[5]))
                }
            }
            Sensor.TYPE_ACCELEROMETER ->
                out.onImu(ImuSample(tBoot, ImuChannel.ACCEL_CAL, event.values[0], event.values[1], event.values[2]))
            Sensor.TYPE_GYROSCOPE ->
                out.onImu(ImuSample(tBoot, ImuChannel.GYRO_CAL, event.values[0], event.values[1], event.values[2]))
        }
        updateRate(arrivalBoot)
    }

    private fun detectBase(eventTs: Long, arrivalBoot: Long) {
        offsetEstimator.update(foreignNanos = eventTs, bootNanos = arrivalBoot)
        warmupSamples++
        if (warmupSamples < WARMUP_SAMPLES) return
        val offset = offsetEstimator.offsetNanos
        if (abs(offset) > BASE_THRESHOLD_NANOS) {
            appliedOffsetNanos = offset
            detectedBaseName = "NON_BOOTTIME(offset=${offset / 1_000_000}ms)"
            Timber.w("IMU timestamp base is NOT BOOTTIME; applying offset ${offset}ns")
        } else {
            appliedOffsetNanos = 0L
            detectedBaseName = "BOOTTIME"
            Timber.i("IMU timestamp base detected as BOOTTIME")
        }
        baseDetected = true
    }

    private fun updateRate(nowBoot: Long) {
        fpsCount++
        if (fpsAnchorBoot == 0L) { fpsAnchorBoot = nowBoot; return }
        val elapsed = nowBoot - fpsAnchorBoot
        if (elapsed >= 1_000_000_000L) {
            achievedRate = fpsCount * 1e9 / elapsed
            fpsAnchorBoot = nowBoot
            fpsCount = 0
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { /* accuracy transitions are not fatal */ }

    fun stop() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely(); thread = null
        handler = null
    }
}
