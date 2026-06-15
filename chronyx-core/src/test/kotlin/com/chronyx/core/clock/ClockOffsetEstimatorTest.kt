package com.chronyx.core.clock

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The offset estimator is the most correctness-critical pure unit: a wrong camera/IMU offset silently
 * mis-times every frame. These feed synthetic foreign→boot pairs with a known constant offset plus
 * realistic one-sided latency jitter and gross outliers, and assert the robust estimate recovers the
 * true offset and reports high confidence.
 */
class ClockOffsetEstimatorTest {

    @Test
    fun recoversConstantOffsetUnderOneSidedJitter() {
        val trueOffset = 1_234_567_890L
        val rng = Random(42)
        val est = ClockOffsetEstimator(windowSize = 256)
        var foreign = 5_000_000_000L
        repeat(500) {
            foreign += 5_000_000L // ~200 Hz
            // boot = foreign + offset + latency; latency is one-sided (>= 0), heavy-tailed.
            val latency = (rng.nextDouble().let { it * it } * 800_000).toLong() // 0..0.8 ms, skewed low
            est.update(foreign, foreign + trueOffset + latency)
        }
        // Median-based estimate sits within the latency band of the true offset.
        assertThat(abs(est.offsetNanos - trueOffset)).isLessThan(500_000L)
        assertThat(est.confidence).isGreaterThan(0.5)
    }

    @Test
    fun rejectsGrossOutliers() {
        val trueOffset = -42_000_000L
        val est = ClockOffsetEstimator(windowSize = 128)
        var foreign = 0L
        repeat(200) { i ->
            foreign += 5_000_000L
            val sample = if (i % 17 == 0) {
                // ~6% gross outliers (e.g. a scheduling stall) of +50 ms.
                foreign + trueOffset + 50_000_000L
            } else {
                foreign + trueOffset + (i % 3) * 100_000L
            }
            est.update(foreign, sample)
        }
        assertThat(abs(est.offsetNanos - trueOffset)).isLessThan(1_000_000L)
    }

    @Test
    fun confidenceIsZeroBeforeWarmup() {
        val est = ClockOffsetEstimator(minSamplesForConfidence = 16)
        est.update(1000, 2000)
        est.update(2000, 3000)
        assertThat(est.confidence).isEqualTo(0.0)
    }

    @Test
    fun toBootAppliesOffset() {
        val est = ClockOffsetEstimator()
        repeat(50) { est.update(it * 1000L, it * 1000L + 777L) }
        assertThat(est.toBoot(10_000L)).isEqualTo(10_000L + est.offsetNanos)
    }
}
