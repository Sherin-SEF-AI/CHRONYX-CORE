package com.chronyx.harness.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chronyx.core.model.CameraTimestampSource
import com.chronyx.core.model.SyncDiagnostics
import com.chronyx.harness.HarnessViewModel
import com.chronyx.harness.RecordingFile
import com.chronyx.harness.ui.theme.ChronyxColors
import androidx.compose.runtime.collectAsState

// ---- 1. Arm / Record ----

@Composable
fun RecordScreen(vm: HarnessViewModel) {
    val recording by vm.recording.collectAsState()
    val diag by vm.diagnostics.collectAsState()
    val permitted by vm.permissionsGranted.collectAsState()
    val preflight by vm.preflight.collectAsState()
    androidx.compose.runtime.LaunchedEffect(permitted) { vm.refreshPreflight() }

    val canRecord = preflight?.go == true

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val locked = diag?.syncLocked == true
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("ARMED", earned = canRecord && !recording)
            StatusPill("RECORDING", earned = recording)
            StatusPill("SYNC LOCKED", earned = recording && locked)
        }

        // Viewfinder for framing the mount (idle only — the service owns the camera while recording).
        if (!recording && permitted) {
            Viewfinder(
                vm.preview,
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).border(1.dp, ChronyxColors.Hairline),
            )
        }

        preflight?.let { pf ->
            Section("PRE-FLIGHT") {
                ReadoutRow("battery", "${pf.batteryPercent}%", if (pf.batteryOk) ChronyxColors.TextPrimary else ChronyxColors.Alarm)
                ReadoutRow("free storage", "${pf.freeBytes / 1_000_000} MB", if (pf.storageOk) ChronyxColors.TextPrimary else ChronyxColors.Alarm)
                ReadoutRow("thermal", pf.thermalStatus.toString(), if (pf.thermalOk) ChronyxColors.TextPrimary else ChronyxColors.Alarm)
                ReadoutRow("permissions", if (pf.permissionsOk) "GRANTED" else "MISSING", if (pf.permissionsOk) ChronyxColors.TextPrimary else ChronyxColors.Alarm)
                ReadoutRow("status", if (pf.go) "GO" else "NO-GO", if (pf.go) ChronyxColors.Phosphor else ChronyxColors.Alarm)
            }
        }

        Section("CAPTURE") {
            ReadoutRow("state", if (recording) "RECORDING" else if (permitted) "ARMED" else "PERMISSIONS REQUIRED",
                if (recording) ChronyxColors.Phosphor else ChronyxColors.TextPrimary)
            ReadoutRow("file bytes", diag?.engine?.mcapBytesWritten?.let { "${it / 1_000_000} MB" } ?: "—")
            ReadoutRow("bundle rate", diag?.engine?.bundleRateHz?.let { "%.1f Hz".format(it) } ?: "—")
            ReadoutRow("free storage", "${vm.freeStorageBytes() / 1_000_000} MB")
            ReadoutRow("thermal", diag?.resources?.thermalStatus?.toString() ?: "—")
            diag?.resources?.activeDegradation?.let { ReadoutRow("degradation", it, ChronyxColors.Alarm) }
        }

        Spacer(Modifier.height(8.dp))

        if (!recording) {
            Button(
                onClick = { vm.startRecording(requireCalibration = false) },
                enabled = canRecord,
                modifier = Modifier.fillMaxWidth().height(96.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChronyxColors.Phosphor, contentColor = ChronyxColors.Surface,
                    disabledContainerColor = ChronyxColors.Hairline, disabledContentColor = ChronyxColors.TextDim,
                ),
            ) { Text("● RECORD", style = MaterialTheme.typography.titleLarge) }
        } else {
            val markers by vm.markerCount.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.stopRecording() },
                    modifier = Modifier.weight(1f).height(96.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChronyxColors.SurfaceRaised, contentColor = ChronyxColors.TextPrimary),
                ) { Text("■ STOP", style = MaterialTheme.typography.titleLarge) }
                Button(
                    onClick = { vm.mark("field") },
                    modifier = Modifier.weight(1f).height(96.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChronyxColors.Phosphor, contentColor = ChronyxColors.Surface),
                ) { Text("⚑ MARK\n$markers", style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
        }

        if (!canRecord && !recording) {
            val reasons = preflight?.reasons?.joinToString("; ").orEmpty()
            Text(
                if (reasons.isBlank()) "Record disabled until pre-flight passes." else "Blocked: $reasons",
                color = ChronyxColors.TextDim, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---- 2. Live sync diagnostics ----

@Composable
fun DiagnosticsScreen(vm: HarnessViewModel) {
    val diag by vm.diagnostics.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val d: SyncDiagnostics? = diag
        if (d == null) {
            Text("No active capture. Arm and record to see live sync diagnostics.", color = ChronyxColors.TextDim)
            return@Column
        }
        StatusPill(if (d.syncLocked) "SYNC LOCKED" else "ACQUIRING SYNC", earned = d.syncLocked)

        d.camera?.let { c ->
            Section("CAMERA") {
                // A bad clock source must be visible at a glance: UNKNOWN with low confidence is alarmed.
                val srcEarned = c.timestampSource == CameraTimestampSource.REALTIME || c.clockOffsetConfidence >= 0.8
                ReadoutRow("timestamp src", c.timestampSource.name,
                    if (srcEarned) ChronyxColors.Phosphor else ChronyxColors.Alarm)
                ReadoutRow("offset conf", "%.2f".format(c.clockOffsetConfidence))
                ReadoutRow("fps", "%.1f".format(c.achievedFps))
                ReadoutRow("rolling shutter", "%.1f ms".format(c.currentRollingShutterSkewNanos / 1e6))
                ReadoutRow("exposure", "%.2f ms".format(c.currentExposureNanos / 1e6))
                ReadoutRow("td seed", c.tdSeedNanos?.let { "${it / 1000} µs (conf %.2f)".format(c.tdConfidence) } ?: "unseeded")
                ReadoutRow("encoder queue", c.encoderQueueDepth.toString())
                ReadoutRow("dropped frames", c.droppedFrames.toString())
            }
        }
        d.imu?.let { i ->
            Section("IMU") {
                val baseEarned = i.detectedBase == "BOOTTIME"
                ReadoutRow("clock base", i.detectedBase, if (baseEarned) ChronyxColors.Phosphor else ChronyxColors.Alarm)
                ReadoutRow("rate", "%.0f / %.0f Hz".format(i.achievedRateHz, i.targetRateHz))
                ReadoutRow("applied offset", "${i.appliedOffsetNanos / 1000} µs")
                ReadoutRow("dropped", i.droppedSamples.toString())
            }
        }
        d.gnss?.let { g ->
            Section("GNSS") {
                ReadoutRow("fix age", if (g.fixAgeMillis == Long.MAX_VALUE) "no fix" else "${g.fixAgeMillis} ms")
                ReadoutRow("sats used/visible", "${g.satellitesUsed} / ${g.satellitesVisible}")
                ReadoutRow("mean C/N0", "%.1f dBHz".format(g.meanCn0DbHz))
                ReadoutRow("raw meas", if (g.rawMeasurementsActive) "ACTIVE" else if (g.rawMeasurementsSupported) "supported" else "unsupported",
                    if (g.rawMeasurementsActive) ChronyxColors.Phosphor else ChronyxColors.TextPrimary)
                ReadoutRow("elapsedRT↔UTC", "${g.utcResidualNanos / 1000} µs")
                g.bootToUtc?.let { ReadoutRow("boot→UTC drift", "%.2f ppm".format(it.driftPpm)) }
                ReadoutRow("dropped epochs", g.droppedEpochs.toString())
            }
        }
        d.audio?.let { a ->
            Section("AUDIO") {
                ReadoutRow("sample rate", "${a.sampleRate} Hz")
                ReadoutRow("source", a.source)
                ReadoutRow("anchor age", if (a.anchorAgeMillis == Long.MAX_VALUE) "none" else "${a.anchorAgeMillis} ms")
                ReadoutRow("dropped", a.droppedBuffers.toString())
            }
        }
        Section("ENGINE") {
            ReadoutRow("bundle rate", "%.1f Hz".format(d.engine.bundleRateHz))
            ReadoutRow("dropped bundles", d.engine.droppedBundles.toString())
            ReadoutRow("mcap throughput", "${d.engine.mcapWriteThroughputBytesPerSec / 1000} kB/s")
            ReadoutRow("mcap written", "${d.engine.mcapBytesWritten / 1_000_000} MB")
        }
        Section("THERMAL / STORAGE") {
            ReadoutRow("thermal status", d.resources.thermalStatus.toString())
            ReadoutRow("battery temp", if (d.resources.batteryTemperatureCelsius.isNaN()) "—" else "%.1f °C".format(d.resources.batteryTemperatureCelsius))
            ReadoutRow("free storage", "${d.resources.freeStorageBytes / 1_000_000} MB")
            d.resources.activeDegradation?.let { ReadoutRow("degradation", it, ChronyxColors.Alarm) }
        }
    }
}

// ---- 3. Calibration ----

@Composable
fun CalibrationScreen(vm: HarnessViewModel) {
    val excitation by vm.excitation.collectAsState()
    val result by vm.calibResult.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("CALIBRATION GESTURE", style = MaterialTheme.typography.titleMedium, color = ChronyxColors.TextPrimary)
        Text(
            "Move the phone through a figure-8 with BOTH rotation and translation. td and extrinsics are " +
                "unobservable without both.",
            color = ChronyxColors.TextDim, style = MaterialTheme.typography.bodySmall,
        )

        val ex = excitation
        Section("EXCITATION") {
            if (ex == null) {
                ReadoutRow("status", "idle — press START")
            } else {
                val axes = listOf("x", "y", "z")
                axes.forEachIndexed { i, ax ->
                    ReadoutRow("gyro $ax", "%.2f".format(ex.gyroEnergy[i]),
                        if (ex.gyroEnergy[i] >= 0.5) ChronyxColors.Phosphor else ChronyxColors.TextSecondary)
                }
                axes.forEachIndexed { i, ax ->
                    ReadoutRow("accel $ax", "%.2f".format(ex.accelEnergy[i]),
                        if (ex.accelEnergy[i] >= 0.5) ChronyxColors.Phosphor else ChronyxColors.TextSecondary)
                }
                ReadoutRow("sufficient", if (ex.sufficient) "YES" else "NO",
                    if (ex.sufficient) ChronyxColors.Phosphor else ChronyxColors.Alarm)
            }
        }

        result?.let {
            Section("RESULT") { Text(it, color = ChronyxColors.TextPrimary, style = MaterialTheme.typography.bodyMedium) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.startCalibration() }, modifier = Modifier.weight(1f)) { Text("START") }
            Button(
                onClick = { vm.acceptCalibration() },
                enabled = excitation?.sufficient == true,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ChronyxColors.Phosphor, contentColor = ChronyxColors.Surface),
            ) { Text("ACCEPT") }
        }
    }
}

// ---- 4. File browser ----

@Composable
fun FileBrowserScreen(vm: HarnessViewModel) {
    val files by vm.files.collectAsState()
    val total by vm.totalRecordingBytes.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedRefresh(vm)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("RECORDINGS", style = MaterialTheme.typography.titleMedium, color = ChronyxColors.TextPrimary)
        Text("${files.size} files · ${total / 1_000_000} MB on disk · export via the share sheet or adb pull",
            color = ChronyxColors.TextDim, style = MaterialTheme.typography.bodySmall)
        if (files.isEmpty()) {
            Text("No recordings yet.", color = ChronyxColors.TextDim)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files) { f ->
                    FileRow(f, onDelete = { vm.deleteFile(f) }, onShare = { com.chronyx.harness.shareRecording(context, f) })
                }
            }
        }
    }
}

@Composable
private fun FileRow(f: RecordingFile, onDelete: () -> Unit, onShare: () -> Unit) {
    Section(f.name) {
        ReadoutRow("size", "${f.sizeBytes / 1_000_000} MB")
        f.durationSec?.let { ReadoutRow("duration", "%.1f min".format(it / 60.0)) }
        f.channelCount?.let { ReadoutRow("channels", it.toString()) }
        f.syncLockedPercent?.let {
            ReadoutRow("sync locked", "$it%", if (it >= 90) ChronyxColors.Phosphor else ChronyxColors.TextPrimary)
        }
        f.markerCount?.takeIf { it > 0 }?.let { ReadoutRow("markers", it.toString()) }
        ReadoutRow("path", f.file.absolutePath, ChronyxColors.TextDim)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onShare, colors = ButtonDefaults.buttonColors(
                containerColor = ChronyxColors.Phosphor, contentColor = ChronyxColors.Surface)) { Text("EXPORT") }
            OutlinedButton(onClick = onDelete) { Text("DELETE", color = ChronyxColors.Alarm) }
        }
    }
}

@Composable
private fun LaunchedRefresh(vm: HarnessViewModel) {
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshFiles() }
}
