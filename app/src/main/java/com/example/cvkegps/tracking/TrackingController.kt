package com.example.cvkegps.tracking

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.example.cvkegps.R
import com.example.cvkegps.settings.AppSettings
import com.example.cvkegps.ui.AppNavigator
import com.example.cvkegps.ui.Destination
import com.example.cvkegps.ui.Tab

/** The Android access that recording needs. The user can change it in system settings at any time. */
data class RecordingAccess(val precise: Boolean = false, val notifications: Boolean = false, val locationOn: Boolean = false) {
    val complete: Boolean get() = precise && notifications && locationOn
}

/**
 * A start or resume that waits for access. A null [tripId] starts a new trip.
 * [reopen] continues a trip that Stop and save ended a moment ago. The recorder checks the time limit.
 */
data class PendingStart(val tripId: String? = null, val reopen: Boolean = false)

/**
 * Sends trip commands to the recording service. A start or resume first checks access.
 * The access explanation shows before the first trip, and again when access is missing. The app remembers
 * that the user read it, so it does not show again after a cold start.
 * The access dialog reads Compose state from this class, so it follows each change.
 */
@Stable
class TrackingController(
    private val activity: Activity,
    private val vm: TrackingViewModel,
    private val navigator: AppNavigator,
    private val settings: AppSettings,
    accessShown: Boolean = false,
    pending: PendingStart = PendingStart(),
    private var explained: Boolean = false,
    asked: Boolean = false,
    awaitedResume: String? = null,
) {
    var access by mutableStateOf(RecordingAccess())
        private set
    /** True while the access explanation shows. */
    var accessShown by mutableStateOf(accessShown)
        private set
    /** The command that runs when access is complete. It stays through activity recreation. */
    var pending by mutableStateOf(pending)
        private set
    /** A trip that the user resumed from its saved screen. The live map opens when the recorder resumes it. */
    var awaitedResume by mutableStateOf(awaitedResume)
        private set
    /** True after the first permission request. Android can then refuse to show its dialog, so settings are also offered. */
    var asked by mutableStateOf(asked)
        private set
    /** The trip that Stop and save ended in this screen session. The saved trip offers Resume for a short time. */
    var justSaved by mutableStateOf<String?>(null)
        private set

    val permissions: Array<String> get() = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /** Reads the current access. Call it when the screen resumes and before each command. */
    fun refresh() {
        val precise = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val channel = activity.getSystemService(NotificationManager::class.java).getNotificationChannel(RecordingService.CHANNEL)
        val notifications = NotificationManagerCompat.from(activity).areNotificationsEnabled() &&
            channel?.importance != NotificationManager.IMPORTANCE_NONE
        val locationOn = activity.getSystemService(LocationManager::class.java)?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        access = RecordingAccess(precise, notifications, locationOn)
    }

    fun start() {
        pending = PendingStart()
        if (!explained && !settings.values.value.tripAccessExplained) accessShown = true else send(RecordingService.START)
    }

    fun pause() = send(RecordingService.PAUSE)
    fun resume(id: String) = send(RecordingService.RESUME, id)
    fun stop(id: String) = send(RecordingService.STOP, id)

    /** Continues a trip that Stop and save ended a moment ago. */
    fun reopen(id: String) {
        justSaved = null
        send(RecordingService.RESUME, id, reopen = true)
    }

    fun consumeJustSaved() { justSaved = null }

    /** The user read the explanation. Start or resume the trip. */
    fun confirmAccess() {
        explained = true
        settings.setTripAccessExplained()
        val command = pending
        send(if (command.tripId == null) RecordingService.START else RecordingService.RESUME, command.tripId, command.reopen)
    }

    fun dismissAccess() {
        accessShown = false
        pending = PendingStart()
    }

    /** The recorder answered a resume from a saved trip. A refusal keeps the saved trip, which shows the reason. */
    fun resumeAnswered(state: RecordingState) {
        val id = awaitedResume ?: return
        awaitedResume = null
        if (state.trip?.id == id && state.trip.status == TripStatus.Recording && navigator.backStack.last() is Destination.SavedTrip) {
            navigator.open(Tab.Trips, Destination.LiveTrip)
        }
    }

    fun permissionRequested() {
        asked = true
    }

    fun permissionResult() {
        asked = true
        refresh()
    }

    fun openAppSettings() = activity.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri()))

    fun openNotificationSettings() = activity.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))

    fun openLocationSettings() = activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    private fun send(action: String, id: String? = null, reopen: Boolean = false) {
        refresh()
        val starts = action == RecordingService.START || action == RecordingService.RESUME
        if (starts && !access.complete) {
            pending = PendingStart(id, reopen)
            accessShown = true
            return
        }
        val mode = vm.mode.value
        val intent = Intent(activity, RecordingService::class.java).setAction(action)
            .putExtra(RecordingService.MODE, (mode ?: vm.estimate.value).name)
            .putExtra(RecordingService.AUTOMATIC, mode == null).putExtra(RecordingService.TRIP, id)
            .putExtra(RecordingService.REOPEN, reopen)
        try {
            if (starts) vm.recorder.expectStart()
            if (starts) ContextCompat.startForegroundService(activity, intent) else activity.startService(intent)
            accessShown = false
            pending = PendingStart()
            // A resumed saved trip continues on the live map after the recorder resumes it. Other resumes stay on the current screen.
            if (action == RecordingService.RESUME && navigator.backStack.last() is Destination.SavedTrip) awaitedResume = id
            if (action == RecordingService.STOP && id != null) {
                justSaved = id
                navigator.open(Tab.Trips, Destination.SavedTrip(id))
            }
        } catch (_: RuntimeException) {
            vm.recorder.send(RecordingCommand.Interrupt(RecordingError.Service))
        }
    }

    /** Writes the trip as GPX and opens the Android share sheet. Returns false when the file cannot be made or shared. */
    suspend fun shareGpx(id: String): Boolean {
        val name = vm.trips.value.ready?.find { it.id == id }?.let {
            DateUtils.formatDateTime(activity, it.startedMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)
        } ?: activity.getString(R.string.app_name)
        val file = vm.exportGpx(id, name) ?: return false
        return try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.exports", file)
            val send = Intent(Intent.ACTION_SEND).setType(Gpx.MIME_TYPE).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(file.name, uri)
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.trip_share_gpx)))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        /** Keeps the dialog state and the pending command through activity recreation. Access is read again on resume. */
        fun saver(activity: Activity, vm: TrackingViewModel, navigator: AppNavigator, settings: AppSettings): Saver<TrackingController, Any> = Saver(
            save = {
                arrayListOf(it.accessShown, it.pending.tripId.orEmpty(), it.pending.reopen, it.explained, it.asked, it.awaitedResume.orEmpty())
            },
            restore = { saved ->
                val values = saved as List<*>
                TrackingController(activity, vm, navigator, settings, values[0] as Boolean,
                    PendingStart((values[1] as String).ifEmpty { null }, values[2] as Boolean),
                    values[3] as Boolean, values[4] as Boolean, (values[5] as String).ifEmpty { null })
            },
        )
    }
}
