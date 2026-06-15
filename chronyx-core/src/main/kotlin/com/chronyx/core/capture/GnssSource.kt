package com.chronyx.core.capture

import android.content.Context
import android.location.GnssClock
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.chronyx.core.api.GnssConfig
import com.chronyx.core.clock.xdevice.BootToUtcMapper
import com.chronyx.core.model.AdrState
import com.chronyx.core.model.Constellation
import com.chronyx.core.model.GnssFix
import com.chronyx.core.model.GnssMeasurementSample
import com.chronyx.core.model.GnssRawEpoch
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * GNSS capture as two parallel streams, both aligned to BOOTTIME:
 *  - **Cooked fixes** via [FusedLocationProviderClient]. The alignment key is
 *    `Location.getElapsedRealtimeNanos()` (the BOOTTIME instant of the fix) — NOT the UTC field —
 *    because that is what shares the axis with frames and IMU. UTC is retained for cross-device
 *    discipline and to drive the [BootToUtcMapper].
 *  - **Raw measurements** via `LocationManager.registerGnssMeasurementsCallback` (config-gated).
 *    GPS time is `timeNanos − (fullBiasNanos + biasNanos)`; each event is paired with
 *    `elapsedRealtimeNanos()` for BOOTTIME alignment.
 *
 * Permission absence and "raw measurements unsupported" are expected runtime states surfaced via
 * [rawSupported]/[rawActive], never crashes.
 */
class GnssSource(
    private val context: Context,
    private val config: GnssConfig,
    private val mapper: BootToUtcMapper,
    private val out: SourceSink,
) {
    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile var rawSupported: Boolean = false; private set
    @Volatile var rawActive: Boolean = false; private set
    @Volatile var satellitesInFix: Int = 0; private set
    @Volatile var lastFixBootNanos: Long = 0; private set
    @Volatile var lastUtcResidualNanos: Long = 0; private set

    // Authoritative satellite health from GnssStatus (the cooked-fix "satellites" extra is unreliable).
    @Volatile var satellitesVisible: Int = 0; private set
    @Volatile var satellitesUsed: Int = 0; private set
    @Volatile var meanCn0DbHz: Double = 0.0; private set

    private val droppedEpochs = AtomicLong(0)
    val droppedEpochCount: Long get() = droppedEpochs.get()

    private val fixCounter = AtomicLong(0)
    /** Total cooked fixes received this session — 0 means the session cannot be georeferenced. */
    val fixCount: Long get() = fixCounter.get()

    private var measurementsCallback: GnssMeasurementsEvent.Callback? = null
    private var statusCallback: android.location.GnssStatus.Callback? = null

    fun start() {
        thread = HandlerThread("chronyx-gnss").also { it.start() }
        handler = Handler(thread!!.looper)
        startCookedFixes()
        startStatus()
        if (config.rawMeasurements) startRawMeasurements()
    }

    private fun startCookedFixes() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, config.fixIntervalMillis)
            .setMinUpdateIntervalMillis(config.fixIntervalMillis)
            .build()
        try {
            fused.requestLocationUpdates(request, locationCallback, thread!!.looper)
        } catch (e: SecurityException) {
            Timber.w(e, "Location permission absent; GNSS cooked fixes disabled")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            fixCounter.incrementAndGet()
            val tBoot = if (Build.VERSION.SDK_INT >= 17) loc.elapsedRealtimeNanos else SystemClock.elapsedRealtimeNanos()
            val utcMillis = loc.time
            mapper.addPair(tBoot, utcMillis * 1_000_000L)
            // Residual: how far the boot→UTC model's prediction sits from the reported UTC.
            val residual = mapper.toUtcNanos(tBoot)?.let { predicted -> predicted - utcMillis * 1_000_000L } ?: 0L
            lastUtcResidualNanos = residual
            lastFixBootNanos = tBoot
            // Authoritative count from GnssStatus (the cooked-fix "satellites" extra is unreliable/absent).
            satellitesInFix = satellitesUsed

            out.onGnssFix(
                GnssFix(
                    tBoot = tBoot,
                    utcMillis = utcMillis,
                    latitudeDeg = loc.latitude,
                    longitudeDeg = loc.longitude,
                    altitudeMeters = if (loc.hasAltitude()) loc.altitude else 0.0,
                    horizontalAccuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 0f,
                    verticalAccuracyMeters = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy())
                        loc.verticalAccuracyMeters else 0f,
                    speedMps = if (loc.hasSpeed()) loc.speed else 0f,
                    bearingDeg = if (loc.hasBearing()) loc.bearing else 0f,
                    satellitesInFix = satellitesInFix,
                    utcResidualNanos = residual,
                ),
            )
        }
    }

    private fun startStatus() {
        val cb = object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                val n = status.satelliteCount
                var used = 0
                var cn0Sum = 0.0
                var cn0Count = 0
                for (i in 0 until n) {
                    if (status.usedInFix(i)) used++
                    val cn0 = status.getCn0DbHz(i)
                    if (cn0 > 0f) { cn0Sum += cn0; cn0Count++ }
                }
                satellitesVisible = n
                satellitesUsed = used
                meanCn0DbHz = if (cn0Count > 0) cn0Sum / cn0Count else 0.0
            }
        }
        statusCallback = cb
        try {
            locationManager.registerGnssStatusCallback(cb, handler)
        } catch (e: SecurityException) {
            Timber.w(e, "Location permission absent; GNSS status disabled")
        }
    }

    private fun startRawMeasurements() {
        val cb = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                rawActive = true
                val clock: GnssClock = event.clock
                val tBoot = if (Build.VERSION.SDK_INT >= 29 && clock.hasElapsedRealtimeNanos())
                    clock.elapsedRealtimeNanos else SystemClock.elapsedRealtimeNanos()
                val fullBias = if (clock.hasFullBiasNanos()) clock.fullBiasNanos else 0L
                val bias = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
                val gpsTimeNanos = clock.timeNanos - (fullBias + bias).toLong()
                val drift = if (clock.hasDriftNanosPerSecond()) clock.driftNanosPerSecond else 0.0

                val measurements = event.measurements.map { m ->
                    GnssMeasurementSample(
                        svid = m.svid,
                        constellation = Constellation.fromAndroid(m.constellationType),
                        cn0DbHz = m.cn0DbHz,
                        receivedSvTimeNanos = m.receivedSvTimeNanos,
                        pseudorangeRateMetersPerSecond = m.pseudorangeRateMetersPerSecond,
                        accumulatedDeltaRangeMeters = m.accumulatedDeltaRangeMeters,
                        adrState = AdrState(
                            valid = m.accumulatedDeltaRangeState and 0x1 != 0,
                            resetDetected = m.accumulatedDeltaRangeState and 0x2 != 0,
                            cycleSlipDetected = m.accumulatedDeltaRangeState and 0x4 != 0,
                        ),
                        carrierFrequencyHz = if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz else 0f,
                        multipathIndicator = m.multipathIndicator,
                    )
                }
                out.onGnssRaw(
                    GnssRawEpoch(
                        tBoot = tBoot,
                        timeNanos = clock.timeNanos,
                        fullBiasNanos = fullBias,
                        biasNanos = bias,
                        driftNanosPerSecond = drift,
                        gpsTimeNanos = gpsTimeNanos,
                        hardwareClockDiscontinuityCount = clock.hardwareClockDiscontinuityCount,
                        measurements = measurements,
                    ),
                )
            }

            override fun onStatusChanged(status: Int) {
                rawSupported = status == STATUS_READY
                if (!rawSupported) {
                    Timber.w("GNSS raw measurements not available (status=$status); degrading gracefully")
                }
            }
        }
        measurementsCallback = cb
        try {
            val registered = locationManager.registerGnssMeasurementsCallback(cb, handler)
            rawSupported = registered
            if (!registered) Timber.w("Device rejected GNSS raw measurements registration")
        } catch (e: SecurityException) {
            Timber.w(e, "Location permission absent; GNSS raw measurements disabled")
        }
    }

    fun stop() {
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Throwable) {}
        measurementsCallback?.let {
            try { locationManager.unregisterGnssMeasurementsCallback(it) } catch (_: Throwable) {}
        }
        measurementsCallback = null
        statusCallback?.let {
            try { locationManager.unregisterGnssStatusCallback(it) } catch (_: Throwable) {}
        }
        statusCallback = null
        rawActive = false
        thread?.quitSafely(); thread = null
        handler = null
    }
}
