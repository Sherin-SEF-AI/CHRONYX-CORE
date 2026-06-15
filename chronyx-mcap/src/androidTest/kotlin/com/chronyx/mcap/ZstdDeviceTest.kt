package com.chronyx.mcap

import com.chronyx.mcap.internal.Compressor
import com.chronyx.mcap.internal.McapCompression
import com.google.common.truth.Truth.assertThat
import io.airlift.compress.zstd.ZstdDecompressor
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Documents the on-device compression contract. Empirically, aircompressor's pure-Java zstd does NOT
 * run on this Android runtime (it relies on sun.misc.Unsafe in ways ART rejects), so requesting ZSTD
 * falls back to NONE — uncompressed but valid. This asserts the integrity invariant that MUST hold on
 * any device: whatever codec the writer actually applied, the payload round-trips exactly. (The
 * compression ratio itself is proven on the JVM by ZstdRoundTripTest, for desktop/JVM consumers.)
 */
@RunWith(AndroidJUnit4::class)
class ZstdDeviceTest {
    @Test
    fun zstdRequestRoundTripsWhicheverCodecRuns() {
        val src = ByteArray(128 * 1024) { ((it / 8) % 11).toByte() }
        val r = Compressor.compress(McapCompression.ZSTD, src, src.size)
        val out = when (r.compression) {
            McapCompression.ZSTD -> ByteArray(src.size).also { ZstdDecompressor().decompress(r.bytes, 0, r.bytes.size, it, 0, it.size) }
            McapCompression.NONE -> r.bytes // graceful fallback on Android — already raw
            else -> error("unexpected codec ${r.compression}")
        }
        assertThat(out.copyOf(src.size)).isEqualTo(src)
    }
}
