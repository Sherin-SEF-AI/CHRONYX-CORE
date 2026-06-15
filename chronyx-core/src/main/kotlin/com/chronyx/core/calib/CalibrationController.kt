package com.chronyx.core.calib

import android.content.Context
import com.chronyx.core.sync.CameraImuOffsetSeed
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Live excitation feedback shown during the calibration gesture. */
data class ExcitationState(
    val gyroEnergy: DoubleArray,   // per-axis angular-rate energy
    val accelEnergy: DoubleArray,  // per-axis linear energy (gravity-removed proxy)
    val sufficient: Boolean,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** Persisted coarse calibration result, keyed by device + camera config. */
data class CalibrationResult(
    val tdSeedNanos: Long,
    val confidence: Double,
    val deviceKey: String,
)

/**
 * Guides the start-of-session calibration gesture (figure-8 / rotate-and-translate) that makes the
 * camera↔IMU time offset `td` and extrinsics observable. Without BOTH rotation and translation they
 * are unobservable and any estimate floats, so the gesture is accepted only once per-axis excitation
 * thresholds are met.
 *
 * It then computes a **coarse** `td` seed by cross-correlating gyro angular-rate magnitude against the
 * image-motion proxy (see [LumaMotion]) over a lag search, and persists it. Online refinement of `td`
 * is the VIO head's job; this is only a seed.
 *
 * Non-VIO heads skip calibration entirely (a config flag), so this controller is never constructed.
 */
class CalibrationController(
    context: Context,
    private val deviceKey: String,
) {
    private val prefs = context.getSharedPreferences("chronyx_calib", Context.MODE_PRIVATE)

    // Excitation accumulators (Welford-style running variance per axis).
    private val gyroAcc = AxisStats()
    private val accelAcc = AxisStats()

    // Resampled series for cross-correlation, on a uniform grid.
    private val gyroSeries = ArrayList<TimedScalar>()
    private val motionSeries = ArrayList<TimedScalar>()
    @Volatile private var collecting = false

    private companion object {
        const val GYRO_ENERGY_THRESHOLD = 0.5      // (rad/s)^2 variance per axis
        const val ACCEL_ENERGY_THRESHOLD = 0.5     // (m/s^2)^2 variance per axis
        const val GRID_HZ = 100.0
        const val MAX_LAG_MS = 80
    }

    fun start() {
        collecting = true
        gyroAcc.reset(); accelAcc.reset()
        gyroSeries.clear(); motionSeries.clear()
    }

    fun feedGyro(tBoot: Long, x: Float, y: Float, z: Float) {
        if (!collecting) return
        gyroAcc.add(x.toDouble(), y.toDouble(), z.toDouble())
        gyroSeries.add(TimedScalar(tBoot, sqrt((x * x + y * y + z * z).toDouble())))
    }

    fun feedAccel(x: Float, y: Float, z: Float) {
        if (!collecting) return
        accelAcc.add(x.toDouble(), y.toDouble(), z.toDouble())
    }

    fun feedFrameMotion(tMidBoot: Long, motion: Double) {
        if (!collecting) return
        motionSeries.add(TimedScalar(tMidBoot, motion))
    }

    fun excitation(): ExcitationState {
        val g = gyroAcc.variance()
        val a = accelAcc.variance()
        val sufficient = g.all { it >= GYRO_ENERGY_THRESHOLD } && a.all { it >= ACCEL_ENERGY_THRESHOLD }
        return ExcitationState(g, a, sufficient)
    }

    /**
     * Finalizes calibration. Returns the coarse [CalibrationResult] and persists it, or null if
     * excitation was insufficient or the two series don't overlap enough to correlate.
     */
    fun accept(): CalibrationResult? {
        collecting = false
        if (!excitation().sufficient) {
            Timber.w("Calibration rejected: insufficient excitation")
            return null
        }
        val (lagNanos, confidence) = crossCorrelateLag() ?: run {
            Timber.w("Calibration rejected: insufficient series overlap for cross-correlation")
            return null
        }
        val result = CalibrationResult(lagNanos, confidence, deviceKey)
        persist(result)
        Timber.i("Calibration accepted: td seed=${lagNanos}ns confidence=$confidence")
        return result
    }

    fun cancel() { collecting = false }

    fun load(): CalibrationResult? {
        if (!prefs.contains(keyTd())) return null
        return CalibrationResult(
            tdSeedNanos = prefs.getLong(keyTd(), 0L),
            confidence = prefs.getFloat(keyConf(), 0f).toDouble(),
            deviceKey = deviceKey,
        )
    }

    fun asSeed(): CameraImuOffsetSeed =
        load()?.let { CameraImuOffsetSeed(it.tdSeedNanos, it.confidence) } ?: CameraImuOffsetSeed(0L, 0.0)

    private fun persist(r: CalibrationResult) {
        prefs.edit().putLong(keyTd(), r.tdSeedNanos).putFloat(keyConf(), r.confidence.toFloat()).apply()
    }

    private fun keyTd() = "td_$deviceKey"
    private fun keyConf() = "conf_$deviceKey"

    /**
     * Resamples both series onto a uniform grid and finds the lag (camera relative to IMU) maximizing
     * normalized cross-correlation. Returns (lagNanos, peakCorrelation) or null.
     */
    private fun crossCorrelateLag(): Pair<Long, Double>? {
        if (gyroSeries.size < 16 || motionSeries.size < 8) return null
        val start = max(gyroSeries.first().t, motionSeries.first().t)
        val end = min(gyroSeries.last().t, motionSeries.last().t)
        if (end - start < 500_000_000L) return null // need >= 0.5 s overlap

        val dtNanos = (1e9 / GRID_HZ).toLong()
        val n = ((end - start) / dtNanos).toInt()
        if (n < 32) return null

        val gyro = resample(gyroSeries, start, dtNanos, n)
        val motion = resample(motionSeries, start, dtNanos, n)
        normalize(gyro); normalize(motion)

        val maxLag = (MAX_LAG_MS * GRID_HZ / 1000.0).toInt()
        var bestLag = 0
        var bestCorr = -Double.MAX_VALUE
        for (lag in -maxLag..maxLag) {
            var sum = 0.0
            var count = 0
            for (i in 0 until n) {
                val j = i + lag
                if (j in 0 until n) { sum += gyro[i] * motion[j]; count++ }
            }
            if (count > 0) {
                val corr = sum / count
                if (corr > bestCorr) { bestCorr = corr; bestLag = lag }
            }
        }
        // Positive lag means motion lags gyro → camera stamp is later than IMU by lag·dt.
        val lagNanos = bestLag.toLong() * dtNanos
        val confidence = bestCorr.coerceIn(0.0, 1.0)
        return lagNanos to confidence
    }

    private fun resample(series: List<TimedScalar>, start: Long, dtNanos: Long, n: Int): DoubleArray {
        val out = DoubleArray(n)
        var si = 0
        for (i in 0 until n) {
            val t = start + i * dtNanos
            while (si < series.size - 1 && series[si + 1].t < t) si++
            // nearest-neighbour hold; adequate at 100 Hz grid for a coarse seed
            out[i] = series[min(si, series.size - 1)].v
        }
        return out
    }

    private fun normalize(a: DoubleArray) {
        var mean = 0.0
        for (v in a) mean += v
        mean /= a.size
        var sd = 0.0
        for (v in a) sd += (v - mean) * (v - mean)
        sd = sqrt(sd / a.size)
        if (sd < 1e-9) { for (i in a.indices) a[i] = 0.0; return }
        for (i in a.indices) a[i] = (a[i] - mean) / sd
    }

    private class TimedScalar(val t: Long, val v: Double)

    private class AxisStats {
        private val count = LongArray(3)
        private val mean = DoubleArray(3)
        private val m2 = DoubleArray(3)
        fun reset() { count.fill(0); mean.fill(0.0); m2.fill(0.0) }
        fun add(x: Double, y: Double, z: Double) { upd(0, x); upd(1, y); upd(2, z) }
        private fun upd(i: Int, v: Double) {
            count[i]++
            val d = v - mean[i]
            mean[i] += d / count[i]
            m2[i] += d * (v - mean[i])
        }
        fun variance(): DoubleArray = DoubleArray(3) { if (count[it] > 1) m2[it] / (count[it] - 1) else 0.0 }
    }
}
