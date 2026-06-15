package com.chronyx.core.clock.xdevice

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs

/**
 * Disciplines a device's BOOTTIME axis to GNSS UTC so that files recorded independently on several
 * phones (the swarm case, where NTP/RTT is useless) can be co-registered offline.
 *
 * Each cooked GNSS fix yields a `(elapsedRealtimeNanos, utcNanos)` pair. Raw `GnssClock`
 * (`FullBiasNanos`/`BiasNanos`/`DriftNanosPerSecond`) yields a tighter pair where available. We fit
 *
 *     utc ≈ offsetNanos + (1 + driftPpm·1e-6) · (boot − bootRef)
 *
 * with a robust **Theil–Sen** estimator (median of pairwise slopes, median intercept) over a sliding
 * window. Theil–Sen tolerates the occasional wild fix without a RANSAC consensus loop.
 *
 * Honest envelope (documented for callers): low-tens-of-ms with cooked locations, low-ms with raw
 * measurements. Do not overstate cross-device alignment beyond this.
 */
class BootToUtcMapper(
    private val windowSize: Int = 64,
    private val minSamplesForFit: Int = 8,
) {
    init { require(windowSize >= 4) { "windowSize must be >= 4" } }

    private val lock = ReentrantReadWriteLock()
    private val bootNs = LongArray(windowSize)
    private val utcNs = LongArray(windowSize)
    private var head = 0
    private var filled = 0
    private var bootRef = 0L
    private var utcRef = 0L

    @Volatile private var params: MapParams? = null

    /** Current fit parameters, or null until [minSamplesForFit] pairs have accumulated. */
    val current: MapParams? get() = params

    /**
     * @param bootNanos BOOTTIME instant of the fix (`Location.getElapsedRealtimeNanos()`).
     * @param utcNanos  UTC of the fix in ns. From cooked fix `time` (ms→ns) or, more precisely, raw
     *   GnssClock GPS time converted to UTC.
     */
    fun addPair(bootNanos: Long, utcNanos: Long) = lock.write {
        if (filled == 0) { bootRef = bootNanos; utcRef = utcNanos }
        bootNs[head] = bootNanos
        utcNs[head] = utcNanos
        head = (head + 1) % windowSize
        if (filled < windowSize) filled++
        if (filled >= minSamplesForFit) refit()
    }

    /** Map a BOOTTIME ns value to UTC ns. Returns null if no fit is available yet. */
    fun toUtcNanos(bootNanos: Long): Long? = params?.let { p ->
        p.offsetNanos + ((bootNanos - p.bootRefNanos) * (1.0 + p.driftPpm * 1e-6)).toLong()
    }

    fun snapshot(): MapParams? = lock.read { params }

    private fun refit() {
        val n = filled
        // Fit in reference-relative coordinates: x against bootRef, y against utcRef. Crucially these
        // are SEPARATE origins — UTC is epoch-scale (~1.7e18) and would lose precision (and be wrong)
        // if reduced against bootRef. offsetNanos below is reconstituted to full UTC at bootRef.
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        for (i in 0 until n) {
            xs[i] = (bootNs[i] - bootRef).toDouble()
            ys[i] = (utcNs[i] - utcRef).toDouble()
        }

        // Theil–Sen slope: median of pairwise slopes.
        val slopes = ArrayList<Double>(n * (n - 1) / 2)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dx = xs[j] - xs[i]
                if (abs(dx) < 1.0) continue // identical boot times → undefined slope, skip
                slopes.add((ys[j] - ys[i]) / dx)
            }
        }
        if (slopes.isEmpty()) return
        val slope = median(slopes)

        // Median intercept: median of (y_i - slope·x_i).
        val intercepts = DoubleArray(n) { ys[it] - slope * xs[it] }
        val intercept = median(intercepts.toMutableList())

        // Residual MAD for a confidence proxy.
        val residuals = DoubleArray(n) { abs(ys[it] - (intercept + slope * xs[it])) }
        residuals.sort()
        val residMad = residuals[n / 2]

        params = MapParams(
            bootRefNanos = bootRef,
            // Reconstitute full UTC at boot=bootRef (intercept is in the utcRef-relative frame).
            offsetNanos = utcRef + intercept.toLong(),
            driftPpm = (slope - 1.0) * 1e6,
            residualMadNanos = residMad.toLong(),
            sampleCount = n,
        )
    }

    private fun median(values: MutableList<Double>): Double {
        values.sort()
        val n = values.size
        val mid = n / 2
        return if (n % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) / 2.0
    }

    /**
     * Immutable snapshot of the boot→UTC map. Embedded periodically into `/diag/sync` so offline
     * tools can reconstruct the mapping for each device file.
     */
    data class MapParams(
        val bootRefNanos: Long,
        val offsetNanos: Long,
        val driftPpm: Double,
        val residualMadNanos: Long,
        val sampleCount: Int,
    )
}
