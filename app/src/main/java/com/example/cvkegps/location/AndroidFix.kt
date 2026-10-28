package com.example.cvkegps.location

import android.content.Context
import android.hardware.GeomagneticField
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.core.location.LocationCompat
import java.io.IOException

/** Copies an Android fix without the usability rules. [FixInterpreter] applies them. */
fun Location.toRawFix(receivedElapsedNanos: Long, source: FixSource = FixSource.Gps) = RawFix(
    Math.toRadians(latitude), Math.toRadians(longitude), time, elapsedRealtimeNanos, receivedElapsedNanos,
    accuracyMeters = accuracy.takeIf { hasAccuracy() },
    speedMetersPerSecond = speed.takeIf { hasSpeed() },
    speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond.takeIf { hasSpeedAccuracy() },
    altitudeMeters = altitude.takeIf { hasAltitude() },
    verticalAccuracyMeters = verticalAccuracyMeters.takeIf { hasVerticalAccuracy() },
    bearingRadians = if (hasBearing()) Math.toRadians(bearing.toDouble()) else null,
    bearingAccuracyRadians = if (hasBearingAccuracy()) Math.toRadians(bearingAccuracyDegrees.toDouble()) else null,
    isMock = LocationCompat.isMock(this),
    source = source,
    // The GNSS provider reports the number of satellites in the fix as an extra.
    satellitesUsed = extras?.getInt("satellites", -1)?.takeIf { it >= 0 },
)

/** Magnetic declination from Android's world magnetic model, in radians. */
fun androidDeclination(sample: TrackSample): Double = Math.toRadians(GeomagneticField(
    Math.toDegrees(sample.latitudeRadians).toFloat(), Math.toDegrees(sample.longitudeRadians).toFloat(),
    sample.altitudeMeters?.toFloat() ?: 0f, sample.timeMillis,
).declination.toDouble())

/** Android 14 and later can calculate the height above mean sea level from the GPS height. */
@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
val seaLevelAltitudeSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * Height above mean sea level from Android's geoid model. The first call in an area reads model data from disk,
 * so call it only off the main thread. Returns null when the fix has no GPS height or the model is not available.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class AndroidSeaLevel(context: Context) {
    private val context = context.applicationContext
    private val converter = AltitudeConverter()

    @WorkerThread
    fun altitude(location: Location): Double? {
        if (location.hasMslAltitude()) return location.mslAltitudeMeters
        if (!location.hasAltitude()) return null
        return try {
            val copy = Location(location)
            converter.addMslAltitudeToLocation(context, copy)
            copy.mslAltitudeMeters.takeIf { copy.hasMslAltitude() }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
