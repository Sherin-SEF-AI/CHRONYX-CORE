<div align="center">

# CHRONYX

### One clock. Every sensor. Zero excuses.

**A production grade capture kernel that pulls camera, IMU, GNSS and microphone off a commodity Android phone and writes them time aligned on a single monotonic clock, straight into an MCAP file that opens in Foxglove with no manual offset.**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![minSdk](https://img.shields.io/badge/minSdk-26-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![MCAP](https://img.shields.io/badge/format-MCAP-00B8D9.svg)](https://mcap.dev)
[![Foxglove](https://img.shields.io/badge/opens%20in-Foxglove-FF6B00.svg)](https://foxglove.dev)
[![Verified](https://img.shields.io/badge/verified%20on-Galaxy%20A17-2EE6A6.svg)]()
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

</div>

---

## The problem nobody wants to admit

Your phone has a camera, a gyroscope, an accelerometer, a GNSS chip and a microphone. Each one stamps time on a different clock, on a different thread, at a different rate. The camera might report time on one base, the IMU on another, audio on a third. Stir that together and your "synchronized" dataset is quietly wrong by tens of milliseconds, which is the difference between a usable VIO trajectory and garbage.

Most apps paper over this. CHRONYX fixes it at the source.

## What CHRONYX does

It normalizes every single sample to one master axis, `SystemClock.elapsedRealtimeNanos()` (BOOTTIME), which is monotonic and survives deep sleep. Camera frames carry mid exposure timestamps. IMU samples get their clock base detected and corrected. GNSS fixes are keyed on their elapsed realtime instant, not the wall clock. Audio is stamped per sample. Everything lands on the same timeline, and that timeline is what gets written to disk.

The result opens in [Foxglove Studio](https://foxglove.dev) with all channels on one shared clock and zero manual offset.

```
   camera  ──┐
   imu     ──┤
   gnss    ──┼──►  [ BOOTTIME normalization ]  ──►  synced bundles  ──►  perception head
   audio   ──┘                                  └─►  MCAP file       ──►  Foxglove / training
```

## Proof, on real hardware

Verified end to end on a **Samsung Galaxy A17 (SM-A176B), Android 16, arm64**:

| Check | Result |
| --- | --- |
| Cross channel timeline span | **4.59 s** for a 4.6 s capture. Every channel on one BOOTTIME axis, zero manual offset |
| Camera and metadata alignment | `/camera/video` and `/camera/meta` start at the identical offset (0.716 s) |
| Camera timestamp source | REALTIME (sensor timestamp is natively BOOTTIME) |
| IMU clock base | BOOTTIME, no correction needed, 247 Hz uniform (HAL rounded from the 200 Hz request) |
| Recording | 2.4 MB conformant MCAP, 11 channels, 6700+ messages, clean head and tail magic |
| File integrity | 0 out of order messages, summary section present, indexed and seekable |

Two real defects were found and fixed during hardware bring up: an 11 second prediction error in the cross device UTC mapper, and the encoded video channel landing 96000 seconds off axis because this device stamps the encoder input surface in a different clock domain than the capture result. Both are fixed, and a regression guard now asserts cross channel span on every device run.

## Quickstart

A perception head never touches Camera2 or `SensorManager`. It builds a config, calls `start`, and receives time aligned bundles.

```kotlin
val config = ChronyxConfig.Builder()
    .camera(Size(1920, 1080), fps = 30, codec = Codec.HEVC, focusMode = FocusMode.FIXED_INFINITY)
    .imu(rateHz = 200, uncalibrated = true)
    .gnss(rawMeasurements = true)
    .audio(sampleRate = 48_000, source = AudioSource.UNPROCESSED)
    .clockBase(ClockBase.BOOTTIME)
    .bundling(Bundling.PerFrame, imuWindowMs = 40)
    .sink(McapSink(File(dir, "blr_dcr_01.mcap")))
    .thermalPolicy(ThermalPolicy.ADAPTIVE)
    .build()

val session = Chronyx.start(context, config)

session.bundles.collect { bundle ->
    val frame  = bundle.frame                // HardwareBuffer backed YUV, mid exposure BOOTTIME
    val imuWin = bundle.imuWindow            // all IMU samples around the frame
    val fix    = bundle.gnss                 // interpolated to the frame, with a staleness flag
    val tdSeed = bundle.cameraImuOffsetSeed  // coarse camera to IMU offset, VIO refines online
    // your perception goes here
    bundle.release()
}

session.diagnostics.collect { d -> /* live clock sources, rates, skew, residuals */ }
session.mark("pothole")   // drop a timestamped event onto the recording
session.stop()
```

Adding support for a new perception head means filling in the body of one lambda. That is the whole point.

## Feature highlights

**Capture, done right**
- Camera2 direct (not CameraX) so per frame `SENSOR_TIMESTAMP`, exposure, rolling shutter skew and timestamp source are all preserved
- Mid exposure frame timing, the VIO convention, with rolling shutter skew as a first class field
- Uncalibrated plus calibrated IMU, with automatic clock base detection and correction
- GNSS cooked fixes plus raw measurements, keyed on the BOOTTIME instant of the fix
- Microphone in UNPROCESSED mode with per sample BOOTTIME stamping

**Data you can actually use**
- Camera intrinsics recorded every session, read from the device when available, derived from focal length and sensor size when not, and honestly flagged either way
- IMU intrinsics, camera to IMU extrinsic seed, and a self describing device header
- Operator markers on a dedicated channel for annotating events in the field
- A JSON manifest sidecar per recording with duration, channels, sync locked percentage, marker count and a georeference gate

**Built for the field**
- Foreground service for screen off, unattended, multi hour capture
- Crash safe files: an uncaught exception handler and a recovery tool rebuild a finalized, openable file even after a hard kill
- Adaptive thermal policy that steps down resolution, fps and bitrate and logs every transition
- Pre flight gating on battery, storage, thermal and permissions before a long run starts
- Backpressure with honest per channel drop telemetry, never a silent stall

**The operator app**
- Live diagnostics that make a bad clock source visible at a glance
- Settings, named sessions, a live viewfinder, field markers, export through the share sheet, and a recordings browser
- A stark, high signal interface: matte black, monospace data, a single earned accent that only lights when sync is locked

## Architecture

A multi module Gradle project. The library is usable on its own, with or without the service.

| Module | Role |
| --- | --- |
| `chronyx-core` | Capture, sync engine, clock layer, public API. No service, no UI |
| `chronyx-mcap` | MCAP writer and schemas. Implements the core sink |
| `chronyx-service` | Foreground service and an AIDL bound service path that passes frames as buffer handles |
| `harness-app` | The diagnostics and recorder app |

The MCAP file carries these channels, every message stamped on BOOTTIME:

```
/camera/video         foxglove.CompressedVideo (HEVC or AVC)
/camera/meta          exposure, rolling shutter skew, ISO, focus, timestamp source
/camera/calibration   foxglove.CameraCalibration (K and distortion)
/imu/accel /imu/gyro  raw, calibrated and bias channels
/imu/intrinsics       sensor specs and achieved rate
/gnss/fix             foxglove.LocationFix plus the elapsed realtime to UTC residual
/gnss/raw             receiver clock and per satellite measurements
/audio/pcm            first sample BOOTTIME
/markers              operator inserted events
/diag/sync            self describing health snapshots
/device/info          one shot session header
```

## Build and run

Requirements: JDK 17, Android SDK with platform 34, an arm64 device.

```bash
git clone https://github.com/Sherin-SEF-AI/CHRONYX-CORE.git
cd CHRONYX-CORE

# unit tests (clock offset estimator, boot to UTC, MCAP round trip, recovery, preflight)
./gradlew :chronyx-core:testDebugUnitTest :chronyx-mcap:testDebugUnitTest

# assemble every module plus the harness APK
./gradlew assembleDebug

# install the harness on a connected device
./gradlew :harness-app:installDebug

# on device capture and integrity tests
./gradlew :chronyx-mcap:connectedDebugAndroidTest
```

Pull a recording and open it in Foxglove:

```bash
adb pull /sdcard/Android/data/com.chronyx.harness/files/your_session.mcap
```

## Honest limits

This project does not pretend.

- **Chunk compression is off by default.** The desktop zstd and lz4 native libraries do not load on Android, and the pure Java zstd path relies on internals that the Android runtime rejects (confirmed on device). The writer falls back to uncompressed and labels every chunk truthfully, so files are always valid. Real on device compression needs a native libzstd built with the NDK, which is a self contained follow up. Video is already H.265, so the uncompressed cost is only the few megabytes of inertial and audio data per file.
- **Camera intrinsics are derived from focal length on phones that do not publish a factory calibration**, which is most of them. They are a sound starting point and are flagged as derived. A checkerboard or AprilGrid calibration will improve the metric chain.
- **GNSS needs sky.** Indoor sessions produce no fix, the manifest marks them not georeferenceable, and absolute time rests on the device clock at session start.

## Roadmap

- NDK built libzstd for on device chunk compression
- Per device camera calibration tool
- Cross device UTC discipline hardening for the multi phone swarm case

## License

Released under the Apache License 2.0. See [LICENSE](LICENSE).
