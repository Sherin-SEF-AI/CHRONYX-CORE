package com.chronyx.mcap

/**
 * The format-writer seam. [McapSink] depends only on this interface, so the backend can be the
 * internal spec-conformant writer (default, [com.chronyx.mcap.internal.InternalMcapWriter]) or an
 * adapter over the official Foxglove MCAP Java writer, without the sink changing.
 *
 * Threading: all methods are invoked from the sink's single writer thread; implementations need not
 * be internally synchronized.
 */
interface McapWriter : AutoCloseable {

    /** Writes the file magic + Header. Must be called exactly once before any other method. */
    fun start(profile: String, library: String)

    /**
     * Registers a protobuf schema and returns its schema id. `descriptorSet` is a serialized
     * `FileDescriptorSet` containing [messageFullName] and all its dependencies.
     */
    fun addSchema(messageFullName: String, descriptorSet: ByteArray): Int

    /** Registers a channel on [schemaId] and returns its channel id. */
    fun addChannel(topic: String, schemaId: Int, metadata: Map<String, String> = emptyMap()): Int

    /**
     * Appends a message. [logTimeNanos] is the BOOTTIME axis value used by the Foxglove timeline.
     * Buffers into the current chunk; the implementation flushes chunks by size/time.
     */
    fun writeMessage(channelId: Int, sequence: Long, logTimeNanos: Long, publishTimeNanos: Long, data: ByteArray, length: Int)

    /**
     * Writes an MCAP Metadata record (name + string map) in the data section and indexes it in the
     * summary, so standard tooling (`mcap info`) surfaces it. Use for self-describing session/device
     * facts. Call before [close].
     */
    fun writeMetadata(name: String, metadata: Map<String, String>)

    /** Total bytes flushed to the underlying file so far. */
    val bytesWritten: Long

    /** Forces the current chunk to disk (durability cadence). Safe to call between messages. */
    fun flush()

    /** Finalizes: flushes the open chunk, writes the summary section, footer, and trailing magic. */
    override fun close()
}
