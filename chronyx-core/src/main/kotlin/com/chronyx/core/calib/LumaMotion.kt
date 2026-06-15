package com.chronyx.core.calib

import android.media.Image
import kotlin.math.abs

/**
 * Computes a scalar image-motion proxy from consecutive camera frames' luma (Y) planes. During the
 * calibration gesture this proxy correlates with the gyro angular-rate magnitude; the lag between the
 * two series is the coarse camera↔IMU time offset seed.
 *
 * This is deliberately cheap (downsampled mean absolute luma difference), not optical flow — a coarse
 * seed is all CHRONYX produces; the VIO head refines td online.
 */
object LumaMotion {
    private const val STRIDE = 16 // sample every 16th pixel in both dimensions

    /** Copies a downsampled luma snapshot from an Image's Y plane for later differencing. */
    fun snapshot(image: Image): LumaSnapshot {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val cols = (w + STRIDE - 1) / STRIDE
        val rows = (h + STRIDE - 1) / STRIDE
        val out = ByteArray(cols * rows)
        var idx = 0
        var y = 0
        while (y < h) {
            val rowBase = y * rowStride
            var x = 0
            while (x < w) {
                val pos = rowBase + x * pixelStride
                out[idx++] = if (pos < buf.limit()) buf.get(pos) else 0
                x += STRIDE
            }
            y += STRIDE
        }
        return LumaSnapshot(cols, rows, out)
    }

    /** Mean absolute difference between two equally-sized snapshots, normalized to [0,255]. */
    fun motion(a: LumaSnapshot, b: LumaSnapshot): Double {
        if (a.data.size != b.data.size || a.data.isEmpty()) return 0.0
        var sum = 0L
        for (i in a.data.indices) {
            sum += abs((a.data[i].toInt() and 0xFF) - (b.data[i].toInt() and 0xFF))
        }
        return sum.toDouble() / a.data.size
    }
}

class LumaSnapshot(val cols: Int, val rows: Int, val data: ByteArray)
