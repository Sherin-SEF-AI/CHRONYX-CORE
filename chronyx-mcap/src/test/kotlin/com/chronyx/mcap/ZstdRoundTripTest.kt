package com.chronyx.mcap

import com.chronyx.mcap.internal.Compressor
import com.chronyx.mcap.internal.InternalMcapWriter
import com.chronyx.mcap.internal.McapCompression
import com.google.common.truth.Truth.assertThat
import io.airlift.compress.zstd.ZstdDecompressor
import org.junit.Test
import java.io.File

/**
 * Proves the pure-Java zstd path (aircompressor) produces valid, standard zstd frames and that an MCAP
 * written with ZSTD chunks round-trips. Pure-Java means this works on Android (no native `.so`); the
 * worst case if it ever failed at runtime is the graceful NONE fallback, never corruption.
 */
class ZstdRoundTripTest {

    @Test
    fun compressorProducesDecodableZstdFrame() {
        // Repetitive numeric data like IMU/audio — should compress well.
        val src = ByteArray(64 * 1024) { ((it / 8) % 7).toByte() }
        val result = Compressor.compress(McapCompression.ZSTD, src, src.size)
        assertThat(result.compression).isEqualTo(McapCompression.ZSTD)
        assertThat(result.bytes.size).isLessThan(src.size) // actually compressed

        val out = ByteArray(src.size)
        val n = ZstdDecompressor().decompress(result.bytes, 0, result.bytes.size, out, 0, out.size)
        assertThat(n).isEqualTo(src.size)
        assertThat(out).isEqualTo(src)
    }

    @Test
    fun mcapWithZstdChunksRoundTrips() {
        val file = File.createTempFile("chronyx-zstd", ".mcap").also { it.deleteOnExit() }
        val writer = InternalMcapWriter(file, McapCompression.ZSTD)
        writer.start("chronyx", "test")
        val s = writer.addSchema("foo.Bar", byteArrayOf(1, 2, 3))
        val ch = writer.addChannel("/t", s)
        repeat(200) { val p = "payload-$it-aaaaaaaa".toByteArray(); writer.writeMessage(ch, it.toLong(), 1000L + it, 1000L + it, p, p.size) }
        writer.close()

        val b = file.readBytes()
        val magic = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 0x30, 0x0D, 0x0A)
        assertThat(b.copyOfRange(0, 8)).isEqualTo(magic)
        assertThat(b.copyOfRange(b.size - 8, b.size)).isEqualTo(magic)
        // Find a Chunk, confirm it's labeled zstd and decompresses to valid inner Message records.
        var p = 8; var sawZstd = false; var msgs = 0
        val dec = ZstdDecompressor()
        while (p < b.size - 8) {
            val op = b[p].toInt() and 0xFF
            val len = u64(b, p + 1); val c = p + 9
            if (op == 0x06) {
                var o = c + 8 + 8; val uncompressed = u64(b, o).toInt(); o += 8 + 4
                val cl = u32(b, o).toInt(); val comp = String(b, o + 4, cl); o += 4 + cl
                val rlen = u64(b, o).toInt(); o += 8
                if (comp == "zstd") {
                    sawZstd = true
                    val out = ByteArray(uncompressed)
                    dec.decompress(b, o, rlen, out, 0, uncompressed)
                    var q = 0
                    while (q < out.size) { if ((out[q].toInt() and 0xFF) == 0x05) msgs++; q += 9 + u64(out, q + 1).toInt() }
                }
            }
            if (op == 0x0F) break
            p = c + len.toInt()
        }
        assertThat(sawZstd).isTrue()
        assertThat(msgs).isEqualTo(200)
    }

    private fun u64(b: ByteArray, o: Int): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i)); return v }
    private fun u32(b: ByteArray, o: Int): Long { var v = 0L; for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i)); return v }
}
