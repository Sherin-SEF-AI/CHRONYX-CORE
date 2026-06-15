package com.chronyx.mcap.internal

/**
 * A growable little-endian byte buffer. MCAP is little-endian throughout; this centralizes the
 * encoding so the writer never hand-rolls byte order. Not thread-safe; each writer thread owns its
 * own buffers.
 */
class LeBuffer(initialCapacity: Int = 1024) {
    var array: ByteArray = ByteArray(initialCapacity)
        private set
    var size: Int = 0
        private set

    fun clear() { size = 0 }

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need <= array.size) return
        var cap = array.size
        while (cap < need) cap = cap shl 1
        array = array.copyOf(cap)
    }

    fun u8(v: Int) {
        ensure(1)
        array[size++] = (v and 0xFF).toByte()
    }

    fun u16(v: Int) {
        ensure(2)
        array[size++] = (v and 0xFF).toByte()
        array[size++] = ((v ushr 8) and 0xFF).toByte()
    }

    fun u32(v: Long) {
        ensure(4)
        var x = v
        repeat(4) { array[size++] = (x and 0xFF).toByte(); x = x ushr 8 }
    }

    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)

    fun u64(v: Long) {
        ensure(8)
        var x = v
        repeat(8) { array[size++] = (x and 0xFF).toByte(); x = x ushr 8 }
    }

    fun bytesRaw(src: ByteArray, off: Int, len: Int) {
        ensure(len)
        System.arraycopy(src, off, array, size, len)
        size += len
    }

    fun bytesRaw(src: ByteArray) = bytesRaw(src, 0, src.size)

    /** MCAP `String`: uint32 length prefix + UTF-8 bytes. */
    fun str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        u32(b.size)
        bytesRaw(b)
    }

    /** MCAP length-prefixed `bytes`: uint32 length + bytes. */
    fun lenBytes(src: ByteArray, off: Int, len: Int) {
        u32(len)
        bytesRaw(src, off, len)
    }

    /** Snapshot copy of the written content. */
    fun toByteArray(): ByteArray = array.copyOf(size)
}
