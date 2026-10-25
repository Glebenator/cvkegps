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
        val sample = TrackSample(
            raw.latitudeRadians, raw.longitudeRadians, raw.timeMillis, raw.elapsedNanos, raw.receivedElapsedNanos,
            raw.accuracyMeters.nonNegative(),
            raw.speedMetersPerSecond.nonNegative()?.takeIf { !speedAccuracyInvalid },
            speedAccuracy,
            raw.altitudeMeters?.takeIf { it.isFinite() },
            raw.verticalAccuracyMeters.nonNegative(),
            raw.bearingRadians?.takeIf { it.isFinite() },
            raw.bearingAccuracyRadians?.takeIf { it.isFinite() && it >= 0 },
            raw.isMock,
        )
        val speed = sample.usableSpeed
        val rawSpeed = raw.speedMetersPerSecond
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

    private fun Float?.nonNegative() = this?.takeIf { it.isFinite() && it >= 0 }
}
