package com.chronyx.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeIndexedRingTest {

    private data class Item(val t: Long)

    @Test
    fun rangeReturnsWindowInclusive() {
        val ring = TimeIndexedRing<Item>(100) { it.t }
        (0..10).forEach { ring.add(Item(it * 10L)) }
        val r = ring.range(20, 50)
        assertThat(r.map { it.t }).containsExactly(20L, 30L, 40L, 50L).inOrder()
    }

    @Test
    fun dropsOldestBeyondCapacityAndCounts() {
        val ring = TimeIndexedRing<Item>(4) { it.t }
        (1..7).forEach { ring.add(Item(it.toLong())) }
        assertThat(ring.droppedCount).isEqualTo(3)
        assertThat(ring.range(0, 100).map { it.t }).containsExactly(4L, 5L, 6L, 7L).inOrder()
    }

    @Test
    fun bracketingQueries() {
        val ring = TimeIndexedRing<Item>(10) { it.t }
        listOf(10L, 20L, 40L).forEach { ring.add(Item(it)) }
        assertThat(ring.latestAtOrBefore(30)!!.t).isEqualTo(20L)
        assertThat(ring.earliestAtOrAfter(30)!!.t).isEqualTo(40L)
        assertThat(ring.latestAtOrBefore(5)).isNull()
        assertThat(ring.earliestAtOrAfter(50)).isNull()
    }
}
