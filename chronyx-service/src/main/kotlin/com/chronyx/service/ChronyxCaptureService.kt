package com.chronyx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteCallbackList
import android.util.Size
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.chronyx.core.api.Bundling
import com.chronyx.core.api.CaptureSession
import com.chronyx.core.api.CaptureState
import com.chronyx.core.api.ChronyxConfig
import com.chronyx.core.api.Codec
import com.chronyx.core.api.AudioSource as AudioSourcePreset
import com.chronyx.core.api.ThermalPolicy
import com.chronyx.core.api.Chronyx
import com.chronyx.core.model.SyncDiagnostics
import com.chronyx.mcap.McapSink
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns a CHRONYX [CaptureSession] for screen-off, unattended capture, and
 * exposes the AIDL bound-service promotion path ([IChronyxCapture]). It is a thin lifecycle owner: the
 * actual capture is the same [CaptureSession] used by the in-process library path.
 *
 * Frames delivered to bound clients cross the process boundary as HardwareBuffer handles only.
 */
class ChronyxCaptureService : LifecycleService() {

    private val recording = AtomicBoolean(false)
    private var session: CaptureSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val callbacks = RemoteCallbackList<IChronyxBundleCallback>()
    private var bundleJob: Job? = null
    private var diagJob: Job? = null
    @Volatile private var notificationText = "Idle"
    private var activeConfig: ChronyxServiceConfig? = null

    private val binder = object : IChronyxCapture.Stub() {
        override fun startCapture(config: ChronyxServiceConfig) = this@ChronyxCaptureService.startCapture(config)
        override fun stopCapture() = this@ChronyxCaptureService.stopCapture()
        override fun isRecording(): Boolean = recording.get()
        override fun registerCallback(cb: IChronyxBundleCallback?) { cb?.let { callbacks.register(it) } }
        override fun unregisterCallback(cb: IChronyxBundleCallback?) { cb?.let { callbacks.unregister(it) } }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val config = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_CONFIG, ChronyxServiceConfig::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_CONFIG)
                }
                config?.let { startCapture(it) }
            }
            ACTION_STOP -> stopCapture()
            ACTION_MARK -> Live.mark(intent.getStringExtra(EXTRA_LABEL) ?: "mark")
        }
        // Not sticky: the OS must not silently resume capture without an explicit user re-arm.
        return START_NOT_STICKY
    }

    private fun startCapture(serviceConfig: ChronyxServiceConfig) {
        if (!recording.compareAndSet(false, true)) {
            Timber.w("startCapture ignored: already recording")
            return
        }
        createNotificationChannel()
        startForegroundCompat()
        acquireWakeLock()

        val dir = File(serviceConfig.outputDirPath).apply { mkdirs() }
        val sink = McapSink(
            fileFactory = { idx -> File(dir, "%s_%04d.mcap".format(serviceConfig.fileBaseName, idx)) },
            maxBytesPerFile = serviceConfig.maxBytesPerFile,
            maxDurationNanos = serviceConfig.maxDurationMs * 1_000_000L,
        )
        val config = buildConfig(serviceConfig, sink)
        val s = Chronyx.start(applicationContext, config)
        session = s
        activeConfig = serviceConfig
        Live.markHandler = { label -> s.mark(label) }
        Live.markerCount.value = 0
        Live.currentFilePath = File(dir, "%s_0000.mcap".format(serviceConfig.fileBaseName)).absolutePath
        Live.recordingState.value = true

        bundleJob = lifecycleScope.launch {
            s.bundles.collect { bundle ->
                val frame = bundle.frame
                val meta = BundleMetadata(
                    tRefBoot = bundle.tRef,
                    hasFrame = frame != null,
                    frameIndex = frame?.meta?.frameIndex ?: -1,
                    tMidBoot = frame?.meta?.tMidBoot ?: bundle.tRef,
                    exposureNanos = frame?.meta?.exposureNanos ?: 0,
                    rollingShutterSkewNanos = frame?.meta?.rollingShutterSkewNanos ?: 0,
                    imuSampleCount = bundle.imuWindow.size,
                    gnssStale = bundle.gnss?.stale ?: true,
                    tdSeedNanos = bundle.cameraImuOffsetSeed.nanos,
                    tdConfidence = bundle.cameraImuOffsetSeed.confidence,
                )
                broadcastBundle(meta, frame?.hardwareBuffer)
                bundle.release()
            }
        }
        diagJob = s.diagnostics.onEach { d ->
            Live.diagnostics.value = d
            notificationText = if (d.syncLocked) "RECORDING · SYNC LOCKED · ${d.engine.mcapBytesWritten / 1_000_000}MB"
            else "RECORDING · acquiring sync · ${d.engine.mcapBytesWritten / 1_000_000}MB"
            updateNotification()
            broadcastDiagnostics("bundles=${"%.1f".format(d.engine.bundleRateHz)}Hz dropped=${d.engine.droppedBundles}")
        }.launchIn(lifecycleScope)

        lifecycleScope.launch {
            s.state.collect { st -> if (st == CaptureState.STOPPED || st == CaptureState.ERROR) stopCapture() }
        }
    }

    private fun buildConfig(c: ChronyxServiceConfig, sink: McapSink): ChronyxConfig {
        val builder = ChronyxConfig.Builder()
            .camera(
                Size(c.cameraWidth, c.cameraHeight),
                fps = c.fps,
                codec = if (c.codecName.equals("AVC", true)) Codec.AVC else Codec.HEVC,
                wantRollingShutterSkew = c.wantRollingShutterSkew,
                focusMode = runCatching { com.chronyx.core.api.FocusMode.valueOf(c.focusModeName) }
                    .getOrDefault(com.chronyx.core.api.FocusMode.FIXED_INFINITY),
            )
            .sink(sink)
            .thermalPolicy(if (c.thermalAdaptive) ThermalPolicy.ADAPTIVE else ThermalPolicy.NONE)
            .requireCalibration(c.requireCalibration)
            .bundling(Bundling.PerFrame, imuWindowMs = 40)
        if (c.imuRateHz > 0) builder.imu(rateHz = c.imuRateHz, uncalibrated = c.imuUncalibrated)
        if (c.gnssEnabled) builder.gnss(rawMeasurements = c.gnssRaw)
        if (c.audioEnabled) {
            builder.audio(
                sampleRate = c.audioSampleRate,
                source = if (c.audioUnprocessed) AudioSourcePreset.UNPROCESSED else AudioSourcePreset.VOICE_RECOGNITION,
            )
        }
        return builder.build()
    }

    private fun broadcastBundle(meta: BundleMetadata, buffer: android.hardware.HardwareBuffer?) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).onBundleMetadata(meta, buffer)
                } catch (t: Throwable) {
                    Timber.w(t, "Bundle callback failed")
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun broadcastDiagnostics(line: String) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try { callbacks.getBroadcastItem(i).onDiagnostics(line) } catch (_: Throwable) {}
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun stopCapture() {
        if (!recording.compareAndSet(true, false)) return
        bundleJob?.cancel(); bundleJob = null
        diagJob?.cancel(); diagJob = null
        // Grab the summary while the session is alive, then finalize, then write the manifest sidecar.
        val summary = try { session?.summary() } catch (_: Throwable) { null }
        try { session?.stop() } catch (t: Throwable) { Timber.e(t, "session.stop failed") }
        writeManifest(summary)
        session = null
        Live.markHandler = null
        Live.recordingState.value = false
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    /** Write the JSON manifest sidecar next to the recording so browsers/pipelines are self-describing. */
    private fun writeManifest(summary: com.chronyx.core.api.SessionSummary?) {
        val cfg = activeConfig ?: return
        val s = summary ?: return
        try {
            val json = org.json.JSONObject().apply {
                put("sessionId", s.sessionId)
                put("deviceModel", s.deviceModel)
                put("fileBaseName", cfg.fileBaseName)
                put("startWallMillis", s.startWallMillis)
                put("startBootNanos", s.startBootNanos)
                put("durationSec", s.durationNanos / 1e9)
                put("syncLockedFraction", s.syncLockedFraction)
                put("markerCount", s.markerCount)
                put("gnssFixCount", s.gnssFixCount)
                put("georeferenceable", s.georeferenceable)
                put("imuAchievedRateHz", s.imuAchievedRateHz)
                put("mcapBytes", s.mcapBytes)
                put("intrinsicsSource", s.intrinsicsSource ?: "none")
                put("channels", org.json.JSONArray(s.channels))
                s.calibrationK?.let { put("calibrationK", org.json.JSONArray(it.toList())) }
                put("camera", "${cfg.cameraWidth}x${cfg.cameraHeight}@${cfg.fps} ${cfg.codecName}")
                put("imuRateHz", cfg.imuRateHz)
            }
            File(cfg.outputDirPath, "${cfg.fileBaseName}_manifest.json").writeText(json.toString(2))
        } catch (t: Throwable) {
            Timber.e(t, "Failed to write manifest")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Task removal must not silently drop the file; finalize cleanly.
        stopCapture()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopCapture()
        callbacks.kill()
        super.onDestroy()
    }

    // ---- foreground / notification / wakelock ----

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        builder
            .setContentTitle("CHRONYX capture")
            .setContentText(notificationText)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
        if (recording.get()) {
            val markIntent = Intent(this, ChronyxCaptureService::class.java).apply {
                action = ACTION_MARK; putExtra(EXTRA_LABEL, "field")
            }
            val flags = if (Build.VERSION.SDK_INT >= 23)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            else android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val pi = android.app.PendingIntent.getService(this, 1, markIntent, flags)
            builder.addAction(android.R.drawable.ic_menu_add, "MARK", pi)
        }
        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "CHRONYX capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chronyx:capture").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKELOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    /**
     * Same-process live state for the harness UI. The Activity observes these directly (no IPC) while
     * the AIDL path serves out-of-process clients. Cross-process clients use [IChronyxBundleCallback].
     */
    object Live {
        val diagnostics = MutableStateFlow<SyncDiagnostics?>(null)
        val recordingState = MutableStateFlow(false)
        val markerCount = MutableStateFlow(0)
        @Volatile var currentFilePath: String? = null
        @Volatile internal var markHandler: ((String) -> Unit)? = null

        val recording: StateFlow<Boolean> get() = recordingState.asStateFlow()

        /** Drop an operator marker on the live recording (no-op if not recording). */
        fun mark(label: String) {
            markHandler?.invoke(label)
            markerCount.value = markerCount.value + 1
        }
    }

    companion object {
        const val ACTION_START = "com.chronyx.service.action.START"
        const val ACTION_STOP = "com.chronyx.service.action.STOP"
        const val ACTION_MARK = "com.chronyx.service.action.MARK"
        const val EXTRA_CONFIG = "com.chronyx.service.extra.CONFIG"
        const val EXTRA_LABEL = "com.chronyx.service.extra.LABEL"

        private const val NOTIFICATION_ID = 0xC401
        private const val CHANNEL_ID = "chronyx_capture"
        private const val MAX_WAKELOCK_MS = 6L * 60 * 60 * 1000 // 6h safety cap; released on stop

        /** Starts capture as a foreground service. */
        fun start(context: android.content.Context, config: ChronyxServiceConfig) {
            val intent = Intent(context, ChronyxCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, config)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Stops capture and finalizes the file. */
        fun stop(context: android.content.Context) {
            context.startService(Intent(context, ChronyxCaptureService::class.java).apply { action = ACTION_STOP })
        }
    }
}
