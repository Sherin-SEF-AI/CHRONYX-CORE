package com.chronyx.core.perf

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import java.io.File

/**
 * Go/no-go safety gate evaluated BEFORE arming a long unattended capture, so a 30-minute vehicle run
 * isn't started at 5% battery, on a nearly-full disk, or while already throttling. Composes the same
 * resource primitives the running session uses (battery, storage, thermal) plus host-supplied
 * permission state. Pure read; cheap enough to call on a UI tick.
 */
object Preflight {

    data class Thresholds(
        val minBatteryPercent: Int = 15,
        val minFreeBytes: Long = 2L * 1024 * 1024 * 1024, // 2 GB
        /** Block at/above this `PowerManager.THERMAL_STATUS_*` (SEVERE = 3). */
        val maxThermalStatusExclusive: Int = PowerManager.THERMAL_STATUS_SEVERE,
    )

    data class Status(
        val go: Boolean,
        val batteryOk: Boolean,
        val storageOk: Boolean,
        val thermalOk: Boolean,
        val permissionsOk: Boolean,
        val batteryPercent: Int,
        val freeBytes: Long,
        val thermalStatus: Int,
        val reasons: List<String>,
    )

    fun evaluate(
        context: Context,
        outputDir: File,
        permissionsGranted: Boolean,
        thresholds: Thresholds = Thresholds(),
    ): Status = decide(batteryPercent(context), freeBytes(outputDir), thermalStatus(context), permissionsGranted, thresholds)

    /** Pure decision logic (no Android), so it is unit-testable. A negative [batteryPercent] = unknown. */
    fun decide(
        batteryPercent: Int,
        freeBytes: Long,
        thermalStatus: Int,
        permissionsGranted: Boolean,
        thresholds: Thresholds = Thresholds(),
    ): Status {
        val batteryOk = batteryPercent < 0 || batteryPercent >= thresholds.minBatteryPercent
        val storageOk = freeBytes >= thresholds.minFreeBytes
        val thermalOk = thermalStatus < thresholds.maxThermalStatusExclusive
        val permissionsOk = permissionsGranted
        val reasons = buildList {
            if (!permissionsOk) add("Permissions not granted")
            if (!batteryOk) add("Battery $batteryPercent% < ${thresholds.minBatteryPercent}%")
            if (!storageOk) add("Free space ${freeBytes / 1_000_000} MB < ${thresholds.minFreeBytes / 1_000_000} MB")
            if (!thermalOk) add("Thermal status $thermalStatus (already throttling)")
        }
        return Status(
            go = batteryOk && storageOk && thermalOk && permissionsOk,
            batteryOk, storageOk, thermalOk, permissionsOk,
            batteryPercent, freeBytes, thermalStatus, reasons,
        )
    }

    private fun batteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun freeBytes(dir: File): Long = try {
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (t: Throwable) {
        Long.MAX_VALUE
    }

    private fun thermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return PowerManager.THERMAL_STATUS_NONE
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return PowerManager.THERMAL_STATUS_NONE
        return pm.currentThermalStatus
    }
}
