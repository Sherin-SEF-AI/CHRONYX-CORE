package com.chronyx.core.perf

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.chronyx.core.api.ThermalPolicy
import timber.log.Timber
import java.io.File

/** A declared degradation step. Ordered: capture sheds resolution first, then fps, then bitrate. */
data class DegradationState(
    val level: Int,
    val description: String,
    val resolutionScale: Double,
    val fpsScale: Double,
    val bitrateScale: Double,
) {
    companion object {
        val NONE = DegradationState(0, "none", 1.0, 1.0, 1.0)
    }
}

/**
 * Watches SoC thermal status and drives an explicit, ordered degradation policy so a 30-minute
 * unattended capture survives throttling instead of being silently throttled by the OS.
 *
 * Sustained Camera2 + 200 Hz IMU + continuous mic + encode heats the SoC; an uncooled phone throttles
 * after ~15–30 min. On rising `PowerManager` thermal status we step down in a declared order
 * (resolution → fps → bitrate) and report every transition via [onDegradation] so the session can log
 * it into `/diag/sync` — capture parameters are never changed silently.
 */
class ThermalManager(
    private val context: Context,
    private val policy: ThermalPolicy,
    private val onDegradation: (DegradationState) -> Unit,
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    @Volatile var thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
        private set
    @Volatile var current: DegradationState = DegradationState.NONE
        private set

    fun start() {
        thermalStatus = if (Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus
        else PowerManager.THERMAL_STATUS_NONE
        if (policy == ThermalPolicy.NONE) return
        if (Build.VERSION.SDK_INT >= 29) {
            val l = PowerManager.OnThermalStatusChangedListener { status ->
                thermalStatus = status
                applyPolicy(status)
            }
            listener = l
            powerManager.addThermalStatusListener(l)
            applyPolicy(thermalStatus)
        }
    }

    private fun applyPolicy(status: Int) {
        val next = when (status) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> DegradationState.NONE
            PowerManager.THERMAL_STATUS_MODERATE ->
                DegradationState(1, "bitrate -25%", 1.0, 1.0, 0.75)
            PowerManager.THERMAL_STATUS_SEVERE ->
                DegradationState(2, "fps -33%, bitrate -50%", 1.0, 0.66, 0.5)
            else -> // CRITICAL / EMERGENCY / SHUTDOWN
                DegradationState(3, "resolution -50%, fps -50%, bitrate -50%", 0.5, 0.5, 0.5)
        }
        if (next.level != current.level) {
            Timber.w("Thermal status $status → degradation '${next.description}'")
            current = next
            onDegradation(next)
        }
    }

    fun batteryTemperatureCelsius(): Float {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (tenthsC < 0) Float.NaN else tenthsC / 10f
    }

    fun freeStorageBytes(dir: File): Long = try {
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (t: Throwable) {
        Long.MAX_VALUE
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 29) listener?.let { powerManager.removeThermalStatusListener(it) }
        listener = null
    }
}
