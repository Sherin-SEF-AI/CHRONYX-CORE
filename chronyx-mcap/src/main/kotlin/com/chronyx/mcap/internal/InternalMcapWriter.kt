package com.chronyx.mcap.internal

import com.chronyx.mcap.McapWriter
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * A complete, MCAP-spec-conformant streaming writer (chunked + compressed) with full summary
 * indexing, so the produced file opens directly in Foxglove Studio with no manual offset.
 *
 * Layout produced:
 * ```
 * <magic> Header [Schema…] [Channel…] [ Chunk MessageIndex… ]… DataEnd
 *         Summary( Schema… Channel… ChunkIndex… Statistics ) SummaryOffset… Footer <magic>
 * ```
 * All integers little-endian; strings are uint32-length-prefixed UTF-8; CRCs are CRC-32 (IEEE),
 * matching the MCAP specification.
 *
 * Not thread-safe — owned by [com.chronyx.mcap.McapSink]'s single writer thread.
 */
class InternalMcapWriter(
    file: File,
    private val compression: McapCompression = McapCompression.ZSTD,
    private val chunkTargetBytes: Int = 4 * 1024 * 1024,
    private val chunkMaxDurationNanos: Long = 2_000_000_000L,
) : McapWriter {

    private companion object {
        val MAGIC = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(),
            'P'.code.toByte(), 0x30, 0x0D, 0x0A)

        const val OP_HEADER = 0x01
        const val OP_FOOTER = 0x02
        const val OP_SCHEMA = 0x03
        const val OP_CHANNEL = 0x04
        const val OP_MESSAGE = 0x05
        const val OP_CHUNK = 0x06
        const val OP_MESSAGE_INDEX = 0x07
        const val OP_CHUNK_INDEX = 0x08
        const val OP_STATISTICS = 0x0B
        const val OP_METADATA = 0x0C
        const val OP_METADATA_INDEX = 0x0D
        const val OP_DATA_END = 0x0F
        const val OP_SUMMARY_OFFSET = 0x0E
    }

    private val out: OutputStream = BufferedOutputStream(file.outputStream(), 1 shl 16)
    private var pos = 0L
    private val dataCrc = CRC32()
    private var inData = false
    private var started = false
    private var closed = false

    override val bytesWritten: Long get() = pos

    // Registered schemas/channels — kept to re-emit into the summary section.
    private class SchemaDef(val id: Int, val content: ByteArray)
    private class ChannelDef(val id: Int, val content: ByteArray)
    private val schemas = ArrayList<SchemaDef>()
    private val channels = ArrayList<ChannelDef>()
    private var nextSchemaId = 1
    private var nextChannelId = 0

    // Current open chunk.
    private val chunkRecords = LeBuffer(chunkTargetBytes)
    private val chunkIndexByChannel = LinkedHashMap<Int, ArrayList<Long>>() // channelId -> [logTime, offset, ...]
    private var chunkMinTime = Long.MAX_VALUE
    private var chunkMaxTime = Long.MIN_VALUE
    private var chunkMsgCount = 0

    // Summary accounting.
    private class ChunkIndexEntry(
        val startTime: Long, val endTime: Long, val chunkStartOffset: Long, val chunkLength: Long,
        val messageIndexOffsets: List<Pair<Int, Long>>, val messageIndexLength: Long,
        val compressionId: String, val compressedSize: Long, val uncompressedSize: Long,
    )
    private val chunkIndexes = ArrayList<ChunkIndexEntry>()
    // Metadata records (op 0x0C) written in the data section; indexed in the summary so `mcap info`
    // surfaces them. Each entry = (file offset, record length, name).
    private val metadataIndex = ArrayList<Triple<Long, Long, String>>()
    private val channelMessageCounts = LinkedHashMap<Int, Long>()
    private var totalMessages = 0L
    private var globalMinTime = Long.MAX_VALUE
    private var globalMaxTime = Long.MIN_VALUE

    // Scratch buffers reused per record to avoid per-message allocation churn.
    private val msgContent = LeBuffer(256)
    private val recHeader = ByteArray(9)

    override fun start(profile: String, library: String) {
        check(!started) { "start() called twice" }
        started = true
        raw(MAGIC)
        inData = true
        val c = LeBuffer(64)
        c.str(profile)
        c.str(library)
        writeRecord(OP_HEADER, c)
    }

    override fun addSchema(messageFullName: String, descriptorSet: ByteArray): Int {
        checkStarted()
        val id = nextSchemaId++
        val c = LeBuffer(descriptorSet.size + messageFullName.length + 32)
        c.u16(id)
        c.str(messageFullName)
        c.str("protobuf")
        c.lenBytes(descriptorSet, 0, descriptorSet.size)
        val bytes = c.toByteArray()
        writeRecord(OP_SCHEMA, bytes)
        schemas.add(SchemaDef(id, bytes))
        return id
    }

    override fun addChannel(topic: String, schemaId: Int, metadata: Map<String, String>): Int {
        checkStarted()
        val id = nextChannelId++
        val c = LeBuffer(64)
        c.u16(id)
        c.u16(schemaId)
        c.str(topic)
        c.str("protobuf")
        // metadata map: uint32 byte-length prefix then key/value strings.
        val meta = LeBuffer(32)
        for ((k, v) in metadata) { meta.str(k); meta.str(v) }
        c.u32(meta.size)
        c.bytesRaw(meta.array, 0, meta.size)
        val bytes = c.toByteArray()
        writeRecord(OP_CHANNEL, bytes)
        channels.add(ChannelDef(id, bytes))
        return id
    }

    override fun writeMessage(
        channelId: Int, sequence: Long, logTimeNanos: Long, publishTimeNanos: Long, data: ByteArray, length: Int,
    ) {
        checkStarted()
        // Offset of this Message record within the uncompressed chunk records.
        val offsetInChunk = chunkRecords.size.toLong()

        msgContent.clear()
        msgContent.u16(channelId)
        msgContent.u32(sequence)
        msgContent.u64(logTimeNanos)
        msgContent.u64(publishTimeNanos)
        msgContent.bytesRaw(data, 0, length)
        appendRecord(chunkRecords, OP_MESSAGE, msgContent)

        chunkIndexByChannel.getOrPut(channelId) { ArrayList() }.apply { add(logTimeNanos); add(offsetInChunk) }
        if (logTimeNanos < chunkMinTime) chunkMinTime = logTimeNanos
        if (logTimeNanos > chunkMaxTime) chunkMaxTime = logTimeNanos
        chunkMsgCount++

        channelMessageCounts[channelId] = (channelMessageCounts[channelId] ?: 0L) + 1
        totalMessages++
        if (logTimeNanos < globalMinTime) globalMinTime = logTimeNanos
        if (logTimeNanos > globalMaxTime) globalMaxTime = logTimeNanos

        val durationExceeded = chunkMaxTime - chunkMinTime >= chunkMaxDurationNanos
        if (chunkRecords.size >= chunkTargetBytes || durationExceeded) flushChunk()
    }

    override fun writeMetadata(name: String, metadata: Map<String, String>) {
        checkStarted()
        val c = LeBuffer(128)
        c.str(name)
        val m = LeBuffer(64)
        for ((k, v) in metadata) { m.str(k); m.str(v) }
        c.u32(m.size)
        c.bytesRaw(m.array, 0, m.size)
        val offset = pos
        writeRecord(OP_METADATA, c)
        metadataIndex.add(Triple(offset, pos - offset, name))
    }

    override fun flush() {
        if (chunkMsgCount > 0) flushChunk()
        out.flush()
    }

    private fun flushChunk() {
        if (chunkMsgCount == 0) return

        val uncompressedSize = chunkRecords.size
        val crc = CRC32().apply { update(chunkRecords.array, 0, uncompressedSize) }.value
        // The codec actually applied may differ from the requested one (native lib absent → NONE);
        // label the chunk with what was really used so the file stays conformant.
        val result = Compressor.compress(compression, chunkRecords.array, uncompressedSize)
        val usedCompression = result.compression
        val compressed = result.bytes

        val content = LeBuffer(compressed.size + 64)
        content.u64(chunkMinTime)
        content.u64(chunkMaxTime)
        content.u64(uncompressedSize.toLong())
        content.u32(crc)
        content.str(usedCompression.id)
        content.u64(compressed.size.toLong())
        content.bytesRaw(compressed)

        val chunkStartOffset = pos
        writeRecord(OP_CHUNK, content)
        val chunkLength = pos - chunkStartOffset

        // MessageIndex records immediately follow the chunk; record their file offsets.
        val indexOffsets = ArrayList<Pair<Int, Long>>(chunkIndexByChannel.size)
        val messageIndexStart = pos
        for ((channelId, entries) in chunkIndexByChannel) {
            val mi = LeBuffer(entries.size * 16 + 16)
            mi.u16(channelId)
            mi.u32(entries.size * 8) // byte length of the (logTime, offset) array; each long is 8 bytes
            var i = 0
            while (i < entries.size) {
                mi.u64(entries[i])     // log_time
                mi.u64(entries[i + 1]) // offset
                i += 2
            }
            val miOffset = pos
            writeRecord(OP_MESSAGE_INDEX, mi)
            indexOffsets.add(channelId to miOffset)
        }
        val messageIndexLength = pos - messageIndexStart

        chunkIndexes.add(
            ChunkIndexEntry(
                startTime = chunkMinTime, endTime = chunkMaxTime,
                chunkStartOffset = chunkStartOffset, chunkLength = chunkLength,
                messageIndexOffsets = indexOffsets, messageIndexLength = messageIndexLength,
                compressionId = usedCompression.id,
                compressedSize = compressed.size.toLong(), uncompressedSize = uncompressedSize.toLong(),
            )
        )

        // Reset chunk state.
        chunkRecords.clear()
        chunkIndexByChannel.clear()
        chunkMinTime = Long.MAX_VALUE
        chunkMaxTime = Long.MIN_VALUE
        chunkMsgCount = 0
    }

    override fun close() {
        if (closed) return
        check(started) { "close() before start()" }
        closed = true
        flushChunk()

        // DataEnd: data_section_crc covers everything after the leading magic up to (not incl.) DataEnd.
        val dataSectionCrc = dataCrc.value
        inData = false
        val de = LeBuffer(8)
        de.u32(dataSectionCrc)
        writeRecord(OP_DATA_END, de)

        // Build the summary section + summary offsets + footer in one buffer with known absolute
        // offsets (summaryStart maps to tail index 0).
        val summaryStart = pos
        val tail = LeBuffer(4096)

        val schemasStart = summaryStart + tail.size
        for (s in schemas) appendRecord(tail, OP_SCHEMA, s.content)
        val schemasLen = (summaryStart + tail.size) - schemasStart

        val channelsStart = summaryStart + tail.size
        for (c in channels) appendRecord(tail, OP_CHANNEL, c.content)
        val channelsLen = (summaryStart + tail.size) - channelsStart

        val chunkIndexStart = summaryStart + tail.size
        for (ci in chunkIndexes) appendChunkIndex(tail, ci)
        val chunkIndexLen = (summaryStart + tail.size) - chunkIndexStart

        val metadataIndexStart = summaryStart + tail.size
        for ((offset, length, name) in metadataIndex) appendMetadataIndex(tail, offset, length, name)
        val metadataIndexLen = (summaryStart + tail.size) - metadataIndexStart

        val statsStart = summaryStart + tail.size
        appendStatistics(tail)
        val statsLen = (summaryStart + tail.size) - statsStart

        // SummaryOffset records for each non-empty group.
        val summaryOffsetStart = summaryStart + tail.size
        if (schemas.isNotEmpty()) appendSummaryOffset(tail, OP_SCHEMA, schemasStart, schemasLen)
        if (channels.isNotEmpty()) appendSummaryOffset(tail, OP_CHANNEL, channelsStart, channelsLen)
        if (chunkIndexes.isNotEmpty()) appendSummaryOffset(tail, OP_CHUNK_INDEX, chunkIndexStart, chunkIndexLen)
        if (metadataIndex.isNotEmpty()) appendSummaryOffset(tail, OP_METADATA_INDEX, metadataIndexStart, metadataIndexLen)
        appendSummaryOffset(tail, OP_STATISTICS, statsStart, statsLen)

        // Footer (op + len + summary_start + summary_offset_start), then summary_crc over all of tail.
        tail.u8(OP_FOOTER)
        tail.u64(20L) // footer content length: 8 + 8 + 4
        tail.u64(summaryStart)
        tail.u64(summaryOffsetStart)
        val summaryCrc = CRC32().apply { update(tail.array, 0, tail.size) }.value
        tail.u32(summaryCrc)

        raw(tail.array, 0, tail.size)
        raw(MAGIC)
        out.flush()
        out.close()
    }

    // ---- summary helpers ----

    private fun appendChunkIndex(buf: LeBuffer, ci: ChunkIndexEntry) {
        val c = LeBuffer(64 + ci.messageIndexOffsets.size * 10)
        c.u64(ci.startTime)
        c.u64(ci.endTime)
        c.u64(ci.chunkStartOffset)
        c.u64(ci.chunkLength)
        val mio = LeBuffer(ci.messageIndexOffsets.size * 10)
        for ((channelId, offset) in ci.messageIndexOffsets) { mio.u16(channelId); mio.u64(offset) }
        c.u32(mio.size)
        c.bytesRaw(mio.array, 0, mio.size)
        c.u64(ci.messageIndexLength)
        c.str(ci.compressionId)
        c.u64(ci.compressedSize)
        c.u64(ci.uncompressedSize)
        appendRecord(buf, OP_CHUNK_INDEX, c)
    }

    private fun appendStatistics(buf: LeBuffer) {
        val c = LeBuffer(64 + channelMessageCounts.size * 10)
        c.u64(totalMessages)
        c.u16(schemas.size)
        c.u32(channels.size)
        c.u32(0)                      // attachment_count
        c.u32(metadataIndex.size)     // metadata_count
        c.u32(chunkIndexes.size)      // chunk_count
        c.u64(if (totalMessages == 0L) 0 else globalMinTime)
        c.u64(if (totalMessages == 0L) 0 else globalMaxTime)
        val cmc = LeBuffer(channelMessageCounts.size * 10)
        for ((channelId, count) in channelMessageCounts) { cmc.u16(channelId); cmc.u64(count) }
        c.u32(cmc.size)
        c.bytesRaw(cmc.array, 0, cmc.size)
        appendRecord(buf, OP_STATISTICS, c)
    }

    private fun appendMetadataIndex(buf: LeBuffer, offset: Long, length: Long, name: String) {
        val c = LeBuffer(32 + name.length)
        c.u64(offset)
        c.u64(length)
        c.str(name)
        appendRecord(buf, OP_METADATA_INDEX, c)
    }

    private fun appendSummaryOffset(buf: LeBuffer, groupOp: Int, groupStart: Long, groupLength: Long) {
        val c = LeBuffer(20)
        c.u8(groupOp)
        c.u64(groupStart)
        c.u64(groupLength)
        appendRecord(buf, OP_SUMMARY_OFFSET, c)
    }

    // ---- low-level record framing ----

    private fun writeRecord(op: Int, content: LeBuffer) = writeRecordBytes(op, content.array, content.size)

    private fun writeRecord(op: Int, content: ByteArray) = writeRecordBytes(op, content, content.size)

    private fun writeRecordBytes(op: Int, content: ByteArray, length: Int) {
        recHeader[0] = op.toByte()
        var len = length.toLong()
        for (i in 0 until 8) { recHeader[1 + i] = (len and 0xFF).toByte(); len = len ushr 8 }
        raw(recHeader, 0, 9)
        raw(content, 0, length)
    }

    private fun appendRecord(buf: LeBuffer, op: Int, content: LeBuffer) =
        appendRecordBytes(buf, op, content.array, content.size)

    private fun appendRecord(buf: LeBuffer, op: Int, content: ByteArray) =
        appendRecordBytes(buf, op, content, content.size)

    private fun appendRecordBytes(buf: LeBuffer, op: Int, content: ByteArray, length: Int) {
        buf.u8(op)
        buf.u64(length.toLong())
        buf.bytesRaw(content, 0, length)
    }

    private fun raw(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        if (inData) dataCrc.update(b, off, len)
        pos += len
    }

    private fun raw(b: ByteArray) = raw(b, 0, b.size)

    private fun checkStarted() = check(started && !closed) { "writer not started or already closed" }
}
