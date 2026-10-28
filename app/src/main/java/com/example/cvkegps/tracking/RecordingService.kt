package com.example.cvkegps.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.cvkegps.MainActivity
import com.example.cvkegps.R
import com.example.cvkegps.ui.DisplayFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class RecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder get() = (application as TrackingApplication).recorder
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = when (intent?.action) {
            START -> intent.getStringExtra(MODE)?.let { mode -> TripMode.entries.find { it.name == mode } }
                ?.let { RecordingCommand.Start(it, intent.getBooleanExtra(AUTOMATIC, true)) }
            RESUME -> intent.getStringExtra(TRIP)?.let { RecordingCommand.Resume(it, intent.getBooleanExtra(REOPEN, false)) }
            PAUSE -> RecordingCommand.Pause
            STOP -> RecordingCommand.Finish(intent.getStringExtra(TRIP))
            else -> null
        }
        if (command == null) { stopSelf(startId); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW))
        try {
            val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            if (permission != PackageManager.PERMISSION_GRANTED) {
                recorder.send(RecordingCommand.Interrupt(RecordingError.Access))
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ServiceCompat.startForeground(this, NOTIFICATION, notification(recorder.state.value, false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: RuntimeException) {
            recorder.send(RecordingCommand.Interrupt(RecordingError.Service))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        recorder.send(command)
        if (observer == null) observer = serviceScope.launch {
            var shown: Shown? = null
            // A new state cancels the wait for the signal-loss time, because a new fix can move that time.
            recorder.state.collectLatest { state ->
                if (!state.needsService) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                while (true) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    val key = Key(state.trip?.id, state.trip?.status, state.segmentStartNanos, state.signalLost(now))
                    val values = values(state)
                    val last = shown
                    // State changes show at once. New values show at most every 30 seconds, so the notification does not update with each fix.
                    if (last == null || key != last.key || (values != last.values && now - last.atNanos >= VALUE_INTERVAL_NANOS)) {
                        manager.notify(NOTIFICATION, notification(state, key.signalLost))
                        shown = Shown(key, values, now)
                    }
                    val lostAt = state.signalLostAtNanos?.takeIf { it > now } ?: break
                    delay((lostAt - now) / 1_000_000 + 1)
                }
            }
        }
        return START_NOT_STICKY
    }

    private data class Key(val id: String?, val status: TripStatus?, val segmentStart: Long?, val signalLost: Boolean)
    private data class Shown(val key: Key, val values: String?, val atNanos: Long)

    /** Distance and average speed, for example "4.21 km · 38 km/h average". Null before the trip has a distance. */
    private fun values(state: RecordingState): String? {
        val trip = state.trip ?: return null
        val distance = trip.distanceMeters ?: return null
        val format = displayFormat()
        val average = averageSpeed(distance, trip.durationNanos)
        return if (average == null) format.distance(distance)
        else getString(R.string.recording_notification_values, format.distance(distance), format.speed(average))
    }

    /**
     * The notification has three levels. Android 17 shows a metric card. Android 16 QPR1 and later can promote it
     * to a Live Update with a status bar chip. Earlier versions show the same values as text.
     * Current speed is not shown, because a notification cannot reliably remove an old speed.
     */
    private fun notification(state: RecordingState, signalLost: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra(OPEN_TRIPS, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Android ignores the request where Live Updates do not exist, or where the user turned them off.
            .setRequestPromotedOngoing(true)
        // Android shows the app name. While recording, the system chronometer counts the time without notification updates.
        val segmentStart = state.segmentStartNanos
        val format = displayFormat()
        val values = values(state)
        val paused = state.trip?.status == TripStatus.Paused
        var elapsedMillis: Long? = null
        if (paused) {
            builder.setContentTitle(getString(R.string.recording_notification_paused)).setShowWhen(false)
                .setShortCriticalText(getString(R.string.trip_paused))
                .setContentText(listOfNotNull(getString(R.string.recording_notification_paused_time,
                    format.duration(state.trip.durationNanos)), values).joinToString(SEPARATOR))
        } else {
            builder.setContentTitle(getString(R.string.recording_notification_title))
                .setContentText(listOfNotNull(getString(R.string.trip_no_signal).takeIf { signalLost }, values)
                    .joinToString(SEPARATOR).ifEmpty { null })
            if (state.trip != null && segmentStart != null) {
                elapsedMillis = ((state.segmentBaseDurationNanos + SystemClock.elapsedRealtimeNanos() - segmentStart) / 1_000_000).coerceAtLeast(0)
                builder.setWhen(System.currentTimeMillis() - elapsedMillis).setUsesChronometer(true).setShowWhen(true)
            } else builder.setShowWhen(false)
        }
        if (signalLost) builder.setSubText(getString(R.string.trip_no_signal))
        if (state.trip != null) {
            val action = Intent(this, RecordingService::class.java).setAction(if (paused) RESUME else PAUSE).putExtra(TRIP, state.trip.id)
            builder.addAction(0, getString(if (paused) R.string.trip_resume else R.string.trip_pause),
                PendingIntent.getService(this, 1, action, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            builder.addAction(0, getString(R.string.trip_stop), PendingIntent.getService(this, 2,
                Intent(this, RecordingService::class.java).setAction(STOP).putExtra(TRIP, state.trip.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        val notification = builder.build()
        val trip = state.trip ?: return notification
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) metricCard(notification, trip, elapsedMillis) else notification
    }

    /** Android 17: time, distance, and average speed in the system metric layout. The other notification content stays. */
    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun metricCard(base: Notification, trip: TripRecord, elapsedMillis: Long?): Notification {
        val time = if (elapsedMillis != null) {
            Notification.Metric.TimeDifference.forStopwatch(Instant.ofEpochMilli(System.currentTimeMillis() - elapsedMillis),
                Notification.Metric.TimeDifference.FORMAT_CHRONOMETER)
        } else Notification.Metric.TimeDifference.forPausedStopwatch(Duration.ofNanos(trip.durationNanos),
            Notification.Metric.TimeDifference.FORMAT_CHRONOMETER)
        val format = displayFormat()
        val meters = trip.distanceMeters
        val distance = meters?.let(format::distanceAmount)?.metric() ?: Notification.Metric.FixedText(getString(R.string.no_value))
        val average = averageSpeed(meters, trip.durationNanos)?.let(format::speedAmount)?.metric()
            ?: Notification.Metric.FixedText(getString(R.string.no_value))
        return Notification.Builder.recoverBuilder(this, base).setStyle(Notification.MetricStyle()
            .addMetric(Notification.Metric(time, getString(R.string.recording_metric_time)))
            .addMetric(Notification.Metric(distance, getString(R.string.trip_distance)))
            .addMetric(Notification.Metric(average, getString(R.string.recording_metric_average)))).build()
    }

    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun DisplayFormat.Amount.metric(): Notification.Metric.MetricValue =
        if (decimals == 0) Notification.Metric.FixedInt(value.toInt(), unit) else Notification.Metric.FixedFloat(value.toFloat(), unit, decimals, decimals)

    /** The units that the user chose. The next notification update shows a change. */
    private fun displayFormat(): DisplayFormat =
        DisplayFormat(resources, (application as TrackingApplication).settings.values.value.units(resources.configuration.locales[0].country))

    override fun onDestroy() {
        recorder.serviceLost()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val START = "com.example.cvkegps.START_TRIP"
        const val RESUME = "com.example.cvkegps.RESUME_TRIP"
        const val PAUSE = "com.example.cvkegps.PAUSE_TRIP"
        const val STOP = "com.example.cvkegps.STOP_TRIP"
        const val MODE = "mode"
        const val AUTOMATIC = "automatic"
        const val TRIP = "trip"
        const val REOPEN = "reopen"
        const val OPEN_TRIPS = "open_trips"
        const val CHANNEL = "trip_recording"
        private const val NOTIFICATION = 100
        private const val VALUE_INTERVAL_NANOS = 30_000_000_000L
        private const val SEPARATOR = " · "
    }
}
