package com.example.cvkegps.location

/** The receiver that measured a fix. Only [Gps] fixes give speed, direction, and height. */
enum class FixSource { Gps, Network }

/** One fix as a trip stores it. Linear measurements use SI units. Angular measurements use radians. */
data class TrackSample(
    val latitudeRadians: Double,
    val longitudeRadians: Double,
    val timeMillis: Long,
    val elapsedNanos: Long,
    val receivedElapsedNanos: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val altitudeMeters: Double?,
    val verticalAccuracyMeters: Float?,
    val bearingRadians: Double?,
    val bearingAccuracyRadians: Double?,
    val isMock: Boolean,
    val source: FixSource = FixSource.Gps,
    /** The number of satellites that the receiver used for this fix. Null when the receiver does not report it. */
    val satellitesUsed: Int? = null,
) {
    fun valid(): Boolean = latitudeRadians.isFinite() && longitudeRadians.isFinite() &&
        latitudeRadians in -Math.PI / 2..Math.PI / 2 && longitudeRadians in -Math.PI..Math.PI &&
        timeMillis > 0 && elapsedNanos > 0 && elapsedNanos <= receivedElapsedNanos

    /** The usability rules for the Speed tab, the recorder, and saved trips. See GLOSSARY.md. */
    val usablePosition: Boolean get() = accuracyMeters?.let { it.isFinite() && it in 0f..MAX_HORIZONTAL_ERROR_METERS } == true
    val usableSpeed: Float? get() = speedMetersPerSecond?.takeIf { speed ->
        source == FixSource.Gps && usablePosition && speed.isFinite() && speed >= 0 &&
            (speedAccuracyMetersPerSecond == null || speedAccuracyMetersPerSecond in 0f..MAX_SPEED_ERROR_METERS_PER_SECOND)
    }
}

// File-level constants, so Room does not see them as fields of the embedded sample.
private const val MAX_HORIZONTAL_ERROR_METERS = 50f
private const val MAX_SPEED_ERROR_METERS_PER_SECOND = 3f
