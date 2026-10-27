package com.example.cvkegps.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.example.cvkegps.location.FixInterpreter
import com.example.cvkegps.location.GpsEvent
import com.example.cvkegps.location.GpsSource
import com.example.cvkegps.location.TrackSample
import com.example.cvkegps.location.androidDeclination
import com.example.cvkegps.location.toRawFix
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
    private val interpreter = FixInterpreter(::androidDeclination)
    private val latest = MutableStateFlow<GpsEvent>(GpsEvent.Searching)
    private val estimator = ActivityEstimator()
    private val estimate = MutableStateFlow(ActivityEstimate())
    val activityEstimate = estimate.asStateFlow()
    private var displayCount = 0
    private var foreground = false
    private var recordingOwned = false
    private var paused = false
    private var sink: ((List<TrackSample>) -> Unit)? = null
    private var accessLost: (() -> Unit)? = null
    private var registered = false
    // True when the current request lets Android deliver fixes in groups. Null without a request.
    private var batched: Boolean? = null
    private var received = 0L
    private var latestNanos = 0L

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = deliver(listOf(location))
        override fun onLocationChanged(locations: List<Location>) = deliver(locations)
        override fun onProviderDisabled(provider: String) { clearEstimate(); latest.value = GpsEvent.Disabled }
        override fun onProviderEnabled(provider: String) { latest.value = GpsEvent.Searching }
    }

    // A request stays registered while the app is visible. Android does not always report location turned on again to it,
    // so the source reads the setting again when it changes.
    private val locationSetting = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!registered) return
            val enabled = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            if (!enabled) {
                clearEstimate()
                latest.value = GpsEvent.Disabled
            } else if (latest.value == GpsEvent.Disabled) latest.value = GpsEvent.Searching
        }
    }

    override fun updates(): Flow<GpsEvent> = flow {
        withContext(Dispatchers.Main.immediate) { displayCount++; refresh() }
        try { emitAll(latest) }
        finally { withContext(NonCancellable + Dispatchers.Main.immediate) { displayCount--; refresh() } }
    }

    /** True while the app is visible. GPS then stays on for all screens, and fixes arrive one by one. */
    fun setForeground(visible: Boolean) {
        foreground = visible
        refresh()
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
        val wanted = !paused && (recordingOwned || displayCount > 0 || foreground)
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!wanted || !permitted) {
            clearEstimate()
            if (registered) {
                manager?.removeUpdates(listener)
                context.unregisterReceiver(locationSetting)
            }
            registered = false
            batched = null
            latest.value = if (paused) GpsEvent.Paused else if (!permitted) GpsEvent.PermissionRequired else GpsEvent.Searching
            if (wanted && !permitted) accessLost?.invoke()
            return
        }
        // Only a trip that records without a visible app can wait for grouped fixes.
        val batch = !foreground && displayCount == 0
        if (registered && batched == batch) return
        if (manager == null || LocationManager.GPS_PROVIDER !in manager.allProviders) {
            latest.value = GpsEvent.Unavailable
            accessLost?.invoke()
            return
        }
        try {
            // A change between single and grouped delivery replaces the request. The display keeps its state.
            if (!registered) {
                received = 0
                latestNanos = 0
                latest.value = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) GpsEvent.Searching else GpsEvent.Disabled
            }
            LocationManagerCompat.requestLocationUpdates(manager, LocationManager.GPS_PROVIDER,
                LocationRequestCompat.Builder(1_000L).setMinUpdateIntervalMillis(1_000L)
                    .setMaxUpdateDelayMillis(if (batch) BATCH_DELAY_MILLIS else 0L)
                    .setMinUpdateDistanceMeters(0f).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build(),
                ContextCompat.getMainExecutor(context), listener)
            if (!registered) ContextCompat.registerReceiver(context, locationSetting,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            batched = batch
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
        // One interpretation per fix. The recorder and the display therefore use the same usability rules.
        val fixes = locations.map { interpreter.interpret(it.toRawFix(now)) }
        val samples = fixes.map { it.sample }
        samples.sortedBy { it.elapsedNanos }.forEach { estimator.accept(it)?.let { value -> estimate.value = value } }
        sink?.invoke(samples)
        received += locations.size
        fixes.maxByOrNull { it.sample.elapsedNanos }?.let { fix ->
            if (fix.sample.elapsedNanos >= latestNanos) {
                latestNanos = fix.sample.elapsedNanos
                latest.value = GpsEvent.Fix(fix.reading, received)
            }
        }
    }

    private fun clearEstimate() {
        estimator.reset()
        estimate.value = ActivityEstimate()
    }

    private companion object {
        /**
         * The longest wait for a group of fixes. Android can keep the processor asleep between groups. A receiver can ignore it.
         * It is shorter than [RouteMath.SIGNAL_GAP_NANOS], so a group that arrives late does not show as lost signal.
         */
        const val BATCH_DELAY_MILLIS = 5_000L
    }
}
