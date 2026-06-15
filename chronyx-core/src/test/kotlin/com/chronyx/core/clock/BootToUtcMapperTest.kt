package com.chronyx.core.clock.xdevice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class BootToUtcMapperTest {

    @Test
    fun fitsOffsetAndDriftRobustly() {
        // Ground truth: utc = offset + (1 + drift) * (boot - ref).
        val ref = 10_000_000_000L
        val offset = 1_700_000_000_000_000_000L // ~epoch ns scale
        val driftPpm = 25.0
        val rng = Random(7)
        val mapper = BootToUtcMapper(windowSize = 64)

        var boot = ref
        repeat(60) {
            boot += 1_000_000_000L // 1 Hz fixes
            val trueUtc = offset + ((boot - ref) * (1.0 + driftPpm * 1e-6)).toLong()
            // Cooked-fix UTC has ms quantization + occasional outlier.
            val noise = (rng.nextInt(-2_000_000, 2_000_000)).toLong()
            val outlier = if (it % 19 == 0) 40_000_000L else 0L
            mapper.addPair(boot, trueUtc + noise + outlier)
        }

        val p = mapper.current
        assertThat(p).isNotNull()
        // Drift precision is noise-limited (ms-quantized cooked-fix UTC over a 60 s window); the real
        // cross-device metric is prediction accuracy, asserted below. Keep the drift bound loose.
        assertThat(abs(p!!.driftPpm - driftPpm)).isLessThan(20.0)

        // Predicted UTC at a fresh boot instant is within a few ms of truth.
        val testBoot = boot + 500_000_000L
        val trueUtc = offset + ((testBoot - ref) * (1.0 + driftPpm * 1e-6)).toLong()
        val predicted = mapper.toUtcNanos(testBoot)!!
        assertThat(abs(predicted - trueUtc)).isLessThan(5_000_000L)
    }

    @Test
    fun returnsNullBeforeMinSamples() {
        val mapper = BootToUtcMapper(minSamplesForFit = 8)
        repeat(3) { mapper.addPair(it * 1_000_000_000L, it * 1_000_000_000L) }
        assertThat(mapper.toUtcNanos(0)).isNull()
    }
}
