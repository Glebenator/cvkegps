package com.example.cvkegps.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.hardware.GeomagneticField
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.location.LocationCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

class AndroidGpsSource(context: Context) : GpsSource {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission") // Precise permission is checked before registration.
    override fun updates(): Flow<GpsEvent> = callbackFlow {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            trySend(GpsEvent.PermissionRequired)
            close()
            return@callbackFlow
        }
        if (manager == null || LocationManager.GPS_PROVIDER !in manager.allProviders) {
            trySend(GpsEvent.Unavailable)
            close()
            return@callbackFlow
        }

        var receivedCount = 0L
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                receivedCount += 1
                trySend(GpsEvent.Fix(location.toReading(), receivedCount))
            }

            override fun onProviderEnabled(provider: String) {
                trySend(GpsEvent.Searching)
            }

            override fun onProviderDisabled(provider: String) {
                trySend(GpsEvent.Disabled)
            }
        }

        try {
            trySend(
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) GpsEvent.Searching
                else GpsEvent.Disabled,
            )
            val request = LocationRequestCompat.Builder(UPDATE_INTERVAL_MILLIS)
                .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MILLIS)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .build()
            LocationManagerCompat.requestLocationUpdates(
                manager,
                LocationManager.GPS_PROVIDER,
                request,
                ContextCompat.getMainExecutor(appContext),
                listener,
            )
        } catch (_: SecurityException) {
            trySend(GpsEvent.PermissionRequired)
        } catch (_: IllegalArgumentException) {
            trySend(GpsEvent.Error)
        }

        awaitClose { manager.removeUpdates(listener) }
    }.conflate()

    internal fun Location.toReading(): GpsReading {
        val accuracy = accuracy.takeIf { hasAccuracy() && it.isFinite() && it >= 0 }
        // Reject poor fixes and uncertain speed. Do not derive speed from position jitter.
        val usableFix = accuracy != null && accuracy <= MAX_HORIZONTAL_ERROR_METERS
        val speedAccuracy = speedAccuracyMetersPerSecond.takeIf {
            hasSpeedAccuracy() && it.isFinite() && it >= 0
        }
        val speedIssue = when {
            !usableFix -> SpeedIssue.PositionAccuracy
            !hasSpeed() -> SpeedIssue.Missing
            !speed.isFinite() || speed < 0 -> SpeedIssue.Invalid
            hasSpeedAccuracy() && (speedAccuracy == null || speedAccuracy > MAX_SPEED_ERROR_METERS_PER_SECOND) ->
                SpeedIssue.SpeedAccuracy
            else -> null
        }
        return GpsReading(
            speedMetersPerSecond = speed.takeIf { speedIssue == null },
            altitudeMeters = altitude.takeIf { usableFix && hasAltitude() && it.isFinite() },
            accuracyMeters = accuracy,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            speedAccuracyMetersPerSecond = speedAccuracy,
            verticalAccuracyMeters = verticalAccuracyMeters.takeIf { hasVerticalAccuracy() && it.isFinite() && it >= 0 },
            bearingRadians = bearing.takeIf { hasBearing() && it.isFinite() }?.let { Math.toRadians(it.toDouble()) },
            bearingAccuracyRadians = bearingAccuracyDegrees.takeIf {
                hasBearingAccuracy() && it.isFinite() && it >= 0
            }?.let { Math.toRadians(it.toDouble()) },
            isMock = LocationCompat.isMock(this),
            speedIssue = speedIssue,
            magneticDeclinationRadians = if (usableFix && latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0
            ) Math.toRadians(GeomagneticField(latitude.toFloat(), longitude.toFloat(),
                altitude.takeIf { hasAltitude() && it.isFinite() }?.toFloat() ?: 0f,
                time).declination.toDouble()) else null,
        )
    }

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 1_000L
        const val MAX_HORIZONTAL_ERROR_METERS = 50f
        const val MAX_SPEED_ERROR_METERS_PER_SECOND = 3f
    }
}
