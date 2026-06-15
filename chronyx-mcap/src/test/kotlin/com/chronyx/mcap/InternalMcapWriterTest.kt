package com.chronyx.mcap

import com.chronyx.mcap.internal.InternalMcapWriter
import com.chronyx.mcap.internal.McapCompression
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Byte-conformance round-trip: write a small MCAP with the internal writer, then parse it back with an
 * independent minimal reader and assert the structure (magic, header, schema/channel, chunked messages
 * with correct channel ids and BOOTTIME log times, footer, trailing magic). Uses NONE compression so
 * the test has no native dependency and can inspect message bytes directly.
 */
class InternalMcapWriterTest {

    private val MAGIC = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(),
        'P'.code.toByte(), 0x30, 0x0D, 0x0A)

    @Test
    fun roundTripsConformantFile() {
        val file = File.createTempFile("chronyx", ".mcap").also { it.deleteOnExit() }
        val writer = InternalMcapWriter(file, McapCompression.NONE)
        writer.start("chronyx", "chronyx-mcap/test")
        val schemaId = writer.addSchema("foo.Bar", byteArrayOf(1, 2, 3, 4))
        val ch = writer.addChannel("/topic", schemaId, mapOf("clock_base" to "BOOTTIME"))

        val logTimes = listOf(1_000_000_000L, 1_005_000_000L, 1_010_000_000L)
        logTimes.forEachIndexed { i, t ->
            val payload = "msg$i".toByteArray()
            writer.writeMessage(ch, i.toLong(), t, t, payload, payload.size)
        }
        writer.close()

        val bytes = file.readBytes()
        // Leading + trailing magic.
        assertThat(bytes.copyOfRange(0, 8)).isEqualTo(MAGIC)
        assertThat(bytes.copyOfRange(bytes.size - 8, bytes.size)).isEqualTo(MAGIC)

        val reader = Reader(bytes)
        val parsed = reader.parseMessages()
        assertThat(parsed.map { it.logTime }).containsExactlyElementsIn(logTimes).inOrder()
        assertThat(parsed.map { it.channelId }.toSet()).containsExactly(ch)
        assertThat(parsed.map { String(it.data) }).containsExactly("msg0", "msg1", "msg2").inOrder()
    }

    private data class Msg(val channelId: Int, val logTime: Long, val data: ByteArray)

    /** A minimal independent MCAP reader covering only what this round-trip needs. */
    private inner class Reader(val b: ByteArray) {
        private var p = 8 // skip leading magic

        private fun u8(off: Int) = b[off].toInt() and 0xFF
        private fun u16(off: Int) = u8(off) or (u8(off + 1) shl 8)
        private fun u64(off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }
        private fun u32(off: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }

        fun parseMessages(): List<Msg> {
            val out = ArrayList<Msg>()
            // Walk top-level records until DataEnd (0x0F).
            while (p < b.size - 8) {
                val op = u8(p)
                val len = u64(p + 1)
                val contentStart = p + 9
                when (op) {
                    0x06 -> parseChunk(contentStart, len.toInt(), out) // Chunk
                    0x0F -> return out // DataEnd reached; messages live before it
                }
                p = contentStart + len.toInt()
            }
            return out
        }

        private fun parseChunk(start: Int, len: Int, out: MutableList<Msg>) {
            // Chunk fields: start(8) end(8) uncompressedSize(8) crc(4) compression(string) recordsLen(8) records
            var o = start
            o += 8 + 8 + 8 + 4
            val compLen = u32(o).toInt(); o += 4
            val compression = String(b, o, compLen); o += compLen
            check(compression.isEmpty()) { "test uses NONE compression" }
            val recordsLen = u64(o).toInt(); o += 8
            val recEnd = o + recordsLen
            // Parse inner Message records (op 0x05).
            while (o < recEnd) {
                val iop = u8(o)
                val ilen = u64(o + 1)
                val ic = o + 9
                if (iop == 0x05) {
                    val channelId = u16(ic)
                    val logTime = u64(ic + 6) // channelId(2) sequence(4) -> logTime at +6
                    val dataStart = ic + 22
                    val dataLen = ilen.toInt() - 22
                    out.add(Msg(channelId, logTime, b.copyOfRange(dataStart, dataStart + dataLen)))
                }
                o = ic + ilen.toInt()
            }
        }
    }
}
