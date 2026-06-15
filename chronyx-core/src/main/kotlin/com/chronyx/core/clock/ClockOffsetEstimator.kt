package com.chronyx.core.clock

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs

/**
 * Maintains a running estimate of the (assumed constant over short windows) offset between a foreign
 * clock domain and the BOOTTIME master axis: `boot ≈ foreign + offset`.
 *
 * Used in two places:
 *  - Camera with `SENSOR_INFO_TIMESTAMP_SOURCE == UNKNOWN`: each `onCaptureCompleted` pairs the
 *    frame's `SENSOR_TIMESTAMP` (foreign) with a fresh `elapsedRealtimeNanos()` (boot at arrival).
 *  - IMU base detection: pairs `SensorEvent.timestamp` against `elapsedRealtimeNanos()` at receive.
 *
 * A single subtraction is wrong because sample-arrival jitter is heavy-tailed and one-sided (the
 * boot read always trails the true event by processing latency). We therefore keep a sliding window
 * of deltas and take a robust location estimate:
 *
 *  1. median of the window (resistant to the latency tail),
 *  2. MAD (median absolute deviation) → reject samples beyond [outlierMadFactor] × MAD,
 *  3. re-estimate the offset as the mean of the surviving inliers.
 *
 * [confidence] in `[0,1]` blends the inlier fraction, the relative MAD spread, and window fill so a
 * consumer (and the diagnostics screen) can tell a locked estimate from a noisy one.
 *
 * Thread-safe: the producing sensor callback calls [update] while sync/diagnostics threads read.
 */
class ClockOffsetEstimator(
    private val windowSize: Int = 256,
    private val outlierMadFactor: Double = 3.0,
    /** Below this many samples the estimate is considered unconverged regardless of spread. */
    private val minSamplesForConfidence: Int = 16,
) {
    init {
        require(windowSize >= 4) { "windowSize must be >= 4" }
        require(outlierMadFactor > 0.0) { "outlierMadFactor must be > 0" }
    }

    private val lock = ReentrantReadWriteLock()

    private val deltas = LongArray(windowSize)
    private var head = 0
    private var filled = 0

    @Volatile private var offsetNanosCached = 0L
    @Volatile private var confidenceCached = 0.0
    @Volatile private var madNanosCached = 0L

    /** Best current estimate of `boot - foreign` in nanoseconds. */
    val offsetNanos: Long get() = offsetNanosCached

    /** Estimate quality in `[0,1]`. 0 until [minSamplesForConfidence] deltas have arrived. */
    val confidence: Double get() = confidenceCached

    /** Median absolute deviation of the inlier window, in ns — exposed for diagnostics. */
    val spreadNanos: Long get() = madNanosCached

    val sampleCount: Int get() = lock.read { filled }

    /**
     * Feed one simultaneously-observed (foreign, boot) pair. Both in nanoseconds. Returns the
     * updated offset estimate.
     */
    fun update(foreignNanos: Long, bootNanos: Long): Long = lock.write {
        deltas[head] = bootNanos - foreignNanos
        head = (head + 1) % windowSize
        if (filled < windowSize) filled++
        recompute()
        offsetNanosCached
    }

    /** Convert a foreign-domain timestamp to BOOTTIME using the current estimate. */
    fun toBoot(foreignNanos: Long): Long = foreignNanos + offsetNanosCached

    fun reset() = lock.write {
        head = 0
        filled = 0
        offsetNanosCached = 0L
        confidenceCached = 0.0
        madNanosCached = 0L
    }

    // Must be called under the write lock.
    private fun recompute() {
        val n = filled
        if (n == 0) return
        val scratch = LongArray(n)
        System.arraycopy(deltas, 0, scratch, 0, n)
        scratch.sort()

        val median = medianOfSorted(scratch, n)

        // MAD: median of |x - median|.
        val absDev = LongArray(n) { abs(scratch[it] - median) }
        absDev.sort()
        val mad = medianOfSorted(absDev, n)
        madNanosCached = mad

        // Inliers within outlierMadFactor * MAD. A zero MAD (degenerate, all-equal window) keeps
        // everything as inliers.
        val threshold = if (mad == 0L) Long.MAX_VALUE else (outlierMadFactor * mad).toLong()
        var sum = 0L
        var inliers = 0
        for (i in 0 until n) {
            if (abs(scratch[i] - median) <= threshold) {
                sum += scratch[i]
                inliers++
            }
        }
        offsetNanosCached = if (inliers > 0) sum / inliers else median

        // Confidence: requires a minimum sample count, rewards a high inlier fraction and a tight
        // spread relative to the offset magnitude.
        if (n < minSamplesForConfidence) {
            confidenceCached = 0.0
            return
        }
        val inlierFraction = inliers.toDouble() / n
        val fillFraction = n.toDouble() / windowSize
        // Spread term: 1 at zero MAD, decaying as MAD grows past ~1ms.
        val spreadTerm = 1.0 / (1.0 + (mad.toDouble() / 1_000_000.0))
        confidenceCached = (inlierFraction * spreadTerm * (0.5 + 0.5 * fillFraction))
            .coerceIn(0.0, 1.0)
    }

    private fun medianOfSorted(sorted: LongArray, n: Int): Long {
        val mid = n / 2
        return if (n % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
