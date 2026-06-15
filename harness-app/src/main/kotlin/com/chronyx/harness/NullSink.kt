package com.chronyx.harness

import com.chronyx.core.api.SessionMetadata
import com.chronyx.core.api.Sink
import com.chronyx.core.model.AudioBuffer
import com.chronyx.core.model.CameraCalibration
import com.chronyx.core.model.CameraFrameMeta
import com.chronyx.core.model.DeviceInfo
import com.chronyx.core.model.EncodedVideoFrame
import com.chronyx.core.model.GnssFix
import com.chronyx.core.model.GnssRawEpoch
import com.chronyx.core.model.ImuIntrinsics
import com.chronyx.core.model.ImuSample
import com.chronyx.core.model.SyncDiagnostics

/**
 * A [Sink] that discards everything. Used for the calibration gesture, which needs a live capture
 * session (to observe gyro + image motion) but does not record a file.
 */
class NullSink : Sink {
    override val bytesWritten: Long = 0
    override val sinkDrops = com.chronyx.core.api.SinkDrops()
    override fun open(metadata: SessionMetadata) {}
    override fun writeVideo(frame: EncodedVideoFrame) {}
    override fun writeCameraMeta(meta: CameraFrameMeta) {}
    override fun writeDeviceInfo(info: DeviceInfo) {}
    override fun writeCameraCalibration(calibration: CameraCalibration, tBoot: Long) {}
    override fun writeImuIntrinsics(intrinsics: ImuIntrinsics, tBoot: Long) {}
    override fun writeMarker(tBoot: Long, index: Long, label: String) {}
    override fun writeImu(sample: ImuSample) {}
    override fun writeGnssFix(fix: GnssFix) {}
    override fun writeGnssRaw(epoch: GnssRawEpoch) {}
    override fun writeAudio(buffer: AudioBuffer) {}
    override fun writeDiagnostics(diagnostics: SyncDiagnostics) {}
    override fun close() {}
}
