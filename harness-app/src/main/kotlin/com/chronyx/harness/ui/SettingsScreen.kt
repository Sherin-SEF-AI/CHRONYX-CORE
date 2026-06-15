package com.chronyx.harness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.chronyx.harness.AUDIO_RATES
import com.chronyx.harness.CODECS
import com.chronyx.harness.FOCUS_MODES
import com.chronyx.harness.FPS_PRESETS
import com.chronyx.harness.HarnessViewModel
import com.chronyx.harness.IMU_RATE_PRESETS
import com.chronyx.harness.RESOLUTION_PRESETS
import com.chronyx.harness.ui.theme.ChronyxColors

/**
 * Capture configuration, persisted. Tap a value to cycle presets — production apps don't hardcode
 * capture params. The session name prefixes the recording filename.
 */
@Composable
fun SettingsScreen(vm: HarnessViewModel) {
    val s by vm.settings.flow.collectAsState()
    val recording by vm.recording.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SETTINGS", style = MaterialTheme.typography.titleMedium, color = ChronyxColors.TextPrimary)
        if (recording) Text("Locked while recording.", color = ChronyxColors.Alarm, style = MaterialTheme.typography.bodySmall)
        val enabled = !recording

        Section("SESSION") {
            NameField(s.sessionName, enabled) { vm.settings.update { c -> c.copy(sessionName = it) } }
        }
        Section("CAMERA") {
            Cycler("resolution", s.resolution.label, enabled) {
                vm.settings.update { c -> c.copy(resolutionIndex = (c.resolutionIndex + 1) % RESOLUTION_PRESETS.size) }
            }
            Cycler("fps", s.fps.toString(), enabled) {
                vm.settings.update { c -> c.copy(fps = next(FPS_PRESETS, c.fps)) }
            }
            Cycler("codec", s.codec, enabled) {
                vm.settings.update { c -> c.copy(codec = next(CODECS, c.codec)) }
            }
            Cycler("focus", s.focusMode, enabled) {
                vm.settings.update { c -> c.copy(focusMode = next(FOCUS_MODES, c.focusMode)) }
            }
        }
        Section("IMU") {
            Cycler("rate (Hz)", s.imuRateHz.toString(), enabled) {
                vm.settings.update { c -> c.copy(imuRateHz = next(IMU_RATE_PRESETS, c.imuRateHz)) }
            }
        }
        Section("GNSS") {
            Toggle("enabled", s.gnssEnabled, enabled) { vm.settings.update { c -> c.copy(gnssEnabled = it) } }
            Toggle("raw measurements", s.gnssRaw, enabled) { vm.settings.update { c -> c.copy(gnssRaw = it) } }
        }
        Section("AUDIO") {
            Toggle("enabled", s.audioEnabled, enabled) { vm.settings.update { c -> c.copy(audioEnabled = it) } }
            Cycler("sample rate", "${s.audioSampleRate} Hz", enabled) {
                vm.settings.update { c -> c.copy(audioSampleRate = next(AUDIO_RATES, c.audioSampleRate)) }
            }
        }
    }
}

private fun <T> next(list: List<T>, current: T): T {
    val i = list.indexOf(current)
    return list[(if (i < 0) 0 else i + 1) % list.size]
}

@Composable
private fun Cycler(label: String, value: String, enabled: Boolean, onCycle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .clickable(enabled = enabled) { onCycle() },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = ChronyxColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(
            "$value  ▸",
            color = if (enabled) ChronyxColors.TextPrimary else ChronyxColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(enabled = enabled) { onChange(!value) },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = ChronyxColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (value) "ON" else "OFF",
            color = if (value && enabled) ChronyxColors.Phosphor else ChronyxColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NameField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("name  ", color = ChronyxColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = ChronyxColors.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 15.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(ChronyxColors.Phosphor),
            modifier = Modifier.fillMaxWidth().border(1.dp, ChronyxColors.Hairline)
                .background(ChronyxColors.Surface).padding(8.dp),
        )
    }
}
