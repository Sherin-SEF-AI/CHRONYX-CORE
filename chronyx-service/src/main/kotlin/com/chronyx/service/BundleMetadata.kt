package com.chronyx.service

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Lightweight, Binder-safe description of a [com.chronyx.core.sync.SyncedBundle], delivered alongside
 * the frame's HardwareBuffer handle. Carries timing/quality metadata only — no pixels.
 */
@Parcelize
data class BundleMetadata(
    val tRefBoot: Long,
    val hasFrame: Boolean,
    val frameIndex: Long,
    val tMidBoot: Long,
    val exposureNanos: Long,
    val rollingShutterSkewNanos: Long,
    val imuSampleCount: Int,
    val gnssStale: Boolean,
    val tdSeedNanos: Long,
    val tdConfidence: Double,
) : Parcelable
