package com.chronyx.harness

import android.app.Application
import android.os.Build
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chronyx.core.api.Bundling
import com.chronyx.core.api.CaptureSession
import com.chronyx.core.api.ChronyxConfig
import com.chronyx.core.api.Codec
import com.chronyx.core.api.Chronyx
import com.chronyx.core.api.ThermalPolicy
import com.chronyx.core.calib.CalibrationController
import com.chronyx.core.calib.ExcitationState
import com.chronyx.core.calib.LumaSnapshot
import com.chronyx.core.calib.LumaMotion
import com.chronyx.core.model.ImuChannel
import com.chronyx.core.perf.Preflight
import com.chronyx.service.ChronyxCaptureService
import com.chronyx.service.ChronyxServiceConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** A recording file row for the browser, enriched from the JSON manifest sidecar when present. */
data class RecordingFile(
    val file: File,
    val sizeBytes: Long,
    val name: String,
    val durationSec: Double? = null,
    val channelCount: Int? = null,
    val syncLockedPercent: Int? = null,
    val markerCount: Int? = null,
    val manifestFile: File? = null,
)

/**
 * Drives the harness UI. Recording runs in [ChronyxCaptureService] (foreground, screen-off survival);
 * the Activity observes the service's same-process [ChronyxCaptureService.Live] flows for live
 * diagnostics. Calibration runs an in-process camera+IMU session (no file) and feeds a
 * [CalibrationController].
 */
class HarnessViewModel(app: Application) : AndroidViewModel(app) {

    val diagnostics = ChronyxCaptureService.Live.diagnostics
    val recording: StateFlow<Boolean> = ChronyxCaptureService.Live.recording

    val permissionsGranted = MutableStateFlow(false)

    val settings = SettingsStore(app)
    val markerCount: StateFlow<Int> = ChronyxCaptureService.Live.markerCount

    private val outputDir: File = (getApplication<Application>().getExternalFilesDir(null)
        ?: getApplication<Application>().filesDir).apply { mkdirs() }

    val preflight = MutableStateFlow<Preflight.Status?>(null)

    /** Re-evaluate the go/no-go gate (call on resume + periodically from the UI). */
    fun refreshPreflight() {
        preflight.value = Preflight.evaluate(getApplication(), outputDir, permissionsGranted.value)
    }

    // ---- recording ----

    fun startRecording(requireCalibration: Boolean) {
        val s = settings.value
        val res = s.resolution
        val safeName = s.sessionName.ifBlank { "session" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val config = ChronyxServiceConfig(
            outputDirPath = outputDir.absolutePath,
            fileBaseName = "${safeName}_${System.currentTimeMillis()}",
            cameraWidth = res.width,
            cameraHeight = res.height,
            fps = s.fps,
            codecName = s.codec,
            focusModeName = s.focusMode,
            imuRateHz = s.imuRateHz,
            gnssEnabled = s.gnssEnabled,
            gnssRaw = s.gnssRaw,
            audioEnabled = s.audioEnabled,
            audioSampleRate = s.audioSampleRate,
            requireCalibration = requireCalibration,
            thermalAdaptive = true,
            maxBytesPerFile = 0,
            maxDurationMs = 0,
        )
        ChronyxCaptureService.start(getApplication(), config)
    }

    fun stopRecording() = ChronyxCaptureService.stop(getApplication())

    fun mark(label: String) = ChronyxCaptureService.Live.mark(label)

    // ---- preview ----

    val preview = CameraPreviewController(app)

    // ---- file browser ----

    private val _files = MutableStateFlow<List<RecordingFile>>(emptyList())
    val files: StateFlow<List<RecordingFile>> = _files.asStateFlow()

    val totalRecordingBytes = MutableStateFlow(0L)

    fun refreshFiles() {
        val all: List<File> = outputDir.listFiles()?.toList() ?: emptyList()
        val mcaps = all.filter { it.extension == "mcap" }.sortedByDescending { it.lastModified() }
        val list = mcaps.map { f ->
            // Manifest base name = file base name without the _NNNN.mcap rotation suffix.
            val base = f.nameWithoutExtension.replace(Regex("_\\d{4}$"), "")
            val manifest = File(outputDir, "${base}_manifest.json").takeIf { it.isFile }
            val m = manifest?.let { runCatching { org.json.JSONObject(it.readText()) }.getOrNull() }
            RecordingFile(
                file = f, sizeBytes = f.length(), name = f.name,
                durationSec = m?.optDouble("durationSec")?.takeIf { !it.isNaN() },
                channelCount = m?.optJSONArray("channels")?.length(),
                syncLockedPercent = m?.optDouble("syncLockedFraction")?.let { (it * 100).toInt() },
                markerCount = m?.optInt("markerCount"),
                manifestFile = manifest,
            )
        }
        _files.value = list
        totalRecordingBytes.value = all.sumOf { it.length() }
    }

    fun deleteFile(rec: RecordingFile) {
        rec.file.delete()
        rec.manifestFile?.delete()
        refreshFiles()
    }

    fun freeStorageBytes(): Long = outputDir.usableSpace

    // ---- calibration ----

    private var calibSession: CaptureSession? = null
    private var calibJob: Job? = null
    private var controller: CalibrationController? = null
    private var prevLuma: LumaSnapshot? = null

    private val _excitation = MutableStateFlow<ExcitationState?>(null)
    val excitation: StateFlow<ExcitationState?> = _excitation.asStateFlow()
    private val _calibResult = MutableStateFlow<String?>(null)
    val calibResult: StateFlow<String?> = _calibResult.asStateFlow()

    fun startCalibration() {
        if (calibSession != null) return
        _calibResult.value = null
        val res = settings.value.resolution
        val fps = settings.value.fps
        val deviceKey = buildString {
            append(Build.MODEL.replace(' ', '_'))
            append("_${res.width}x${res.height}@$fps")
        }
        val c = CalibrationController(getApplication(), deviceKey).also { it.start() }
        controller = c

        val config = ChronyxConfig.Builder()
            .camera(Size(res.width, res.height), fps = fps, codec = Codec.HEVC)
            .imu(rateHz = 200, uncalibrated = true)
            .sink(NullSink())
            .thermalPolicy(ThermalPolicy.NONE)
            .bundling(Bundling.PerFrame, imuWindowMs = 40)
            .build()
        val session = Chronyx.start(getApplication(), config)
        calibSession = session

        calibJob = viewModelScope.launch {
            session.bundles.collect { bundle ->
                // Feed gyro/accel from the IMU window.
                for (s in bundle.imuWindow) {
                    when (s.channel) {
                        ImuChannel.GYRO_RAW -> c.feedGyro(s.tBoot, s.x, s.y, s.z)
                        ImuChannel.ACCEL_RAW -> c.feedAccel(s.x, s.y, s.z)
                        else -> {}
                    }
                }
                // Image-motion proxy from consecutive frames' luma.
                bundle.frame?.rawImage?.let { img ->
                    val snap = LumaMotion.snapshot(img)
                    prevLuma?.let { c.feedFrameMotion(bundle.tRef, LumaMotion.motion(it, snap)) }
                    prevLuma = snap
                }
                _excitation.value = c.excitation()
                bundle.release()
            }
        }
    }

    fun acceptCalibration() {
        val result = controller?.accept()
        _calibResult.value = if (result != null) {
            "td seed = ${result.tdSeedNanos / 1000} µs   confidence = ${"%.2f".format(result.confidence)}"
        } else {
            "REJECTED — insufficient excitation; move through full rotation + translation"
        }
        stopCalibration()
    }

    fun stopCalibration() {
        calibJob?.cancel(); calibJob = null
        controller?.cancel()
        calibSession?.stop(); calibSession = null
        prevLuma = null
    }

    override fun onCleared() {
        stopCalibration()
        preview.stop()
        super.onCleared()
    }
}
