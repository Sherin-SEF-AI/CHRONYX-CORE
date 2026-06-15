package com.chronyx.mcap.internal

import com.chronyx.core.model.AudioBuffer
import com.chronyx.core.model.CameraCalibration
import com.chronyx.core.model.CameraFrameMeta
import com.chronyx.core.model.CameraTimestampSource
import com.chronyx.core.model.DeviceInfo
import com.chronyx.core.model.EncodedVideoFrame
import com.chronyx.core.model.GnssFix
import com.chronyx.core.model.GnssRawEpoch
import com.chronyx.core.model.ImuIntrinsics
import com.chronyx.core.model.ImuSample
import com.chronyx.core.model.SyncDiagnostics
import com.chronyx.core.model.VideoCodec
import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import foxglove.CompressedVideo
import foxglove.LocationFix
import foxglove.CameraCalibration as CameraCalibrationProto
import com.chronyx.mcap.proto.AudioChunk
import com.chronyx.mcap.proto.CameraMeta
import com.chronyx.mcap.proto.DeviceInfo as DeviceInfoProto
import com.chronyx.mcap.proto.ImuIntrinsics as ImuIntrinsicsProto
import com.chronyx.mcap.proto.Marker as MarkerProto
import com.chronyx.mcap.proto.GnssMeasurement as GnssMeasurementProto
import com.chronyx.mcap.proto.GnssRawEpoch as GnssRawEpochProto
import com.chronyx.mcap.proto.ImuSample as ImuSampleProto
import com.chronyx.mcap.proto.SyncDiagnostics as SyncDiagnosticsProto
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Translates CHRONYX model records into serialized protobuf bytes for the MCAP message payloads. The
 * protobuf `timestamp` field on the Foxglove well-known messages is set equal to the BOOTTIME log
 * time so the message renders on the single shared axis with zero manual offset.
 */
internal object ProtoEncoders {

    private fun bootTimestamp(bootNanos: Long): Timestamp =
        Timestamp.newBuilder()
            .setSeconds(bootNanos / 1_000_000_000L)
            .setNanos((bootNanos % 1_000_000_000L).toInt())
            .build()

    fun video(frame: EncodedVideoFrame): ByteArray =
        CompressedVideo.newBuilder()
            .setTimestamp(bootTimestamp(frame.tMidBoot))
            .setFrameId("camera")
            .setData(ByteString.copyFrom(frame.data, 0, frame.size))
            .setFormat(if (frame.codec == VideoCodec.HEVC) "h265" else "h264")
            .build()
            .toByteArray()

    fun cameraMeta(meta: CameraFrameMeta): ByteArray =
        CameraMeta.newBuilder()
            .setFrameIndex(meta.frameIndex)
            .setTSensorBootNs(meta.tSensorBoot)
            .setTMidBootNs(meta.tMidBoot)
            .setExposureNs(meta.exposureNanos)
            .setRollingShutterSkewNs(meta.rollingShutterSkewNanos)
            .setTimestampSource(if (meta.timestampSource == CameraTimestampSource.REALTIME) "REALTIME" else "UNKNOWN")
            .setClockOffsetConfidence(meta.offsetConfidence)
            .setTdSeedNs(meta.tdSeedNanos ?: 0L)
            .setTdHasSeed(meta.tdSeedNanos != null)
            .setTdConfidence(meta.tdConfidence)
            .setIso(meta.iso)
            .setFocusDistanceDiopters(meta.focusDistanceDiopters)
            .setFocalLengthMm(meta.focalLengthMm)
            .setAfState(meta.afState)
            .setAeState(meta.aeState)
            .build()
            .toByteArray()

    fun cameraCalibration(c: CameraCalibration, tBoot: Long): ByteArray {
        val b = CameraCalibrationProto.newBuilder()
            .setTimestamp(bootTimestamp(tBoot))
            .setFrameId("camera")
            .setWidth(c.width)
            .setHeight(c.height)
            .setDistortionModel(c.distortionModel)
        for (v in c.k) b.addK(v)
        for (v in c.distortion) b.addD(v)
        // R = identity (monocular), P derived from K with zero translation.
        for (v in doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)) b.addR(v)
        if (c.k.size == 9) {
            b.addP(c.k[0]); b.addP(c.k[1]); b.addP(c.k[2]); b.addP(0.0)
            b.addP(c.k[3]); b.addP(c.k[4]); b.addP(c.k[5]); b.addP(0.0)
            b.addP(c.k[6]); b.addP(c.k[7]); b.addP(c.k[8]); b.addP(0.0)
        }
        return b.build().toByteArray()
    }

    fun deviceInfo(d: DeviceInfo): ByteArray {
        val b = DeviceInfoProto.newBuilder()
            .setSessionId(d.sessionId)
            .setDeviceManufacturer(d.deviceManufacturer)
            .setDeviceModel(d.deviceModel)
            .setOsVersion(d.osVersion)
            .setSdkInt(d.sdkInt)
            .setClockBase(d.clockBaseName)
            .setCameraId(d.cameraId)
            .setSensorPhysicalWidthMm(d.sensorPhysicalWidthMm)
            .setSensorPhysicalHeightMm(d.sensorPhysicalHeightMm)
            .setActiveArrayWidthPx(d.activeArrayWidthPx)
            .setActiveArrayHeightPx(d.activeArrayHeightPx)
            .setIntrinsicsSource(d.calibration.source.name)
            .setCalibWidth(d.calibration.width)
            .setCalibHeight(d.calibration.height)
            .setDistortionModel(d.calibration.distortionModel)
            .setExtrinsicAvailable(d.extrinsics.available)
            .setExtrinsicReference(d.extrinsics.referenceName)
        for (v in d.calibration.k) b.addK(v)
        for (v in d.calibration.distortion) b.addDistortion(v)
        for (v in d.extrinsics.translationMeters) b.addExtrinsicTranslationM(v)
        for (v in d.extrinsics.rotationQuaternion) b.addExtrinsicRotationQuat(v)
        return b.build().toByteArray()
    }

    fun marker(tBoot: Long, index: Long, label: String): ByteArray =
        MarkerProto.newBuilder().setTBootNs(tBoot).setIndex(index).setLabel(label).build().toByteArray()

    fun imuIntrinsics(i: ImuIntrinsics): ByteArray =
        ImuIntrinsicsProto.newBuilder()
            .setAccelName(i.accelName)
            .setAccelVendor(i.accelVendor)
            .setAccelResolution(i.accelResolution)
            .setAccelMaxRangeMps2(i.accelMaxRangeMps2)
            .setGyroName(i.gyroName)
            .setGyroVendor(i.gyroVendor)
            .setGyroResolution(i.gyroResolution)
            .setGyroMaxRangeRadS(i.gyroMaxRangeRadPerSec)
            .setTargetRateHz(i.targetRateHz)
            .setAchievedRateHz(i.achievedRateHz)
            .build()
            .toByteArray()

    fun imu(sample: ImuSample): ByteArray =
        ImuSampleProto.newBuilder()
            .setTBootNs(sample.tBoot)
            .setChannel(sample.channel.name)
            .setX(sample.x)
            .setY(sample.y)
            .setZ(sample.z)
            .build()
            .toByteArray()

    fun gnssFix(fix: GnssFix): ByteArray =
        LocationFix.newBuilder()
            .setTimestamp(bootTimestamp(fix.tBoot))
            .setFrameId("gnss")
            .setLatitude(fix.latitudeDeg)
            .setLongitude(fix.longitudeDeg)
            .setAltitude(fix.altitudeMeters)
            .build()
            .toByteArray()

    fun gnssRaw(epoch: GnssRawEpoch): ByteArray {
        val b = GnssRawEpochProto.newBuilder()
            .setTBootNs(epoch.tBoot)
            .setTimeNs(epoch.timeNanos)
            .setFullBiasNs(epoch.fullBiasNanos)
            .setBiasNs(epoch.biasNanos)
            .setDriftNsPerS(epoch.driftNanosPerSecond)
            .setGpsTimeNs(epoch.gpsTimeNanos)
            .setHardwareClockDiscontinuityCount(epoch.hardwareClockDiscontinuityCount)
        for (m in epoch.measurements) {
            b.addMeasurements(
                GnssMeasurementProto.newBuilder()
                    .setSvid(m.svid)
                    .setConstellation(m.constellation.name)
                    .setCn0Dbhz(m.cn0DbHz)
                    .setReceivedSvTimeNs(m.receivedSvTimeNanos)
                    .setPseudorangeRateMps(m.pseudorangeRateMetersPerSecond)
                    .setAccumulatedDeltaRangeM(m.accumulatedDeltaRangeMeters)
                    .setAdrValid(m.adrState.valid)
                    .setAdrResetDetected(m.adrState.resetDetected)
                    .setAdrCycleSlipDetected(m.adrState.cycleSlipDetected)
                    .setCarrierFrequencyHz(m.carrierFrequencyHz)
                    .setMultipathIndicator(m.multipathIndicator)
                    .build(),
            )
        }
        return b.build().toByteArray()
    }

    fun audio(buffer: AudioBuffer): ByteArray {
        val bb = ByteBuffer.allocate(buffer.frameCount * buffer.channelCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        val n = buffer.frameCount * buffer.channelCount
        for (i in 0 until n) bb.putShort(buffer.pcm[i])
        return AudioChunk.newBuilder()
            .setTFirstSampleBootNs(buffer.tFirstSampleBoot)
            .setSampleRate(buffer.sampleRate)
            .setChannelCount(buffer.channelCount)
            .setFrameCount(buffer.frameCount)
            .setPcm(ByteString.copyFrom(bb.array()))
            .build()
            .toByteArray()
    }

    fun diagnostics(d: SyncDiagnostics): ByteArray {
        val b = SyncDiagnosticsProto.newBuilder()
            .setTBootNs(d.tBoot)
            .setRecording(d.recording)
            .setSyncLocked(d.syncLocked)
            .setEngineBundleRateHz(d.engine.bundleRateHz)
            .setEngineDroppedBundles(d.engine.droppedBundles)
            .setEngineMcapThroughputBps(d.engine.mcapWriteThroughputBytesPerSec)
            .setEngineMcapBytesWritten(d.engine.mcapBytesWritten)
            .setThermalStatus(d.resources.thermalStatus)
            .setActiveDegradation(d.resources.activeDegradation ?: "")
            .setBatteryTemperatureC(d.resources.batteryTemperatureCelsius)
            .setFreeStorageBytes(d.resources.freeStorageBytes)

        d.camera?.let { c ->
            b.setHasCamera(true)
                .setCameraTimestampSource(if (c.timestampSource == CameraTimestampSource.REALTIME) "REALTIME" else "UNKNOWN")
                .setCameraFps(c.achievedFps)
                .setCameraRollingShutterSkewNs(c.currentRollingShutterSkewNanos)
                .setCameraExposureNs(c.currentExposureNanos)
                .setCameraTdConfidence(c.tdConfidence)
                .setCameraClockOffsetConfidence(c.clockOffsetConfidence)
                .setCameraEncoderQueueDepth(c.encoderQueueDepth)
                .setCameraDroppedFrames(c.droppedFrames)
        }
        d.imu?.let { i ->
            b.setHasImu(true)
                .setImuDetectedBase(i.detectedBase)
                .setImuAchievedRateHz(i.achievedRateHz)
                .setImuTargetRateHz(i.targetRateHz)
                .setImuDroppedSamples(i.droppedSamples)
                .setImuAppliedOffsetNs(i.appliedOffsetNanos)
        }
        d.gnss?.let { g ->
            b.setHasGnss(true)
                .setGnssFixAgeMs(g.fixAgeMillis)
                .setGnssSatellitesInFix(g.satellitesInFix)
                .setGnssSatellitesVisible(g.satellitesVisible)
                .setGnssSatellitesUsed(g.satellitesUsed)
                .setGnssMeanCn0Dbhz(g.meanCn0DbHz)
                .setGnssRawSupported(g.rawMeasurementsSupported)
                .setGnssRawActive(g.rawMeasurementsActive)
                .setGnssUtcResidualNs(g.utcResidualNanos)
                .setGnssDroppedEpochs(g.droppedEpochs)
            g.bootToUtc?.let { m ->
                b.setBootToUtcValid(true)
                    .setBootToUtcRefNs(m.bootRefNanos)
                    .setBootToUtcOffsetNs(m.offsetNanos)
                    .setBootToUtcDriftPpm(m.driftPpm)
                    .setBootToUtcResidualMadNs(m.residualMadNanos)
            }
        }
        d.audio?.let { a ->
            b.setHasAudio(true)
                .setAudioSampleRate(a.sampleRate)
                .setAudioSource(a.source)
                .setAudioAnchorAgeMs(a.anchorAgeMillis)
                .setAudioDroppedBuffers(a.droppedBuffers)
        }
        return b.build().toByteArray()
    }
}
