package com.chronyx.core.clock

/**
 * The Android clock domains CHRONYX must reconcile. Every timestamp in the system is tagged with
 * exactly one of these; a silent cross-domain comparison is a P0 correctness bug.
 *
 * The master axis for the entire capture session is [BOOTTIME]. All channels normalize to it.
 */
enum class ClockDomain {
    /**
     * `SystemClock.elapsedRealtimeNanos()`. Monotonic, counts time since boot **including** deep
     * sleep. This is CHRONYX's master axis: camera `SENSOR_TIMESTAMP` with a REALTIME source, IMU
     * (nominally), GNSS `getElapsedRealtimeNanos()`, and audio `TIMEBASE_BOOTTIME` all live here.
     */
    BOOTTIME,

    /**
     * `System.nanoTime()`. Monotonic but with an arbitrary, undocumented zero. The camera
     * `SENSOR_INFO_TIMESTAMP_SOURCE == UNKNOWN` clock lives near this domain and must be mapped to
     * [BOOTTIME] via [ClockOffsetEstimator] before use.
     */
    MONOTONIC,

    /**
     * `SystemClock.uptimeMillis()` / `uptimeNanos`. Monotonic but **pauses during deep sleep**.
     * Some devices erroneously report IMU `SensorEvent.timestamp` on this base; that is detected at
     * startup and corrected.
     */
    UPTIME,

    /**
     * `System.currentTimeMillis()`. Wall-clock UTC, **not monotonic** (NTP steps, user changes).
     * Used only for human-readable file naming and for GNSS-UTC cross-device discipline — never for
     * intra-session alignment.
     */
    WALL,
}
