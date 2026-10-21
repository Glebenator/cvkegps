package com.example.cvkegps.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.example.cvkegps.location.AndroidGpsSource
import com.example.cvkegps.location.GpsEvent
import com.example.cvkegps.location.GpsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Main-thread ownership. Route delivery precedes the conflated display state. */
class SharedLocationSource(context: Context) : GpsSource {
    private val context = context.applicationContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private val converter = AndroidGpsSource(context)
    private val latest = MutableStateFlow<GpsEvent>(GpsEvent.Searching)
    private val estimator = ActivityEstimator()
    private val estimate = MutableStateFlow(ActivityEstimate())
    val activityEstimate = estimate.asStateFlow()
    private var displayCount = 0
    private var recordingOwned = false
    private var paused = false
    private var sink: ((List<TrackSample>) -> Unit)? = null
    private var accessLost: (() -> Unit)? = null
    private var registered = false
    private var received = 0L
    private var latestNanos = 0L

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = deliver(listOf(location))
        override fun onLocationChanged(locations: List<Location>) = deliver(locations)
        override fun onProviderDisabled(provider: String) { clearEstimate(); latest.value = GpsEvent.Disabled }
        override fun onProviderEnabled(provider: String) { latest.value = GpsEvent.Searching }
    }

    override fun updates(): Flow<GpsEvent> = flow {
        withContext(Dispatchers.Main.immediate) { displayCount++; refresh() }
        try { emitAll(latest) }
        finally { withContext(NonCancellable + Dispatchers.Main.immediate) { displayCount--; refresh() } }
    }

    fun recording(onBatch: (List<TrackSample>) -> Unit, onAccessLost: () -> Unit) {
        recordingOwned = true
        paused = false
        sink = onBatch
        accessLost = onAccessLost
        refresh()
    }

    fun pauseRecording() {
        recordingOwned = true
        paused = true
        sink = null
        refresh()
    }

    fun releaseRecording() {
        recordingOwned = false
        paused = false
        sink = null
        accessLost = null
        refresh()
    }

    @SuppressLint("MissingPermission") // Check the grant before each registration.
    private fun refresh() {
        val wanted = !paused && (recordingOwned || displayCount > 0)
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!wanted || !permitted) {
            clearEstimate()
            if (registered) manager?.removeUpdates(listener)
            registered = false
            latest.value = if (paused) GpsEvent.Paused else if (!permitted) GpsEvent.PermissionRequired else GpsEvent.Searching
            if (wanted && !permitted) accessLost?.invoke()
            return
        }
        if (registered) return
        if (manager == null || LocationManager.GPS_PROVIDER !in manager.allProviders) {
            latest.value = GpsEvent.Unavailable
            accessLost?.invoke()
            return
        }
        received = 0
        latestNanos = 0
        try {
            latest.value = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) GpsEvent.Searching else GpsEvent.Disabled
            LocationManagerCompat.requestLocationUpdates(manager, LocationManager.GPS_PROVIDER,
                LocationRequestCompat.Builder(1_000L).setMinUpdateIntervalMillis(1_000L)
                    .setMinUpdateDistanceMeters(0f).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build(),
                ContextCompat.getMainExecutor(context), listener)
            registered = true
        } catch (_: SecurityException) {
            latest.value = GpsEvent.PermissionRequired
            accessLost?.invoke()
        } catch (_: IllegalArgumentException) {
            latest.value = GpsEvent.Error
            accessLost?.invoke()
        }
    }

    private fun deliver(locations: List<Location>) {
        if (!registered) return
        val now = SystemClock.elapsedRealtimeNanos()
        val samples = locations.map { it.sample(now) }
        samples.sortedBy { it.elapsedNanos }.forEach { estimator.accept(it)?.let { value -> estimate.value = value } }
        sink?.invoke(samples)
        received += locations.size
        locations.maxByOrNull { it.elapsedRealtimeNanos }?.let { location ->
            if (location.elapsedRealtimeNanos >= latestNanos) {
                latestNanos = location.elapsedRealtimeNanos
                latest.value = GpsEvent.Fix(converter.run { location.toReading() }, received)
            }
        }
    }

    private fun clearEstimate() {
        estimator.reset()
        estimate.value = ActivityEstimate()
    }

    private fun Location.sample(now: Long) = TrackSample(
        Math.toRadians(latitude), Math.toRadians(longitude), time, elapsedRealtimeNanos, now,
        accuracy.takeIf { hasAccuracy() && it.isFinite() && it >= 0 },
        speed.takeIf { hasSpeed() && it.isFinite() && it >= 0 },
        speedAccuracyMetersPerSecond.takeIf { hasSpeedAccuracy() && it.isFinite() && it >= 0 },
        altitude.takeIf { hasAltitude() && it.isFinite() },
        verticalAccuracyMeters.takeIf { hasVerticalAccuracy() && it.isFinite() && it >= 0 },
        bearing.takeIf { hasBearing() && it.isFinite() }?.let { Math.toRadians(it.toDouble()) },
        bearingAccuracyDegrees.takeIf { hasBearingAccuracy() && it.isFinite() && it >= 0 }?.let { Math.toRadians(it.toDouble()) },
        LocationCompat.isMock(this),
    )
}
