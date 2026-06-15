package com.chronyx.mcap.internal

import io.airlift.compress.zstd.ZstdCompressor
import net.jpountz.lz4.LZ4FrameOutputStream
import timber.log.Timber
import java.io.ByteArrayOutputStream

/** MCAP chunk compression. The string value is what is written into the Chunk/ChunkIndex records. */
enum class McapCompression(val id: String) {
    NONE(""),
    ZSTD("zstd"),
    LZ4("lz4");
}

/** A compressed payload together with the codec that was ACTUALLY applied (after any fallback). */
class CompressedResult(val compression: McapCompression, val bytes: ByteArray)

/**
 * Compresses chunk payloads with graceful degradation.
 *
 * ZSTD uses **aircompressor**, a pure-Java zstd that emits standard zstd frames Foxglove decodes — and
 * crucially needs NO native `.so`, so it works on Android (unlike `zstd-jni`, whose jar ships only a
 * glibc `linux/aarch64` binary that bionic can't `dlopen`). It is the default. LZ4 uses `lz4-java`'s
 * frame format (native; degrades to NONE on Android). Any compress failure is caught once, the codec
 * disabled, and the chunk written uncompressed — the returned [CompressedResult.compression] is the
 * codec actually applied, so the file stays conformant.
 */
object Compressor {
    @Volatile private var zstdAvailable = true
    @Volatile private var lz4Available = true
    private val zstd by lazy { ZstdCompressor() }

    fun compress(kind: McapCompression, src: ByteArray, length: Int): CompressedResult = when (kind) {
        McapCompression.NONE -> CompressedResult(McapCompression.NONE, src.copyOf(length))

        McapCompression.LZ4 -> {
            if (lz4Available) {
                try {
                    val bos = ByteArrayOutputStream(length / 2 + 64)
                    LZ4FrameOutputStream(bos).use { it.write(src, 0, length) }
                    CompressedResult(McapCompression.LZ4, bos.toByteArray())
                } catch (t: Throwable) {
                    lz4Available = false
                    Timber.w(t, "LZ4 native unavailable; falling back to uncompressed chunks")
                    CompressedResult(McapCompression.NONE, src.copyOf(length))
                }
            } else {
                CompressedResult(McapCompression.NONE, src.copyOf(length))
            }
        }

        McapCompression.ZSTD -> {
            if (zstdAvailable) {
                try {
                    val max = zstd.maxCompressedLength(length)
                    val dst = ByteArray(max)
                    val n = zstd.compress(src, 0, length, dst, 0, max)
                    CompressedResult(McapCompression.ZSTD, dst.copyOf(n))
                } catch (t: Throwable) {
                    zstdAvailable = false
                    Timber.w(t, "zstd compression failed; falling back to uncompressed chunks")
                    CompressedResult(McapCompression.NONE, src.copyOf(length))
                }
            } else {
                CompressedResult(McapCompression.NONE, src.copyOf(length))
            }
        }
    }
}
