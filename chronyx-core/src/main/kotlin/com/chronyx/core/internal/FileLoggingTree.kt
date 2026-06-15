package com.chronyx.core.internal

import android.os.SystemClock
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A [Timber.Tree] that persists the capture session's log to `chronyx_<session>.log` beside the
 * recording, so an unattended field run is debuggable after the fact (logcat is gone by then). Lines
 * are BOOTTIME-stamped to match the data axis. A single dedicated thread serializes writes and a 1 Hz
 * flush bounds loss to ~1 s on a crash. Best-effort: if the file can't be opened, this tree is inert.
 */
class FileLoggingTree(logFile: File) : Timber.Tree() {

    private val writer: BufferedWriter? = try {
        logFile.parentFile?.mkdirs()
        logFile.bufferedWriter()
    } catch (t: Throwable) {
        null
    }
    private val io: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "chronyx-log") }
    @Volatile private var closed = false

    init {
        if (writer != null) {
            io.scheduleAtFixedRate({ runCatching { writer?.flush() } }, 1, 1, TimeUnit.SECONDS)
        }
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val w = writer ?: return
        if (closed) return
        val boot = SystemClock.elapsedRealtimeNanos()
        runCatching {
            io.execute {
                runCatching {
                    w.append(boot.toString()).append(' ')
                        .append(priorityChar(priority)).append('/')
                        .append(tag ?: "chronyx").append(": ").append(message).append('\n')
                    if (t != null) w.append(t.stackTraceToString()).append('\n')
                }
            }
        }
    }

    /** Flush + close. Safe to call from a crash handler. */
    fun close() {
        if (closed) return
        closed = true
        runCatching {
            io.submit { runCatching { writer?.flush(); writer?.close() } }.get(2, TimeUnit.SECONDS)
        }
        io.shutdownNow()
    }

    private fun priorityChar(p: Int) = when (p) {
        2 -> 'V'; 3 -> 'D'; 4 -> 'I'; 5 -> 'W'; 6 -> 'E'; 7 -> 'A'; else -> '?'
    }
}
