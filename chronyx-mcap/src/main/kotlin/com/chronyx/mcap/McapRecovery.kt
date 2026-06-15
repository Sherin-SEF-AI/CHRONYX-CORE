package com.chronyx.mcap

import com.chronyx.mcap.internal.LeBuffer
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Repairs a crash-truncated MCAP that was never finalized (no summary/footer/trailing magic) — the
 * outcome when a 30-minute unattended run is hard-killed before [McapSink.close]. The on-disk data
 * section (Header, Schemas, Channels, Chunks, MessageIndexes) is intact and self-sufficient; this
 * walks it up to the last complete record, then appends a valid DataEnd + summary (Schemas, Channels,
 * ChunkIndexes, Statistics) + SummaryOffsets + Footer + trailing magic, producing a file that opens in
 * Foxglove.
 *
 * Self-contained on purpose (does not reach into the verified [com.chronyx.mcap.internal.InternalMcapWriter]):
 * it re-frames records with the same little-endian [LeBuffer] encoding and the stable MCAP layout.
 */
object McapRecovery {

    private val MAGIC = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(),
        'P'.code.toByte(), 0x30, 0x0D, 0x0A)

    private const val OP_HEADER = 0x01
    private const val OP_FOOTER = 0x02
    private const val OP_SCHEMA = 0x03
    private const val OP_CHANNEL = 0x04
    private const val OP_CHUNK = 0x06
    private const val OP_MESSAGE_INDEX = 0x07
    private const val OP_CHUNK_INDEX = 0x08
    private const val OP_STATISTICS = 0x0B
    private const val OP_METADATA = 0x0C
    private const val OP_METADATA_INDEX = 0x0D
    private const val OP_DATA_END = 0x0F
    private const val OP_SUMMARY_OFFSET = 0x0E

    /** True if [file] looks like an MCAP that was never finalized (missing trailing magic). */
    fun needsRecovery(file: File): Boolean {
        if (!file.isFile || file.length() < 16) return false
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(8); raf.seek(0); raf.readFully(head)
            if (!head.contentEquals(MAGIC)) return false
            val tail = ByteArray(8); raf.seek(file.length() - 8); raf.readFully(tail)
            return !tail.contentEquals(MAGIC)
        }
    }

    /**
     * Finalizes [file] in place. Returns true if it recovered (or was already valid), false if the file
     * is not a recognizable MCAP. Idempotent.
     */
    fun recover(file: File): Boolean {
        if (!file.isFile || file.length() < 16) return false
        val b = file.readBytes()
        if (b.size >= 8 && b.copyOfRange(b.size - 8, b.size).contentEquals(MAGIC)) return true // already final
        if (!b.copyOfRange(0, 8).contentEquals(MAGIC)) return false

        val schemas = ArrayList<ByteArray>()   // raw Schema record contents
        val channels = ArrayList<ByteArray>()  // raw Channel record contents
        val chunkIndexes = ArrayList<ChunkIndexData>()
        val metadataEntries = ArrayList<Triple<Long, Long, String>>() // offset, length, name
        val channelMessageCounts = LinkedHashMap<Int, Long>()
        var totalMessages = 0L
        var minTime = Long.MAX_VALUE
        var maxTime = Long.MIN_VALUE

        var p = 8
        var lastCompleteEnd = 8
        var pendingChunk: PendingChunk? = null

        fun commitPendingChunk(indexOffsets: List<Pair<Int, Long>>, indexLength: Long) {
            val pc = pendingChunk ?: return
            chunkIndexes.add(
                ChunkIndexData(pc.startTime, pc.endTime, pc.chunkStart, pc.chunkLength,
                    indexOffsets, indexLength, pc.compressionId, pc.compressedSize, pc.uncompressedSize),
            )
            pendingChunk = null
        }

        var curIndexOffsets = ArrayList<Pair<Int, Long>>()
        var curIndexStart = -1L
        var curIndexEnd = -1L

        while (p + 9 <= b.size) {
            val op = b[p].toInt() and 0xFF
            val len = u64(b, p + 1)
            val contentStart = p + 9
            val recordEnd = contentStart + len
            if (len < 0 || recordEnd > b.size) break // truncated record — stop, drop it

            when (op) {
                OP_HEADER -> {}
                OP_SCHEMA -> { schemas.add(b.copyOfRange(contentStart, recordEnd.toInt())) }
                OP_CHANNEL -> { channels.add(b.copyOfRange(contentStart, recordEnd.toInt())) }
                OP_METADATA -> {
                    val nameLen = u32(b, contentStart).toInt()
                    val name = String(b, contentStart + 4, nameLen)
                    metadataEntries.add(Triple(p.toLong(), recordEnd - p, name))
                }
                OP_CHUNK -> {
                    // Flush any prior chunk's collected message-index offsets first.
                    if (pendingChunk != null) {
                        val len2 = if (curIndexStart >= 0) curIndexEnd - curIndexStart else 0L
                        commitPendingChunk(curIndexOffsets, len2)
                        curIndexOffsets = ArrayList(); curIndexStart = -1; curIndexEnd = -1
                    }
                    pendingChunk = parseChunkHeader(b, contentStart, p.toLong(), recordEnd - p)
                    pendingChunk?.let {
                        if (it.startTime < minTime) minTime = it.startTime
                        if (it.endTime > maxTime) maxTime = it.endTime
                    }
                }
                OP_MESSAGE_INDEX -> {
                    val cid = u16(b, contentStart)
                    val arrLen = u32(b, contentStart + 2).toInt()
                    val count = (arrLen / 16).toLong()
                    channelMessageCounts[cid] = (channelMessageCounts[cid] ?: 0L) + count
                    totalMessages += count
                    curIndexOffsets.add(cid to p.toLong())
                    if (curIndexStart < 0) curIndexStart = p.toLong()
                    curIndexEnd = recordEnd
                }
                OP_DATA_END, OP_FOOTER -> { lastCompleteEnd = p; break }
            }
            p = recordEnd.toInt()
            lastCompleteEnd = p
        }
        // Commit the final pending chunk.
        if (pendingChunk != null) {
            val len2 = if (curIndexStart >= 0) curIndexEnd - curIndexStart else 0L
            commitPendingChunk(curIndexOffsets, len2)
        }
        if (chunkIndexes.isEmpty() && schemas.isEmpty()) {
            Timber.w("McapRecovery: nothing parseable in ${file.name}")
            return false
        }
        if (totalMessages == 0L) { minTime = 0; maxTime = 0 }

        val truncation = lastCompleteEnd
        val dataCrc = CRC32().apply { update(b, 8, truncation - 8) }.value

        val tail = buildSummary(
            summaryStart = truncation.toLong(), dataSectionCrc = dataCrc,
            schemas = schemas, channels = channels, chunkIndexes = chunkIndexes,
            metadataEntries = metadataEntries,
            totalMessages = totalMessages, channelMessageCounts = channelMessageCounts,
            minTime = minTime, maxTime = maxTime,
        )

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(truncation.toLong())
            raf.seek(truncation.toLong())
            raf.write(tail)
        }
        Timber.i("McapRecovery: finalized ${file.name} (${chunkIndexes.size} chunks, $totalMessages msgs)")
        return true
    }

    private class PendingChunk(
        val startTime: Long, val endTime: Long, val chunkStart: Long, val chunkLength: Long,
        val compressionId: String, val compressedSize: Long, val uncompressedSize: Long,
    )

    private class ChunkIndexData(
        val startTime: Long, val endTime: Long, val chunkStart: Long, val chunkLength: Long,
        val indexOffsets: List<Pair<Int, Long>>, val indexLength: Long,
        val compressionId: String, val compressedSize: Long, val uncompressedSize: Long,
    )

    private fun parseChunkHeader(b: ByteArray, c: Int, chunkStart: Long, chunkLength: Long): PendingChunk {
        val start = u64(b, c)
        val end = u64(b, c + 8)
        val uncompressed = u64(b, c + 16)
        // skip crc(4) at c+24
        val compLen = u32(b, c + 28).toInt()
        val comp = String(b, c + 32, compLen)
        val recordsLen = u64(b, c + 32 + compLen)
        return PendingChunk(start, end, chunkStart, chunkLength, comp, recordsLen, uncompressed)
    }

    private fun buildSummary(
        summaryStart: Long, dataSectionCrc: Long,
        schemas: List<ByteArray>, channels: List<ByteArray>, chunkIndexes: List<ChunkIndexData>,
        metadataEntries: List<Triple<Long, Long, String>>,
        totalMessages: Long, channelMessageCounts: Map<Int, Long>, minTime: Long, maxTime: Long,
    ): ByteArray {
        val out = LeBuffer(4096)

        // DataEnd is part of the data section's end, before the summary. Its own bytes are not in the
        // data_section_crc (computed up to truncation, i.e. before DataEnd).
        appendRecord(out, OP_DATA_END, LeBuffer(8).apply { u32(dataSectionCrc) }.toByteArray())

        // Summary section begins AFTER DataEnd; recompute its absolute start.
        val dataEndLen = out.size
        val sumStart = summaryStart + dataEndLen
        val sum = LeBuffer(4096)

        val schemasStart = sumStart + sum.size
        for (s in schemas) appendRecord(sum, OP_SCHEMA, s)
        val schemasLen = (sumStart + sum.size) - schemasStart

        val channelsStart = sumStart + sum.size
        for (c in channels) appendRecord(sum, OP_CHANNEL, c)
        val channelsLen = (sumStart + sum.size) - channelsStart

        val chunkIdxStart = sumStart + sum.size
        for (ci in chunkIndexes) appendChunkIndex(sum, ci)
        val chunkIdxLen = (sumStart + sum.size) - chunkIdxStart

        val metaIdxStart = sumStart + sum.size
        for ((offset, length, name) in metadataEntries) {
            val c = LeBuffer(32 + name.length); c.u64(offset); c.u64(length); c.str(name)
            appendRecord(sum, OP_METADATA_INDEX, c.toByteArray())
        }
        val metaIdxLen = (sumStart + sum.size) - metaIdxStart

        val statsStart = sumStart + sum.size
        appendStatistics(sum, totalMessages, schemas.size, channels.size, chunkIndexes.size, minTime, maxTime, channelMessageCounts, metadataEntries.size)
        val statsLen = (sumStart + sum.size) - statsStart

        val summaryOffsetStart = sumStart + sum.size
        if (schemas.isNotEmpty()) appendSummaryOffset(sum, OP_SCHEMA, schemasStart, schemasLen)
        if (channels.isNotEmpty()) appendSummaryOffset(sum, OP_CHANNEL, channelsStart, channelsLen)
        if (chunkIndexes.isNotEmpty()) appendSummaryOffset(sum, OP_CHUNK_INDEX, chunkIdxStart, chunkIdxLen)
        if (metadataEntries.isNotEmpty()) appendSummaryOffset(sum, OP_METADATA_INDEX, metaIdxStart, metaIdxLen)
        appendSummaryOffset(sum, OP_STATISTICS, statsStart, statsLen)

        sum.u8(OP_FOOTER)
        sum.u64(20L)
        sum.u64(sumStart)
        sum.u64(summaryOffsetStart)
        val crc = CRC32().apply { update(sum.array, 0, sum.size) }.value
        sum.u32(crc)

        out.bytesRaw(sum.array, 0, sum.size)
        out.bytesRaw(MAGIC)
        return out.toByteArray()
    }

    private fun appendChunkIndex(buf: LeBuffer, ci: ChunkIndexData) {
        val c = LeBuffer(64 + ci.indexOffsets.size * 10)
        c.u64(ci.startTime); c.u64(ci.endTime); c.u64(ci.chunkStart); c.u64(ci.chunkLength)
        val mio = LeBuffer(ci.indexOffsets.size * 10)
        for ((cid, off) in ci.indexOffsets) { mio.u16(cid); mio.u64(off) }
        c.u32(mio.size); c.bytesRaw(mio.array, 0, mio.size)
        c.u64(ci.indexLength); c.str(ci.compressionId); c.u64(ci.compressedSize); c.u64(ci.uncompressedSize)
        appendRecord(buf, OP_CHUNK_INDEX, c.toByteArray())
    }

    private fun appendStatistics(
        buf: LeBuffer, totalMessages: Long, schemaCount: Int, channelCount: Int, chunkCount: Int,
        minTime: Long, maxTime: Long, cmc: Map<Int, Long>, metadataCount: Int,
    ) {
        val c = LeBuffer(64 + cmc.size * 10)
        c.u64(totalMessages); c.u16(schemaCount); c.u32(channelCount); c.u32(0); c.u32(metadataCount); c.u32(chunkCount)
        c.u64(minTime); c.u64(maxTime)
        val m = LeBuffer(cmc.size * 10)
        for ((cid, n) in cmc) { m.u16(cid); m.u64(n) }
        c.u32(m.size); c.bytesRaw(m.array, 0, m.size)
        appendRecord(buf, OP_STATISTICS, c.toByteArray())
    }

    private fun appendSummaryOffset(buf: LeBuffer, groupOp: Int, start: Long, length: Long) {
        val c = LeBuffer(20); c.u8(groupOp); c.u64(start); c.u64(length)
        appendRecord(buf, OP_SUMMARY_OFFSET, c.toByteArray())
    }

    private fun appendRecord(buf: LeBuffer, op: Int, content: ByteArray) {
        buf.u8(op); buf.u64(content.size.toLong()); buf.bytesRaw(content)
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long {
        var v = 0L; for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i)); return v
    }
    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L; for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i)); return v
    }
}
