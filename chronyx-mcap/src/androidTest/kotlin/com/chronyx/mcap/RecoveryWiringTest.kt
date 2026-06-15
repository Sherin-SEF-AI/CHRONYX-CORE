package com.chronyx.mcap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronyx.core.api.SessionMetadata
import com.chronyx.mcap.internal.InternalMcapWriter
import com.chronyx.mcap.internal.McapCompression
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof that [McapSink.open] auto-recovers an orphaned MCAP left by a previous crash (the
 * wiring `McapSink.open → recoverPendingFiles → McapRecovery`). Simulates the crash by writing a chunk
 * to disk without finalizing, then opening a fresh sink in the same directory.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryWiringTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val MAGIC = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(),
        'P'.code.toByte(), 0x30, 0x0D, 0x0A)

    @Test
    fun sinkOpenRecoversOrphanedFile() {
        val dir = File(context.cacheDir, "recovery_${System.currentTimeMillis()}").apply { mkdirs() }
        val orphan = File(dir, "orphan.mcap")

        // Simulate a crashed run: write a chunk, never close (no footer/summary/magic).
        val w = InternalMcapWriter(orphan, McapCompression.NONE)
        w.start("chronyx", "test")
        val s = w.addSchema("foo.Bar", byteArrayOf(1, 2, 3))
        val ch = w.addChannel("/t", s)
        repeat(5) { val p = "m$it".toByteArray(); w.writeMessage(ch, it.toLong(), 1_000L + it, 1_000L + it, p, p.size) }
        w.flush()
        assertThat(McapRecovery.needsRecovery(orphan)).isTrue()

        // A fresh sink opening in the same dir must recover the orphan.
        val sink = McapSink(fileFactory = { File(dir, "new_$it.mcap") })
        sink.open(SessionMetadata("sid", "test", "BOOTTIME", 0, 0, 0, 0, 0, "NONE", 0, 0))
        sink.close() // drains the writer thread (recovery runs there)

        val b = orphan.readBytes()
        assertThat(b.copyOfRange(b.size - 8, b.size)).isEqualTo(MAGIC)
        assertThat(McapRecovery.needsRecovery(orphan)).isFalse()
    }
}
