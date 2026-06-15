package com.chronyx.harness

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A camera resolution preset. */
data class ResolutionPreset(val label: String, val width: Int, val height: Int)

val RESOLUTION_PRESETS = listOf(
    ResolutionPreset("1920x1080", 1920, 1080),
    ResolutionPreset("1280x720", 1280, 720),
    ResolutionPreset("3840x2160", 3840, 2160),
)
val FPS_PRESETS = listOf(24, 30, 60)
val IMU_RATE_PRESETS = listOf(100, 200, 400)
val CODECS = listOf("HEVC", "AVC")
val FOCUS_MODES = listOf("FIXED_INFINITY", "AUTO")
val AUDIO_RATES = listOf(48000, 44100, 16000)

/** User-configurable capture settings, persisted across launches. */
data class CaptureSettings(
    val resolutionIndex: Int = 1, // 1280x720 default (broad device support)
    val fps: Int = 30,
    val codec: String = "HEVC",
    val imuRateHz: Int = 200,
    val focusMode: String = "FIXED_INFINITY",
    val gnssEnabled: Boolean = true,
    val gnssRaw: Boolean = true,
    val audioEnabled: Boolean = true,
    val audioSampleRate: Int = 48000,
    val sessionName: String = "session",
) {
    val resolution: ResolutionPreset get() = RESOLUTION_PRESETS[resolutionIndex.coerceIn(RESOLUTION_PRESETS.indices)]
}

/** SharedPreferences-backed settings with a reactive [flow]. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("chronyx_settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<CaptureSettings> = _flow.asStateFlow()

    val value: CaptureSettings get() = _flow.value

    fun update(transform: (CaptureSettings) -> CaptureSettings) {
        val next = transform(_flow.value)
        save(next)
        _flow.value = next
    }

    private fun load() = CaptureSettings(
        resolutionIndex = prefs.getInt("resIdx", 1),
        fps = prefs.getInt("fps", 30),
        codec = prefs.getString("codec", "HEVC") ?: "HEVC",
        imuRateHz = prefs.getInt("imuRate", 200),
        focusMode = prefs.getString("focus", "FIXED_INFINITY") ?: "FIXED_INFINITY",
        gnssEnabled = prefs.getBoolean("gnss", true),
        gnssRaw = prefs.getBoolean("gnssRaw", true),
        audioEnabled = prefs.getBoolean("audio", true),
        audioSampleRate = prefs.getInt("audioRate", 48000),
        sessionName = prefs.getString("name", "session") ?: "session",
    )

    private fun save(s: CaptureSettings) {
        prefs.edit()
            .putInt("resIdx", s.resolutionIndex).putInt("fps", s.fps).putString("codec", s.codec)
            .putInt("imuRate", s.imuRateHz).putString("focus", s.focusMode)
            .putBoolean("gnss", s.gnssEnabled).putBoolean("gnssRaw", s.gnssRaw)
            .putBoolean("audio", s.audioEnabled).putInt("audioRate", s.audioSampleRate)
            .putString("name", s.sessionName)
            .apply()
    }
}
