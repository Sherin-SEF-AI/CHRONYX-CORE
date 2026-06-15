package com.chronyx.core.capture

import com.chronyx.core.model.AudioBuffer
import com.chronyx.core.model.CameraFrame
import com.chronyx.core.model.CameraFrameMeta
import com.chronyx.core.model.EncodedVideoFrame
import com.chronyx.core.model.GnssFix
import com.chronyx.core.model.GnssRawEpoch
import com.chronyx.core.model.ImuSample

/**
 * The fan-out target every capture source emits into. The capture session implements this to route
 * each channel to BOTH the full-rate [com.chronyx.core.api.Sink] (the file) and the
 * [com.chronyx.core.sync.SyncEngine] (live bundles). Sources never know about the sink or engine
 * directly — they just stamp to BOOTTIME and emit here.
 *
 * All methods are called from sensor/encoder threads and MUST return quickly; the implementation
 * forwards into bounded queues/rings, never blocking the producer.
 */
interface SourceSink {
    /** A HardwareBuffer-backed YUV frame for live perception. The receiver owns it and must close it. */
    fun onCameraFrame(frame: CameraFrame)

    /** Per-frame camera metadata (also drives the engine's td seed and diagnostics). */
    fun onCameraMeta(meta: CameraFrameMeta)

    /** An encoded H.265/H.264 access unit for the file. */
    fun onEncodedVideo(frame: EncodedVideoFrame)

    fun onImu(sample: ImuSample)
    fun onGnssFix(fix: GnssFix)
    fun onGnssRaw(epoch: GnssRawEpoch)
    fun onAudio(buffer: AudioBuffer)
}
