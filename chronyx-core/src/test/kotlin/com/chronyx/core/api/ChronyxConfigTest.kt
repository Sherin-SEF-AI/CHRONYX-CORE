package com.chronyx.core.api

import android.util.Size
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
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChronyxConfigTest {

    private fun fakeSink() = object : Sink {
        override val bytesWritten = 0L
        override val sinkDrops = SinkDrops()
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

    @Test(expected = ChronyxConfigException::class)
    fun missingSinkThrows() {
        ChronyxConfig.Builder().camera(Size(1920, 1080)).build()
    }

    @Test(expected = ChronyxConfigException::class)
    fun perFrameWithoutCameraThrows() {
        ChronyxConfig.Builder().sink(fakeSink()).bundling(Bundling.PerFrame).build()
    }

    @Test(expected = ChronyxConfigException::class)
    fun calibrationWithoutCameraAndImuThrows() {
        ChronyxConfig.Builder().sink(fakeSink()).bundling(Bundling.FixedRate, bundleRateHz = 30)
            .requireCalibration(true).build()
    }

    @Test
    fun validConfigBuilds() {
        val config = ChronyxConfig.Builder()
            .camera(Size(1920, 1080), fps = 30, codec = Codec.HEVC)
            .imu(rateHz = 200)
            .gnss(rawMeasurements = true)
            .audio(sampleRate = 48_000, source = AudioSource.UNPROCESSED)
            .sink(fakeSink())
            .build()
        assertThat(config.clockBase).isEqualTo(ClockBase.BOOTTIME)
        assertThat(config.camera!!.fps).isEqualTo(30)
        assertThat(config.imu!!.uncalibrated).isTrue()
    }

    @Test(expected = ChronyxConfigException::class)
    fun badAudioRateThrows() {
        ChronyxConfig.Builder().camera(Size(1280, 720)).sink(fakeSink()).audio(sampleRate = 12345).build()
    }
}
