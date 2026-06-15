package com.chronyx.core.model

/** GNSS constellation, mirroring `android.location.GnssStatus` constants. */
enum class Constellation {
    UNKNOWN, GPS, SBAS, GLONASS, QZSS, BEIDOU, GALILEO, IRNSS;

    companion object {
        /** Maps an `android.location.GnssStatus.CONSTELLATION_*` int to this enum. */
        fun fromAndroid(value: Int): Constellation = when (value) {
            1 -> GPS
            2 -> SBAS
            3 -> GLONASS
            4 -> QZSS
            5 -> BEIDOU
            6 -> GALILEO
            7 -> IRNSS
            else -> UNKNOWN
        }
    }
}

/**
 * A cooked GNSS fix, aligned to BOOTTIME via [tBoot]. The alignment key is the elapsed-realtime
 * instant of the fix — NOT the UTC field — because that is what shares the axis with frames and IMU.
 *
 * @param tBoot `Location.getElapsedRealtimeNanos()` — BOOTTIME of the fix.
 * @param utcMillis `Location.getTime()` — wall-clock UTC, retained only for cross-device discipline.
 * @param utcResidualNanos current (elapsedRealtime ↔ UTC) residual from the boot→UTC map, for `/gnss/fix`.
 */
data class GnssFix(
    val tBoot: Long,
    val utcMillis: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeMeters: Double,
    val horizontalAccuracyMeters: Float,
    val verticalAccuracyMeters: Float,
    val speedMps: Float,
    val bearingDeg: Float,
    val satellitesInFix: Int,
    val utcResidualNanos: Long,
)

/** Accumulated-delta-range (carrier phase) validity state bitfield, mirroring `GnssMeasurement`. */
data class AdrState(
    val valid: Boolean,
    val resetDetected: Boolean,
    val cycleSlipDetected: Boolean,
)

/**
 * A single per-satellite raw measurement within a [GnssRawEpoch]. Captures everything a downstream
 * PPP/RTK or timing consumer needs.
 */
data class GnssMeasurementSample(
    val svid: Int,
    val constellation: Constellation,
    val cn0DbHz: Double,
    val receivedSvTimeNanos: Long,
    val pseudorangeRateMetersPerSecond: Double,
    val accumulatedDeltaRangeMeters: Double,
    val adrState: AdrState,
    val carrierFrequencyHz: Float,
    val multipathIndicator: Int,
)

/**
 * A raw GNSS measurement epoch: the receiver [GnssClock] plus all per-SV measurements, aligned to
 * BOOTTIME via [tBoot]. GPS time is derived as `gpsTimeNanos = timeNanos − (fullBiasNanos + biasNanos)`.
 *
 * @param tBoot `GnssMeasurementsEvent`-paired `elapsedRealtimeNanos()` for BOOTTIME alignment.
 */
data class GnssRawEpoch(
    val tBoot: Long,
    val timeNanos: Long,
    val fullBiasNanos: Long,
    val biasNanos: Double,
    val driftNanosPerSecond: Double,
    val gpsTimeNanos: Long,
    val hardwareClockDiscontinuityCount: Int,
    val measurements: List<GnssMeasurementSample>,
)
