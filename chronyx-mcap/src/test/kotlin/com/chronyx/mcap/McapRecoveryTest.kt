package com.chronyx.mcap

import com.chronyx.mcap.internal.InternalMcapWriter
import com.chronyx.mcap.internal.McapCompression
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Simulates a crash mid-capture: write a chunk to disk via flush() but never close() (so there is no
 * summary/footer/trailing magic), then recover and assert the file is finalized and every message is
 * recoverable. This is the unattended-run safety net.
 */
class McapRecoveryTest {

    private val MAGIC = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(),
        'P'.code.toByte(), 0x30, 0x0D, 0x0A)

    @Test
    fun recoversCrashTruncatedFile() {
        val file = File.createTempFile("chronyx-crash", ".mcap").also { it.deleteOnExit() }
        val writer = InternalMcapWriter(file, McapCompression.NONE)
        writer.start("chronyx", "test")
        val schema = writer.addSchema("foo.Bar", byteArrayOf(1, 2, 3))
        val ch = writer.addChannel("/topic", schema)
        val times = listOf(1_000_000_000L, 1_005_000_000L, 1_010_000_000L, 1_015_000_000L)
        times.forEachIndexed { i, t -> val p = "m$i".toByteArray(); writer.writeMessage(ch, i.toLong(), t, t, p, p.size) }
        writer.flush() // chunk + message index on disk
        // NO close() — simulate the process dying here.

        assertThat(McapRecovery.needsRecovery(file)).isTrue()
        assertThat(McapRecovery.recover(file)).isTrue()
        assertThat(McapRecovery.needsRecovery(file)).isFalse() // idempotent + finalized

        val b = file.readBytes()
        assertThat(b.copyOfRange(0, 8)).isEqualTo(MAGIC)
        assertThat(b.copyOfRange(b.size - 8, b.size)).isEqualTo(MAGIC)

        // Statistics span equals the message span, and the recovered message count is correct.
        val (count, span) = readStatistics(b)
        assertThat(count).isEqualTo(times.size.toLong())
        assertThat(span).isEqualTo(times.last() - times.first())
    }

    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L; for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i)); return v
    }

    private fun readStatistics(b: ByteArray): Pair<Long, Long> {
        var p = 8
        while (p < b.size - 8) {
            val op = b[p].toInt() and 0xFF
            val len = u64(b, p + 1)
            val c = p + 9
            if (op == 0x0B) {
                val count = u64(b, c)
                val start = u64(b, c + 26)
                val end = u64(b, c + 34)
                return count to (end - start)
            }
            p = c + len.toInt()
        }
        error("No Statistics record")
    }
}
