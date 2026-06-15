package com.chronyx.core.clock

import android.os.SystemClock

/**
 * The single source of "now" for the capture session, on the BOOTTIME master axis.
 *
 * Every sample produced by every sensor subsystem is ultimately expressed against [nowBoot]. This
 * is intentionally a thin wrapper rather than scattered `SystemClock.elapsedRealtimeNanos()` calls
 * so that (a) the clock can be substituted in unit tests and (b) there is exactly one documented
 * place where the master axis is defined.
 */
interface MasterClock {
    /** Current BOOTTIME in nanoseconds (`SystemClock.elapsedRealtimeNanos()`). Monotonic. */
    fun nowBoot(): Long

    /** Current wall-clock UTC in milliseconds. Non-monotonic; only for file naming / UTC pairing. */
    fun nowWallMillis(): Long

    /** Current `System.nanoTime()` — the MONOTONIC domain anchor for camera UNKNOWN-source mapping. */
    fun nowMonotonic(): Long

    /** Reads BOOTTIME and MONOTONIC as close together as the platform allows, for offset pairing. */
    fun pairBootMonotonic(): BootMonotonicSample
}

/** A simultaneously-sampled (BOOTTIME, MONOTONIC) pair used to seed [ClockOffsetEstimator]. */
data class BootMonotonicSample(val bootNanos: Long, val monotonicNanos: Long)

class SystemMasterClock : MasterClock {
    override fun nowBoot(): Long = SystemClock.elapsedRealtimeNanos()
    override fun nowWallMillis(): Long = System.currentTimeMillis()
    override fun nowMonotonic(): Long = System.nanoTime()

    override fun pairBootMonotonic(): BootMonotonicSample {
        // Sandwich the foreign read between two master reads and take the midpoint to halve the
        // sampling skew. This is the standard cross-clock read technique.
        val b0 = SystemClock.elapsedRealtimeNanos()
        val m = System.nanoTime()
        val b1 = SystemClock.elapsedRealtimeNanos()
        return BootMonotonicSample(bootNanos = (b0 + b1) ushr 1, monotonicNanos = m)
    }
}
