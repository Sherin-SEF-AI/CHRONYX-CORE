package com.chronyx.core.sync

import java.util.concurrent.atomic.AtomicLong

/**
 * A bounded, time-ordered ring buffer with a drop-oldest policy and a dropped-item counter. Producers
 * ([add]) are sensor-thread callers; the engine queries time ranges to assemble bundles. Thread-safe
 * via a single monitor; operations are O(n) over the (small, seconds-deep) window, which is fine for
 * IMU/GNSS/audio cadences.
 *
 * Items are assumed to arrive in roughly monotonic timestamp order; out-of-order arrivals are kept
 * but range queries simply scan, so correctness does not depend on perfect ordering.
 */
class TimeIndexedRing<T>(
    private val capacity: Int,
    private val timestampOf: (T) -> Long,
) {
    private val items = ArrayDeque<T>(capacity)
    private val lock = Any()
    private val dropped = AtomicLong(0)

    val droppedCount: Long get() = dropped.get()

    fun add(item: T) {
        synchronized(lock) {
            if (items.size >= capacity) {
                items.removeFirst()
                dropped.incrementAndGet()
            }
            items.addLast(item)
        }
    }

    /** All items with timestamp in [from, to], in insertion order. Allocates a snapshot list. */
    fun range(from: Long, to: Long): List<T> = synchronized(lock) {
        items.filter { val t = timestampOf(it); t in from..to }
    }

    /** The most recent item with timestamp <= at, or null. */
    fun latestAtOrBefore(at: Long): T? = synchronized(lock) {
        var best: T? = null
        var bestT = Long.MIN_VALUE
        for (it in items) {
            val t = timestampOf(it)
            if (t <= at && t >= bestT) { best = it; bestT = t }
        }
        best
    }

    /** The earliest item with timestamp >= at, or null. */
    fun earliestAtOrAfter(at: Long): T? = synchronized(lock) {
        var best: T? = null
        var bestT = Long.MAX_VALUE
        for (it in items) {
            val t = timestampOf(it)
            if (t >= at && t <= bestT) { best = it; bestT = t }
        }
        best
    }

    fun latest(): T? = synchronized(lock) { items.lastOrNull() }

    fun clear() = synchronized(lock) { items.clear() }
}
