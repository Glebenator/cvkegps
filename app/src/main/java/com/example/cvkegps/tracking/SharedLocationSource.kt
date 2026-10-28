package com.example.cvkegps.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.example.cvkegps.location.AndroidSeaLevel
import com.example.cvkegps.location.FixInterpreter
import com.example.cvkegps.location.FixSource
import com.example.cvkegps.location.GpsEvent
import com.example.cvkegps.location.GpsSource
import com.example.cvkegps.location.InterpretedFix
import com.example.cvkegps.location.TrackSample
import com.example.cvkegps.location.androidDeclination
import com.example.cvkegps.location.seaLevelAltitudeSupported
import com.example.cvkegps.location.toRawFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/**
 * Main-thread ownership. Route delivery precedes the conflated display state.
 * [scope] runs on the main thread. It adds the sea-level altitude to the displayed reading off the main thread.
 *
 * The Speed tab and a recording trip share one GPS request. The request uses the strictest need of the active features:
 * a visible screen needs single fixes, and a trip with [RecordingOptions.highAccuracy] needs the fastest fixes.
 * Network positions go only to a recording trip. The Speed tab never shows them.
 */
class SharedLocationSource(context: Context, private val scope: CoroutineScope) : GpsSource {
    private val context = context.applicationContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private val interpreter = FixInterpreter(::androidDeclination)
    private val seaLevel: ((Location) -> Double?)? = if (seaLevelAltitudeSupported) AndroidSeaLevel(context)::altitude else null
    // The geoid model reads from disk. Conversions run one at a time, so their results arrive in order.
    private val conversion = Dispatchers.IO.limitedParallelism(1)
    private val main = Handler(Looper.getMainLooper())
    // Changes when the request stops, so a conversion that ends later does not show a reading from the old request.
    private var request = 0
    private val latest = MutableStateFlow<GpsEvent>(GpsEvent.Searching)
    private val estimator = ActivityEstimator()
    private val estimate = MutableStateFlow(ActivityEstimate())
    val activityEstimate = estimate.asStateFlow()
    private var displayCount = 0
    private var foreground = false
    private var recordingOwned = false
    private var paused = false
    private var options = RecordingOptions()
    private var sink: ((List<TrackSample>) -> Unit)? = null
    private var accessLost: (() -> Unit)? = null
    private var registered = false
    // The GPS request that is registered. Null without a request.
    private var profile: Profile? = null
    private var received = 0L
    private var latestNanos = 0L
    // True while the recorder waits for GPS fixes and can use network positions after a signal gap.
    private var watching = false
    private var networkRegistered = false
    private var fullTracking: GnssMeasurementsEvent.Callback? = null

    /** The GPS request. [batch] lets Android deliver fixes in groups. */
    private data class Profile(val intervalMillis: Long, val minIntervalMillis: Long, val batch: Boolean)

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = deliver(listOf(location))
        override fun onLocationChanged(locations: List<Location>) = deliver(locations)
        override fun onProviderDisabled(provider: String) { clearEstimate(); latest.value = GpsEvent.Disabled }
        override fun onProviderEnabled(provider: String) { latest.value = GpsEvent.Searching }
    }

    private val networkListener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = deliverNetwork(listOf(location))
        override fun onLocationChanged(locations: List<Location>) = deliverNetwork(locations)
        // The GPS listener reports the location setting. The network provider does not change the GPS state.
        override fun onProviderDisabled(provider: String) = Unit
        override fun onProviderEnabled(provider: String) = Unit
    }

    private val gpsMissing = Runnable { startNetwork() }

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

    fun recording(onBatch: (List<TrackSample>) -> Unit, onAccessLost: () -> Unit, options: RecordingOptions = RecordingOptions()) {
        recordingOwned = true
        paused = false
        sink = onBatch
        accessLost = onAccessLost
        this.options = options
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
        options = RecordingOptions()
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
                request++
            }
            registered = false
            profile = null
            updateRecordingExtras()
            latest.value = if (paused) GpsEvent.Paused else if (!permitted) GpsEvent.PermissionRequired else GpsEvent.Searching
            if (wanted && !permitted) accessLost?.invoke()
            return
        }
        val high = recordingOwned && options.highAccuracy
        // Only a trip that records without a visible app can wait for grouped fixes. Android groups fixes at most once
        // each second, so a high accuracy trip receives each fix at once.
        val next = if (high) Profile(HIGH_INTERVAL_MILLIS, 0L, batch = false)
            else Profile(1_000L, 1_000L, batch = !foreground && displayCount == 0)
        if (registered && profile == next) return updateRecordingExtras()
        if (manager == null || LocationManager.GPS_PROVIDER !in manager.allProviders) {
            latest.value = GpsEvent.Unavailable
            accessLost?.invoke()
            return
        }
        try {
            // A change of request replaces it. The display keeps its state.
            if (!registered) {
                received = 0
                latestNanos = 0
                latest.value = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) GpsEvent.Searching else GpsEvent.Disabled
            }
            LocationManagerCompat.requestLocationUpdates(manager, LocationManager.GPS_PROVIDER,
                LocationRequestCompat.Builder(next.intervalMillis).setMinUpdateIntervalMillis(next.minIntervalMillis)
                    .setMaxUpdateDelayMillis(if (next.batch) BATCH_DELAY_MILLIS else 0L)
                    .setMinUpdateDistanceMeters(0f).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build(),
                ContextCompat.getMainExecutor(context), listener)
            if (!registered) ContextCompat.registerReceiver(context, locationSetting,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            profile = next
        } catch (_: SecurityException) {
            latest.value = GpsEvent.PermissionRequired
            accessLost?.invoke()
        } catch (_: IllegalArgumentException) {
            latest.value = GpsEvent.Error
            accessLost?.invoke()
        }
        updateRecordingExtras()
    }

    /** Starts or stops the parts that only a recording trip uses: the GPS signal watch and full GNSS tracking. */
    private fun updateRecordingExtras() {
        val recordingNow = registered && recordingOwned && !paused && sink != null
        val watch = recordingNow && options.networkFallback
        if (watch && !watching) {
            watching = true
            main.postDelayed(gpsMissing, RouteMath.SIGNAL_GAP_NANOS / 1_000_000)
        } else if (!watch && watching) {
            watching = false
            main.removeCallbacks(gpsMissing)
            stopNetwork()
        }
        updateFullTracking(recordingNow && options.highAccuracy)
    }

    /**
     * Full tracking stops the receiver's duty cycling. Android applies it to all apps while any app asks for it,
     * and it uses more battery. The app does not use the raw measurements, so it ignores them.
     */
    @SuppressLint("MissingPermission") // Only a registered GPS request, which needs precise location, asks for it.
    private fun updateFullTracking(wanted: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager == null) return
        val current = fullTracking
        if (wanted && current == null) {
            val callback = object : GnssMeasurementsEvent.Callback() {}
            try {
                if (manager.registerGnssMeasurementsCallback(GnssMeasurementRequest.Builder().setFullTracking(true).build(),
                        IGNORE, callback)) fullTracking = callback
            } catch (_: SecurityException) {
                // The GPS request reports missing access.
            }
        } else if (!wanted && current != null) {
            manager.unregisterGnssMeasurementsCallback(current)
            fullTracking = null
        }
    }

    /** No GPS fix arrived for [RouteMath.SIGNAL_GAP_NANOS]. Ask for network positions until the next GPS fix. */
    @SuppressLint("MissingPermission") // The watch runs only with a registered GPS request.
    private fun startNetwork() {
        if (!watching || networkRegistered || manager == null) return
        if (LocationManager.NETWORK_PROVIDER !in manager.allProviders || !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return
        try {
            LocationManagerCompat.requestLocationUpdates(manager, LocationManager.NETWORK_PROVIDER,
                LocationRequestCompat.Builder(NETWORK_INTERVAL_MILLIS).setMinUpdateIntervalMillis(NETWORK_INTERVAL_MILLIS)
                    .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY).build(),
                ContextCompat.getMainExecutor(context), networkListener)
            networkRegistered = true
        } catch (_: SecurityException) {
            // The GPS request reports missing access.
        } catch (_: IllegalArgumentException) {
            // The trip continues with a gap.
        }
    }

    private fun stopNetwork() {
        if (!networkRegistered) return
        manager?.removeUpdates(networkListener)
        networkRegistered = false
    }

    private fun deliver(locations: List<Location>) {
        if (!registered) return
        if (watching) {
            // A GPS fix ends the network positions. A new gap starts the wait again.
            main.removeCallbacks(gpsMissing)
            stopNetwork()
            main.postDelayed(gpsMissing, RouteMath.SIGNAL_GAP_NANOS / 1_000_000)
        }
        val now = SystemClock.elapsedRealtimeNanos()
        // One interpretation per fix. The recorder and the display therefore use the same usability rules.
        val fixes = locations.map { interpreter.interpret(it.toRawFix(now)) }
        val samples = fixes.map { it.sample }
        samples.sortedBy { it.elapsedNanos }.forEach { estimator.accept(it)?.let { value -> estimate.value = value } }
        sink?.invoke(samples)
        received += locations.size
        val count = received
        val (fix, location) = fixes.zip(locations).maxByOrNull { it.first.sample.elapsedNanos } ?: return
        if (seaLevel == null || fix.reading.altitudeMeters == null) return show(fix, count)
        // The reading shows when its sea-level altitude is known, so the altitude does not change between two values.
        val id = request
        val copy = Location(location)
        scope.launch {
            val meters = withContext(conversion) { seaLevel(copy) }
            if (registered && id == request) show(fix.copy(reading = interpreter.withSeaLevel(fix.reading, meters)), count)
        }
    }

    /** Network positions go only to the recording trip. They have no speed, so the display and the estimate do not use them. */
    private fun deliverNetwork(locations: List<Location>) {
        if (!networkRegistered) return
        val now = SystemClock.elapsedRealtimeNanos()
        sink?.invoke(locations.map { interpreter.interpret(it.toRawFix(now, FixSource.Network)).sample })
    }

    private fun show(fix: InterpretedFix, count: Long) {
        if (fix.sample.elapsedNanos < latestNanos) return
        latestNanos = fix.sample.elapsedNanos
        latest.value = GpsEvent.Fix(fix.reading, count)
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
        /** A high accuracy trip asks for 10 fixes each second. Most phones supply fewer. Android sends the ones that it has. */
        const val HIGH_INTERVAL_MILLIS = 100L
        const val NETWORK_INTERVAL_MILLIS = 5_000L
        /** Raw GNSS measurements are not used. Full tracking needs a listener, so the events go nowhere. */
        val IGNORE = Executor { }
    }
}
