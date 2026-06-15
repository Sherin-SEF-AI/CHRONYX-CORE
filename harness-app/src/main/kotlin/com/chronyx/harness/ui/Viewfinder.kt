package com.chronyx.harness.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.chronyx.harness.CameraPreviewController

/**
 * A live camera viewfinder for framing the mount. Drives the [CameraPreviewController] from the
 * TextureView lifecycle, and is only composed when not recording (the service owns the camera then).
 */
@Composable
fun Viewfinder(preview: CameraPreviewController, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = preview.start(st)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { preview.stop(); return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose { preview.stop() }
    }
}
