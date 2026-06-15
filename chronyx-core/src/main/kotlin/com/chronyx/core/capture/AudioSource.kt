package com.chronyx.core.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.chronyx.core.api.AudioConfig
import com.chronyx.core.api.AudioSource as AudioSourcePreset
import com.chronyx.core.model.AudioBuffer
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Microphone capture with per-sample BOOTTIME stamping.
 *
 * Uses `MediaRecorder.AudioSource.UNPROCESSED` (falling back to `VOICE_RECOGNITION`) so AGC / noise
 * suppression / echo cancellation don't mangle acoustic events. Each emitted PCM buffer is tagged
 * with the BOOTTIME of its first sample, computed from an `AudioTimestamp(TIMEBASE_BOOTTIME)` anchor:
 *
 *     t_sample = anchor.nanoTime + (sampleIndex − anchor.framePosition) · 1e9 / sampleRate
 *
 * The anchor is refreshed periodically so the per-sample stamps track clock drift rather than
 * accumulating error from a single startup reading.
 */
class AudioSource(
    private val config: AudioConfig,
    private val out: SourceSink,
) {
    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var readerThread: Thread? = null

    private val droppedBuffers = AtomicLong(0)
    @Volatile private var anchorRefreshBoot = 0L
    @Volatile private var resolvedSourceName = "NONE"

    val droppedBufferCount: Long get() = droppedBuffers.get()
    val anchorAgeMillis: Long
        get() = if (anchorRefreshBoot == 0L) Long.MAX_VALUE else (SystemClock.elapsedRealtimeNanos() - anchorRefreshBoot) / 1_000_000
    val resolvedSource: String get() = resolvedSourceName

    private companion object {
        const val ANCHOR_REFRESH_NANOS = 1_000_000_000L // refresh the BOOTTIME anchor every ~1s
    }

    fun start() {
        val framesPerBuffer = config.sampleRate * config.bufferDurationMillis / 1000
        // Generous internal buffer (~320 ms) so scheduling jitter doesn't cause HAL overruns (which
        // show up as missing chunks). Read latency is unaffected — we still pull framesPerBuffer at a time.
        val minBuf = AudioRecord.getMinBufferSize(
            config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(framesPerBuffer * 2 * 16)

        val source = resolveSource()
        record = AudioRecord(
            source, config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf,
        )
        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord failed to initialize (source=$source)")
            record?.release(); record = null
            return
        }
        record?.startRecording()
        running = true
        readerThread = thread(name = "chronyx-audio", priority = Thread.MAX_PRIORITY) { readLoop(framesPerBuffer) }
    }

    private fun resolveSource(): Int {
        return when (config.source) {
            AudioSourcePreset.UNPROCESSED -> {
                if (Build.VERSION.SDK_INT >= 24) {
                    resolvedSourceName = "UNPROCESSED"
                    MediaRecorder.AudioSource.UNPROCESSED
                } else {
                    resolvedSourceName = "VOICE_RECOGNITION"
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                }
            }
            AudioSourcePreset.VOICE_RECOGNITION -> {
                resolvedSourceName = "VOICE_RECOGNITION"
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
        }
    }

    private fun readLoop(framesPerBuffer: Int) {
        val rec = record ?: return
        val anchor = AudioTimestamp()
        var haveAnchor = false
        var anchorFrame = 0L
        var anchorNanos = 0L
        var totalFramesRead = 0L

        while (running) {
            val pcm = ShortArray(framesPerBuffer)
            val read = rec.read(pcm, 0, framesPerBuffer)
            if (read <= 0) {
                if (read < 0) Timber.w("AudioRecord.read returned $read")
                droppedBuffers.incrementAndGet()
                continue
            }
            val firstFrameOfBuffer = totalFramesRead

            // Refresh the BOOTTIME anchor periodically (or on first read).
            val nowBoot = SystemClock.elapsedRealtimeNanos()
            if (!haveAnchor || nowBoot - anchorRefreshBoot >= ANCHOR_REFRESH_NANOS) {
                if (rec.getTimestamp(anchor, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    anchorFrame = anchor.framePosition
                    anchorNanos = anchor.nanoTime
                    anchorRefreshBoot = nowBoot
                    haveAnchor = true
                }
            }

            val tFirstSample = if (haveAnchor) {
                anchorNanos + ((firstFrameOfBuffer - anchorFrame) * 1_000_000_000L) / config.sampleRate
            } else {
                // No timestamp yet: approximate with the read-completion instant minus the buffer span.
                nowBoot - (read.toLong() * 1_000_000_000L) / config.sampleRate
            }

            out.onAudio(
                AudioBuffer(
                    tFirstSampleBoot = tFirstSample,
                    sampleRate = config.sampleRate,
                    channelCount = 1,
                    pcm = pcm,
                    frameCount = read,
                ),
            )
            totalFramesRead += read
        }
    }

    fun stop() {
        running = false
        readerThread?.join(500)
        readerThread = null
        try { record?.stop() } catch (_: Throwable) {}
        record?.release()
        record = null
    }
}
