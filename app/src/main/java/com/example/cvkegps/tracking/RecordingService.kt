package com.example.cvkegps.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.cvkegps.MainActivity
import com.example.cvkegps.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder get() = (application as TrackingApplication).recorder
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = when (intent?.action) {
            START -> intent.getStringExtra(MODE)?.let { mode -> TripMode.entries.find { it.name == mode } }?.let(RecordingCommand::Start)
            RESUME -> intent.getStringExtra(TRIP)?.let(RecordingCommand::Resume)
            PAUSE -> RecordingCommand.Pause
            STOP -> RecordingCommand.Finish(intent.getStringExtra(TRIP))
            else -> null
        }
        if (command == null) { stopSelf(startId); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW))
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                recorder.send(RecordingCommand.Interrupt(RecordingError.Access))
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ServiceCompat.startForeground(this, NOTIFICATION, notification(recorder.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: RuntimeException) {
            recorder.send(RecordingCommand.Interrupt(RecordingError.Service))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        recorder.send(command)
        if (observer == null) observer = serviceScope.launch {
            var previous: Pair<String?, TripStatus?>? = null
            recorder.state.collect { state ->
                if (!state.needsService) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val key = state.trip?.id to state.trip?.status
                    if (key != previous) {
                        manager.notify(NOTIFICATION, notification(state))
                        previous = key
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(state: RecordingState): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra(OPEN_TRIPS, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(if (state.trip?.status == TripStatus.Paused) R.string.trip_paused else R.string.trip_recording))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (state.trip != null) {
            val paused = state.trip.status == TripStatus.Paused
            val action = Intent(this, RecordingService::class.java).setAction(if (paused) RESUME else PAUSE).putExtra(TRIP, state.trip.id)
            builder.addAction(0, getString(if (paused) R.string.trip_resume else R.string.trip_pause),
                PendingIntent.getService(this, 1, action, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            builder.addAction(0, getString(R.string.trip_stop), PendingIntent.getService(this, 2,
                Intent(this, RecordingService::class.java).setAction(STOP).putExtra(TRIP, state.trip.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

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
        const val TRIP = "trip"
        const val OPEN_TRIPS = "open_trips"
        private const val CHANNEL = "trip_recording"
        private const val NOTIFICATION = 100
    }
}
