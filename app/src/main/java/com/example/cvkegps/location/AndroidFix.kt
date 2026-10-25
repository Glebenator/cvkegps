package com.example.cvkegps.location

import android.hardware.GeomagneticField
import android.location.Location
import androidx.core.location.LocationCompat

/** Copies an Android fix without the usability rules. [FixInterpreter] applies them. */
fun Location.toRawFix(receivedElapsedNanos: Long) = RawFix(
    Math.toRadians(latitude), Math.toRadians(longitude), time, elapsedRealtimeNanos, receivedElapsedNanos,
    accuracyMeters = accuracy.takeIf { hasAccuracy() },
    speedMetersPerSecond = speed.takeIf { hasSpeed() },
    speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond.takeIf { hasSpeedAccuracy() },
    altitudeMeters = altitude.takeIf { hasAltitude() },
    verticalAccuracyMeters = verticalAccuracyMeters.takeIf { hasVerticalAccuracy() },
    bearingRadians = if (hasBearing()) Math.toRadians(bearing.toDouble()) else null,
    bearingAccuracyRadians = if (hasBearingAccuracy()) Math.toRadians(bearingAccuracyDegrees.toDouble()) else null,
    isMock = LocationCompat.isMock(this),
)

/** Magnetic declination from Android's world magnetic model, in radians. */
fun androidDeclination(sample: TrackSample): Double = Math.toRadians(GeomagneticField(
    Math.toDegrees(sample.latitudeRadians).toFloat(), Math.toDegrees(sample.longitudeRadians).toFloat(),
    sample.altitudeMeters?.toFloat() ?: 0f, sample.timeMillis,
).declination.toDouble())
