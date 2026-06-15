package com.chronyx.core.clock

/**
 * A timestamp value paired with the [ClockDomain] it was measured in. This type exists so a
 * timestamp can never float untyped through the pipeline: any code that needs a BOOTTIME value must
 * either already hold a reading in [ClockDomain.BOOTTIME] or convert through an explicit estimator
 * (e.g. [ClockOffsetEstimator]).
 *
 * The capture hot paths deliberately operate on primitive `Long` nanoseconds for zero-allocation
 * throughput; [ClockReading] is used at module boundaries, in diagnostics, and in tests where the
 * domain must travel with the value.
 *
 * @param nanos timestamp in nanoseconds. Millisecond sources ([ClockDomain.WALL]) are widened to ns
 *   at the boundary so the unit is uniform across the system.
 */
data class ClockReading(
    val nanos: Long,
    val domain: ClockDomain,
) {
    /** True if this reading is already on the master axis and usable without conversion. */
    val isBoottime: Boolean get() = domain == ClockDomain.BOOTTIME

    /**
     * Asserts (in debug builds) that this reading is on [expected]; returns [nanos]. Use at the few
     * call sites that consume a raw long after a domain has been established, to make an accidental
     * domain swap fail loudly rather than silently.
     */
    fun requireDomain(expected: ClockDomain): Long {
        check(domain == expected) { "ClockReading domain mismatch: have $domain, need $expected" }
        return nanos
    }

    companion object {
        fun boot(nanos: Long) = ClockReading(nanos, ClockDomain.BOOTTIME)
        fun monotonic(nanos: Long) = ClockReading(nanos, ClockDomain.MONOTONIC)
        fun uptime(nanos: Long) = ClockReading(nanos, ClockDomain.UPTIME)
        fun wallMillis(millis: Long) = ClockReading(millis * 1_000_000L, ClockDomain.WALL)
    }
}
