package com.chronyx.harness

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import timber.log.Timber

/**
 * A lightweight, preview-ONLY Camera2 session for framing the mount before arming. Deliberately
 * separate from the capture pipeline: it holds the camera only while the Arm screen is visible and is
 * released the instant recording starts (the foreground service then owns the camera). This avoids any
 * surface-lifecycle coupling with the long-running capture session — and never touches timestamps.
 */
class CameraPreviewController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var open = false

    fun start(texture: SurfaceTexture) {
        if (open) return
        open = true
        thread = HandlerThread("chronyx-preview").also { it.start() }
        handler = Handler(thread!!.looper)
        val id = pickBackCamera() ?: run { Timber.w("No camera for preview"); return }
        texture.setDefaultBufferSize(1280, 720)
        val surface = Surface(texture)
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    configure(camera, surface)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    Timber.w("Preview camera error $error"); camera.close(); device = null
                }
            }, handler)
        } catch (e: SecurityException) {
            Timber.w(e, "Camera permission absent for preview")
        }
    }

    @Suppress("DEPRECATION")
    private fun configure(camera: CameraDevice, surface: Surface) {
        try {
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!open) { s.close(); return }
                    session = s
                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    s.setRepeatingRequest(req.build(), null, handler)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { Timber.w("Preview session failed") }
            }, handler)
        } catch (t: Throwable) {
            Timber.w(t, "Preview configure failed")
        }
    }

    private fun pickBackCamera(): String? = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: cameraManager.cameraIdList.firstOrNull()

    fun stop() {
        open = false
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { device?.close() } catch (_: Throwable) {}
        device = null
        thread?.quitSafely(); thread = null
        handler = null
    }
}
