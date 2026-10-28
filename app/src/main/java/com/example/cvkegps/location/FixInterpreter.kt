package com.example.cvkegps.location

import kotlin.math.PI

/** Receiver values before the usability rules. Null means not reported. A reported value can be NaN or negative. */
data class RawFix(
    val latitudeRadians: Double,
    val longitudeRadians: Double,
    val timeMillis: Long,
    val elapsedNanos: Long,
    val receivedElapsedNanos: Long,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
    val altitudeMeters: Double? = null,
    val verticalAccuracyMeters: Float? = null,
    val bearingRadians: Double? = null,
    val bearingAccuracyRadians: Double? = null,
    val isMock: Boolean = false,
    val source: FixSource = FixSource.Gps,
    val satellitesUsed: Int? = null,
)

/** The stored sample and the displayed reading of one fix. */
data class InterpretedFix(val sample: TrackSample, val reading: GpsReading)

/**
 * Applies the usability rules to each fix once, so the Speed tab and the trip use the same speed.
 * [declination] returns radians. It is called only for a usable position.
 */
class FixInterpreter(private val declination: (TrackSample) -> Double) {
    fun interpret(raw: RawFix): InterpretedFix {
        val speedAccuracy = raw.speedAccuracyMetersPerSecond.nonNegative()
        // A stored sample cannot show that the accuracy was reported. The speed is therefore stored as missing (ADR 0001).
        val speedAccuracyInvalid = raw.speedAccuracyMetersPerSecond != null && speedAccuracy == null
        // A network position has no measured motion or height. Android can add values to it, so they are not stored.
        val gps = raw.source == FixSource.Gps
        val sample = TrackSample(
            raw.latitudeRadians, raw.longitudeRadians, raw.timeMillis, raw.elapsedNanos, raw.receivedElapsedNanos,
            raw.accuracyMeters.nonNegative(),
            raw.speedMetersPerSecond.nonNegative()?.takeIf { gps && !speedAccuracyInvalid },
            speedAccuracy?.takeIf { gps },
            raw.altitudeMeters?.takeIf { gps && it.isFinite() },
            raw.verticalAccuracyMeters.nonNegative()?.takeIf { gps },
            raw.bearingRadians?.takeIf { gps && it.isFinite() },
            raw.bearingAccuracyRadians?.takeIf { gps && it.isFinite() && it >= 0 },
            raw.isMock,
            raw.source,
            raw.satellitesUsed?.takeIf { it >= 0 },
        )
        val speed = sample.usableSpeed
        val rawSpeed = raw.speedMetersPerSecond?.takeIf { gps }
        val issue = when {
            !sample.usablePosition -> SpeedIssue.PositionAccuracy
            rawSpeed == null -> SpeedIssue.Missing
            !rawSpeed.isFinite() || rawSpeed < 0 -> SpeedIssue.Invalid
            speed == null -> SpeedIssue.SpeedAccuracy
            else -> null
        }
        val coordinates = sample.latitudeRadians in -PI / 2..PI / 2 && sample.longitudeRadians in -PI..PI
        return InterpretedFix(sample, GpsReading(
            speedMetersPerSecond = speed,
            altitudeMeters = sample.altitudeMeters?.takeIf { sample.usablePosition },
            accuracyMeters = sample.accuracyMeters,
            elapsedRealtimeNanos = sample.elapsedNanos,
            speedAccuracyMetersPerSecond = sample.speedAccuracyMetersPerSecond,
            verticalAccuracyMeters = sample.verticalAccuracyMeters,
            bearingRadians = sample.bearingRadians,
            bearingAccuracyRadians = sample.bearingAccuracyRadians,
            isMock = sample.isMock,
            speedIssue = issue,
            magneticDeclinationRadians = if (sample.usablePosition && coordinates) declination(sample) else null,
        ))
    }

    /** Adds the sea-level altitude of the same fix. The reading shows it only when it also shows the GPS height. */
    fun withSeaLevel(reading: GpsReading, meters: Double?): GpsReading =
        reading.copy(seaLevelAltitudeMeters = meters?.takeIf { it.isFinite() && reading.altitudeMeters != null })

    private fun Float?.nonNegative() = this?.takeIf { it.isFinite() && it >= 0 }
}
