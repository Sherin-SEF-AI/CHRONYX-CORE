package com.chronyx.core.sync

import com.chronyx.core.api.Bundling
import com.chronyx.core.api.BundlingConfig
import com.chronyx.core.model.AudioBuffer
import com.chronyx.core.model.CameraFrame
import com.chronyx.core.model.CameraFrameMeta
import com.chronyx.core.model.GnssFix
import com.chronyx.core.model.ImuSample
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Assembles [SyncedBundle]s on the BOOTTIME axis from the independent per-channel rings.
 *
 * Reference selection follows [BundlingConfig.mode]: `PerFrame` uses each delivered camera frame's
 * `t_mid` as `t_ref`; `FixedRate`/`PerImuSample` produce frame-less bundles for non-camera heads.
 *
 * Backpressure: assembled bundles go into a small bounded buffer. If the consumer is slow, the oldest
 * pending bundle is dropped — and its camera frame closed, so the HardwareBuffer returns to the pool —
 * rather than stalling capture. [droppedBundles] is surfaced to diagnostics.
 *
 * Frame ownership: [bundles] is intended for a single live-perception collector, which must call
 * [SyncedBundle.release] when done. Undelivered/dropped bundles are released by the engine.
 */
class SyncEngine(
    private val bundling: BundlingConfig,
    imuCapacity: Int = 4096,
    gnssCapacity: Int = 64,
    audioCapacity: Int = 256,
    private val maxPending: Int = 4,
) {
    private val imuRing = TimeIndexedRing<ImuSample>(imuCapacity) { it.tBoot }
    private val gnssRing = TimeIndexedRing<GnssFix>(gnssCapacity) { it.tBoot }
    private val audioRing = TimeIndexedRing<AudioBuffer>(audioCapacity) { it.tFirstSampleBoot }

    @Volatile private var tdSeed = CameraImuOffsetSeed(0L, 0.0)

    private val pending = ArrayDeque<SyncedBundle>(maxPending)
    private val lock = Any()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    val droppedBundles = AtomicLong(0)
    private val emittedBundles = AtomicLong(0)
    @Volatile private var rateAnchorBoot = 0L
    @Volatile private var rateCount = 0L
    @Volatile private var bundleRateValue = 0.0

    val bundleRateHz: Double get() = bundleRateValue
    val imuDropped: Long get() = imuRing.droppedCount
    val gnssDropped: Long get() = gnssRing.droppedCount
    val audioDropped: Long get() = audioRing.droppedCount

    fun setTdSeed(seed: CameraImuOffsetSeed) { tdSeed = seed }

    // ---- ingest (sensor threads) ----

    fun onImu(sample: ImuSample) {
        imuRing.add(sample)
        if (bundling.mode == Bundling.PerImuSample &&
            (sample.channel.name == "GYRO_RAW" || sample.channel.name == "ACCEL_RAW")
        ) {
            assembleAndDispatch(sample.tBoot, frame = null)
        }
    }

    fun onGnssFix(fix: GnssFix) { gnssRing.add(fix) }
    fun onAudio(buffer: AudioBuffer) { audioRing.add(buffer) }

    fun onCameraMeta(meta: CameraFrameMeta) { /* td seed/diagnostics handled via metadata elsewhere */ }

    /** PerFrame trigger: the frame carries its own BOOTTIME `t_mid`. */
    fun onCameraFrame(frame: CameraFrame) {
        if (bundling.mode == Bundling.PerFrame) {
            assembleAndDispatch(frame.tMidBoot, frame)
        } else {
            // Frame not used as a bundle trigger in this mode; release it so the buffer returns.
            frame.close()
        }
    }

    /** FixedRate trigger, driven by an external ticker in the session. */
    fun tick(tRef: Long) {
        if (bundling.mode == Bundling.FixedRate) assembleAndDispatch(tRef, frame = null)
    }

    private fun assembleAndDispatch(tRef: Long, frame: CameraFrame?) {
        val w = bundling.imuWindowMs * 1_000_000L
        val imuWindow = imuRing.range(tRef - w, tRef + w)
        val gnss = interpolateGnss(tRef)
        val audio = audioRing.range(tRef - w, tRef + w)
        val bundle = SyncedBundle(tRef, frame, imuWindow, gnss, audio, tdSeed)
        dispatch(bundle)
        updateRate(tRef)
    }

    private fun interpolateGnss(tRef: Long): InterpolatedFix? {
        val before = gnssRing.latestAtOrBefore(tRef) ?: gnssRing.latest() ?: return null
        val after = gnssRing.earliestAtOrAfter(tRef)
        val maxStaleNanos = bundling.gnssMaxStalenessMs * 1_000_000L

        if (after != null && after.tBoot != before.tBoot) {
            // Bracketed: linear interpolation by BOOTTIME.
            val span = (after.tBoot - before.tBoot).toDouble()
            val frac = ((tRef - before.tBoot).toDouble() / span).coerceIn(0.0, 1.0)
            return InterpolatedFix(
                tRef = tRef,
                latitudeDeg = lerp(before.latitudeDeg, after.latitudeDeg, frac),
                longitudeDeg = lerp(before.longitudeDeg, after.longitudeDeg, frac),
                altitudeMeters = lerp(before.altitudeMeters, after.altitudeMeters, frac),
                horizontalAccuracyMeters = maxOf(before.horizontalAccuracyMeters, after.horizontalAccuracyMeters),
                stale = false,
                ageMillis = 0,
            )
        }
        // No future fix to bracket with: use last known, mark stale if older than the bound. We never
        // extrapolate position.
        val age = tRef - before.tBoot
        return InterpolatedFix(
            tRef = tRef,
            latitudeDeg = before.latitudeDeg,
            longitudeDeg = before.longitudeDeg,
            altitudeMeters = before.altitudeMeters,
            horizontalAccuracyMeters = before.horizontalAccuracyMeters,
            stale = abs(age) > maxStaleNanos,
            ageMillis = age / 1_000_000,
        )
    }

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f

    private fun dispatch(bundle: SyncedBundle) {
        var evicted: SyncedBundle? = null
        synchronized(lock) {
            if (pending.size >= maxPending) {
                evicted = pending.removeFirst()
                droppedBundles.incrementAndGet()
            }
            pending.addLast(bundle)
        }
        evicted?.release()
        signal.trySend(Unit)
    }

    private fun updateRate(nowBoot: Long) {
        emittedBundles.incrementAndGet()
        rateCount++
        if (rateAnchorBoot == 0L) { rateAnchorBoot = nowBoot; return }
        val elapsed = nowBoot - rateAnchorBoot
        if (elapsed >= 1_000_000_000L) {
            bundleRateValue = rateCount * 1e9 / elapsed
            rateAnchorBoot = nowBoot
            rateCount = 0
        }
    }

    /** Single-consumer cold flow of assembled bundles. The collector owns and must release each. */
    val bundles: Flow<SyncedBundle> = flow {
        while (true) {
            signal.receive()
            while (true) {
                val next = synchronized(lock) { if (pending.isEmpty()) null else pending.removeFirst() }
                    ?: break
                emit(next)
            }
        }
    }

    fun clear() {
        imuRing.clear(); gnssRing.clear(); audioRing.clear()
        synchronized(lock) {
            while (pending.isNotEmpty()) pending.removeFirst().release()
        }
    }
}
