package com.chronyx.mcap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import com.chronyx.core.api.Bundling
import com.chronyx.core.api.Chronyx
import com.chronyx.core.api.ChronyxConfig
import com.chronyx.core.api.Codec
import com.chronyx.core.api.ThermalPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device capture → MCAP smoke test, and the scaffold for the full sync acceptance test.
 *
 * This runs a short real capture and asserts a conformant, non-empty MCAP with the expected channels
 * is produced and finalized. The FULL acceptance test (clap + flash + sharp tap producing a coincident
 * audio transient, accel spike, and visual change, then asserting the three BOOTTIME stamps agree
 * within tolerance) is a manual hardware procedure documented in the README — it cannot be automated
 * without the physical stimulus rig.
 *
 * Requires CAMERA + RECORD_AUDIO granted; skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class CaptureSmokeTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun granted(p: String) =
        context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @Test
    fun recordsConformantMcapWithChannels() {
        assumeTrue("CAMERA permission required", granted(Manifest.permission.CAMERA))
        assumeTrue("RECORD_AUDIO permission required", granted(Manifest.permission.RECORD_AUDIO))

        val file = File(context.cacheDir, "smoke_${System.currentTimeMillis()}.mcap")
        // NONE so this test can byte-scan chunk contents; zstd is covered by ZstdRoundTripTest.
        val sink = McapSink(file, com.chronyx.mcap.internal.McapCompression.NONE)
        val config = ChronyxConfig.Builder()
            .camera(Size(1280, 720), fps = 30, codec = Codec.HEVC)
            .imu(rateHz = 200)
            .audio(sampleRate = 48_000)
            .sink(sink)
            .thermalPolicy(ThermalPolicy.ADAPTIVE)
            .bundling(Bundling.PerFrame, imuWindowMs = 40)
            .build()

        val session = Chronyx.start(context, config)
        Thread.sleep(2000)
        session.mark("smoke-marker") // operator marker on /markers
        Thread.sleep(2000) // ~4 s capture total
        session.stop()
        Thread.sleep(1000) // allow async writer to finalize

        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isGreaterThan(1024L)
        val bytes = file.readBytes()
        val magic = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(),
            'P'.code.toByte(), 0x30, 0x0D, 0x0A)
        assertThat(bytes.copyOfRange(0, 8)).isEqualTo(magic)
        assertThat(bytes.copyOfRange(bytes.size - 8, bytes.size)).isEqualTo(magic)

        // Cross-channel alignment guard: every channel must lie on the same BOOTTIME axis, so the
        // file's overall message-time span equals the capture duration (~4 s), NOT thousands of
        // seconds. This is exactly the failure mode CHRONYX exists to prevent (a video channel landing
        // in a different clock domain), so assert it directly from the Statistics record.
        val spanSeconds = statisticsSpanSeconds(bytes)
        assertThat(spanSeconds).isGreaterThan(0.5)
        assertThat(spanSeconds).isLessThan(30.0)

        // Self-describing metadata channels must be present so the data is usable downstream.
        fun contains(s: String) = String(bytes, Charsets.ISO_8859_1).contains(s)
        assertThat(contains("/camera/calibration")).isTrue()
        assertThat(contains("/device/info")).isTrue()
        assertThat(contains("/imu/intrinsics")).isTrue()
        // Operator marker channel + the marker label we inserted.
        assertThat(contains("/markers")).isTrue()
        assertThat(contains("smoke-marker")).isTrue()
        // DeviceInfo records the intrinsics provenance (REPORTED or DERIVED_FROM_FOCAL_LENGTH).
        assertThat(contains("DERIVED_FROM_FOCAL_LENGTH") || contains("REPORTED")).isTrue()
        // MCAP Metadata record (surfaced by `mcap info`) — key is unique to the metadata map.
        assertThat(contains("camera_resolution")).isTrue()
    }

    private fun u64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /** Reads message_start_time / message_end_time from the MCAP Statistics (op 0x0B) record. */
    private fun statisticsSpanSeconds(b: ByteArray): Double {
        var p = 8
        while (p < b.size - 8) {
            val op = b[p].toInt() and 0xFF
            val len = u64(b, p + 1)
            val c = p + 9
            if (op == 0x0B) {
                // message_count(8) schema(2) channel(4) attachment(4) metadata(4) chunk(4) then times
                val start = u64(b, c + 26)
                val end = u64(b, c + 34)
                return (end - start) / 1e9
            }
            p = c + len.toInt()
        }
        error("No Statistics record found in MCAP summary")
    }
}
