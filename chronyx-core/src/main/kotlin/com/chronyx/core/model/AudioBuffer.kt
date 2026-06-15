package com.chronyx.core.model

/**
 * A fixed-duration PCM buffer tagged with the BOOTTIME of its first sample. Per-sample timestamps
 * are reconstructed downstream as `tFirstSampleBoot + sampleIndex · 1e9 / sampleRate`.
 *
 * @param tFirstSampleBoot BOOTTIME ns of `pcm[0]`, from `AudioTimestamp` interpolation.
 * @param pcm interleaved 16-bit signed PCM. For mono this is one sample per frame.
 * @param frameCount number of audio frames (samples-per-channel) in [pcm].
 */
data class AudioBuffer(
    val tFirstSampleBoot: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val pcm: ShortArray,
    val frameCount: Int,
) {
    val durationNanos: Long get() = frameCount.toLong() * 1_000_000_000L / sampleRate

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
