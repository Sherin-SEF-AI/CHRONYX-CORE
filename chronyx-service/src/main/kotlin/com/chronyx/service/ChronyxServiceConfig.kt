package com.chronyx.service

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Cross-process capture configuration. Only primitives cross Binder; the service translates this into
 * a [com.chronyx.core.api.ChronyxConfig] and constructs the file sink in-process.
 */
@Parcelize
data class ChronyxServiceConfig(
    val outputDirPath: String,
    val fileBaseName: String = "chronyx",
    val cameraWidth: Int = 1920,
    val cameraHeight: Int = 1080,
    val fps: Int = 30,
    val codecName: String = "HEVC",
    val focusModeName: String = "FIXED_INFINITY",
    val wantRollingShutterSkew: Boolean = true,
    val imuRateHz: Int = 200,
    val imuUncalibrated: Boolean = true,
    val gnssEnabled: Boolean = true,
    val gnssRaw: Boolean = true,
    val audioEnabled: Boolean = true,
    val audioSampleRate: Int = 48_000,
    val audioUnprocessed: Boolean = true,
    val requireCalibration: Boolean = false,
    val thermalAdaptive: Boolean = true,
    val maxBytesPerFile: Long = 0,
    val maxDurationMs: Long = 0,
) : Parcelable
