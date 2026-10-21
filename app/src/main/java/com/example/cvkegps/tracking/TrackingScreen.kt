package com.example.cvkegps.tracking

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.net.toUri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cvkegps.R
import com.example.cvkegps.location.GpsReading
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
fun TrackingApp(
    app: TrackingApplication,
    openTripsRequest: Int,
    home: @Composable (controls: @Composable () -> Unit, onTrips: () -> Unit) -> Unit,
) {
    val vm: TrackingViewModel = viewModel(factory = viewModelFactory {
        initializer { TrackingViewModel(app.database.trips(), app.recorder, app.location) }
    })
    val activity = LocalActivity.current ?: return
    val rows by vm.trips.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val recording by vm.recorder.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val activeDetail by vm.activeDetail.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val mode by vm.mode.collectAsStateWithLifecycle()
    val estimate by vm.estimate.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val reading by vm.reading.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val storageError by vm.storageError.collectAsStateWithLifecycle()
    var showTrips by rememberSaveable { mutableStateOf(false) }
    var showAccess by rememberSaveable { mutableStateOf(false) }
    var explained by rememberSaveable { mutableStateOf(false) }
    var changeMode by rememberSaveable { mutableStateOf(false) }
    var modeTrip by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingResume by rememberSaveable { mutableStateOf<String?>(null) }
    var precise by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(false) }
    var locationOn by remember { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var deleteTrip by remember { mutableStateOf<String?>(null) }
    val active = recording.trip

    LaunchedEffect(openTripsRequest) {
        if (openTripsRequest > 0) { showTrips = false; vm.select(null) }
    }
    fun refresh() {
        precise = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val channel = activity.getSystemService(NotificationManager::class.java).getNotificationChannel("trip_recording")
        notifications = NotificationManagerCompat.from(activity).areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
        locationOn = activity.getSystemService(LocationManager::class.java)?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }
    LifecycleResumeEffect(activity) { refresh(); onPauseOrDispose { } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true; refresh() }
    fun command(action: String, id: String? = null) {
        refresh()
        if ((action == RecordingService.START || action == RecordingService.RESUME) && (!precise || !notifications || !locationOn)) {
            pendingResume = id; showAccess = true; return
        }
        val intent = Intent(activity, RecordingService::class.java).setAction(action)
            .putExtra(RecordingService.MODE, (mode ?: estimate).name)
            .putExtra(RecordingService.AUTOMATIC, mode == null).putExtra(RecordingService.TRIP, id)
        try {
            if (action == RecordingService.START || action == RecordingService.RESUME) ContextCompat.startForegroundService(activity, intent)
            else activity.startService(intent)
            showAccess = false
            if (action == RecordingService.START || action == RecordingService.RESUME) { showTrips = false; vm.select(null) }
            if (action == RecordingService.STOP) { vm.select(id); showTrips = true }
        } catch (_: RuntimeException) { vm.recorder.send(RecordingCommand.Interrupt(RecordingError.Service)) }
    }
    fun chooseMode(id: String?) { modeTrip = id; changeMode = true }
    fun start() {
        pendingResume = null
        if (!explained) showAccess = true else command(RecordingService.START)
    }
    BackHandler(showTrips) { if (selected != null) vm.select(null) else showTrips = false }
    val error = if (storageError) R.string.trip_storage_error else when (recording.error) {
        RecordingError.Access -> R.string.trip_access_missing
        RecordingError.Storage -> R.string.trip_storage_error
        RecordingError.Queue -> R.string.trip_queue_error
        RecordingError.Service -> R.string.trip_service_error
        null -> null
    }
    if (!showTrips && active == null) {
        home({
            if (error != null) Text(stringResource(error), color = MaterialTheme.colorScheme.error)
            Button(onClick = { start() }, enabled = !recording.starting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("trip_start")) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(stringResource(if (recording.starting) R.string.trip_starting else R.string.trip_start))
                    Text(stringResource(R.string.mode_estimate, stringResource(estimate.title())),
                        modifier = Modifier.testTag("activity_estimate"), style = MaterialTheme.typography.labelSmall)
                }
            }
        }, { showTrips = true; vm.select(null) })
    } else Scaffold { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            if (!showTrips && active != null) {
                LiveTracker(recording, activeDetail, reading,
                    onMode = { chooseMode(active.id) }, onTrips = { showTrips = true; vm.select(null) },
                    onPause = { command(RecordingService.PAUSE) },
                    onResume = { command(RecordingService.RESUME, active.id) },
                    onStop = { command(RecordingService.STOP, active.id) })
            } else LazyColumn(Modifier.fillMaxSize().testTag("trips_content"), verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                item {
                    TextButton(onClick = { if (selected != null) vm.select(null) else showTrips = false }, modifier = Modifier.testTag("nav_speed")) {
                        Text(stringResource(if (selected != null) R.string.trip_back else R.string.tracker_return))
                    }
                    Text(stringResource(R.string.tracking_trips), style = MaterialTheme.typography.headlineMedium)
                }
                if (error != null) item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) }
                if (selected == null) {
                    if (rows.isEmpty()) item { Text(stringResource(R.string.trip_none)) }
                    items(rows, key = TripRecord::id) { trip ->
                        Card(onClick = { vm.select(trip.id) }, modifier = Modifier.fillMaxWidth().testTag("saved_trip")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ActivityLabel(trip)
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(trip.startedMillis)))
                                Text(stringResource(trip.status.title()))
                            }
                        }
                    }
                } else {
                    val shown = detail?.takeIf { it.trip.id == selected }
                    if (shown == null) item { Text(stringResource(R.string.trip_loading)) }
                    else {
                        item {
                            TripSummary(shown)
                            TextButton(onClick = { chooseMode(shown.trip.id) }, modifier = Modifier.testTag("change_activity")) { Text(stringResource(R.string.mode_change)) }
                        }
                        item { RouteMap(shown.summary.lines) }
                        if (shown.trip.status == TripStatus.Interrupted) item {
                            Text(stringResource(R.string.trip_interrupted_note))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { command(RecordingService.RESUME, shown.trip.id) }, enabled = active == null) { Text(stringResource(R.string.trip_resume)) }
                                OutlinedButton(onClick = { vm.finishInterrupted(shown.trip.id) }) { Text(stringResource(R.string.trip_finish)) }
                            }
                        }
                        if (shown.trip.status == TripStatus.Recording || shown.trip.status == TripStatus.Paused) item {
                            Button(onClick = { showTrips = false }) { Text(stringResource(R.string.tracker_return)) }
                        } else item {
                            TextButton(onClick = { deleteTrip = shown.trip.id }, modifier = Modifier.testTag("trip_delete")) { Text(stringResource(R.string.trip_delete)) }
                        }
                    }
                }
            }
        }
    }
    if (showAccess) AlertDialog(onDismissRequest = { showAccess = false }, title = { Text(stringResource(R.string.tracker_access_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.trip_access_explanation))
                Text(stringResource(R.string.map_privacy))
                if (!precise || !notifications) {
                    Text(stringResource(R.string.trip_access_missing))
                    Button(onClick = {
                        asked = true
                        permission.launch(buildList {
                            add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.toTypedArray())
                    }, modifier = Modifier.testTag("trip_permission")) { Text(stringResource(R.string.trip_allow_access)) }
                    if (asked) TextButton(onClick = { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri())) }) {
                        Text(stringResource(R.string.trip_access_settings))
                    }
                    if (!notifications) TextButton(onClick = { activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)) }) {
                        Text(stringResource(R.string.trip_notification_settings))
                    }
                }
                if (!locationOn) Button(onClick = { activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text(stringResource(R.string.trip_location_settings)) }
            }
        }, confirmButton = {
            TextButton(onClick = { explained = true; command(if (pendingResume == null) RecordingService.START else RecordingService.RESUME, pendingResume) },
                enabled = precise && notifications && locationOn, modifier = Modifier.testTag("confirm_start")) {
                Text(stringResource(if (pendingResume == null) R.string.trip_start else R.string.trip_resume))
            }
        }, dismissButton = { TextButton(onClick = { showAccess = false }) { Text(stringResource(R.string.trip_cancel)) } })
    if (changeMode) AlertDialog(onDismissRequest = { changeMode = false }, title = { Text(stringResource(R.string.mode_change)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.mode_explanation))
                for (choice in listOf(null, TripMode.Car, TripMode.Transit, TripMode.Plane, TripMode.Bike, TripMode.Ski)) {
                    TextButton(onClick = {
                        if (modeTrip == null) vm.selectMode(choice) else vm.correctMode(modeTrip!!, choice)
                        changeMode = false
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mode_${choice?.name ?: "Auto"}")) {
                        Text(stringResource(choice?.title() ?: R.string.mode_auto))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { changeMode = false }) { Text(stringResource(R.string.trip_cancel)) } })
    deleteTrip?.let { id ->
        AlertDialog(onDismissRequest = { deleteTrip = null }, title = { Text(stringResource(R.string.trip_delete_title)) },
            text = { Text(stringResource(R.string.trip_delete_message)) },
            confirmButton = { TextButton(onClick = { vm.delete(id); deleteTrip = null }, modifier = Modifier.testTag("confirm_delete")) { Text(stringResource(R.string.trip_delete)) } },
            dismissButton = { TextButton(onClick = { deleteTrip = null }) { Text(stringResource(R.string.trip_cancel)) } })
    }
}

@Composable
private fun ActivityLabel(trip: TripRecord) {
    Text(if (trip.automaticMode) stringResource(R.string.mode_estimate, stringResource(trip.mode.title())) else stringResource(trip.mode.title()))
}

@Composable
private fun LiveTracker(state: RecordingState, detail: TripDetail?, reading: GpsReading?, onMode: () -> Unit,
    onTrips: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val trip = state.trip ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val elapsed by produceState(trip.durationNanos, state.segmentStartNanos, trip.status, lifecycle) {
        value = trip.durationNanos
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (trip.status == TripStatus.Recording) {
                value = state.segmentBaseDurationNanos + (SystemClock.elapsedRealtimeNanos() - (state.segmentStartNanos ?: SystemClock.elapsedRealtimeNanos())).coerceAtLeast(0)
                delay(1_000)
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("tracker_controls"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onMode, modifier = Modifier.testTag("change_activity")) { ActivityLabel(trip) }
                TextButton(onClick = onTrips, modifier = Modifier.testTag("nav_trips")) { Text(stringResource(R.string.tracking_trips)) }
            }
            Text(stringResource(trip.status.title()), modifier = Modifier.testTag("recording_status"), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.trip_ground_speed), style = MaterialTheme.typography.labelMedium)
            Text(reading?.speedMetersPerSecond?.let { stringResource(R.string.trip_speed_value, number(it * 3.6)) } ?: stringResource(R.string.no_value),
                modifier = Modifier.testTag("tracking_speed"), style = MaterialTheme.typography.headlineLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(R.string.trip_distance, detail?.let { stringResource(R.string.trip_distance_value, number(it.summary.distanceMeters / 1_000)) })
                Metric(R.string.trip_duration, stringResource(R.string.trip_duration_value, number(elapsed / 60_000_000_000.0)))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (trip.status == TripStatus.Recording) OutlinedButton(onClick = onPause, modifier = Modifier.testTag("trip_pause")) { Text(stringResource(R.string.trip_pause)) }
                else Button(onClick = onResume, modifier = Modifier.testTag("trip_resume")) { Text(stringResource(R.string.trip_resume)) }
                Button(onClick = onStop, modifier = Modifier.testTag("trip_stop")) { Text(stringResource(R.string.trip_stop)) }
            }
            // Design 2a gives speed more space above 30 km/h. Controls remain available.
            if (reading?.speedMetersPerSecond?.let { it < 30.0 / 3.6 } != false) {
                Metric(R.string.trip_altitude, reading?.altitudeMeters?.let { stringResource(R.string.trip_altitude_value, number(it)) })
                Metric(R.string.trip_quality, reading?.accuracyMeters?.let { stringResource(R.string.trip_altitude_value, number(it.toDouble())) })
            }
            if (trip.mode == TripMode.Plane) {
                Metric(R.string.trip_track, reading?.bearingRadians?.let { stringResource(R.string.trip_track_value, number(Math.toDegrees(it))) })
                Metric(R.string.trip_vertical_quality, reading?.verticalAccuracyMeters?.let { stringResource(R.string.trip_altitude_value, number(it.toDouble())) })
                Text(stringResource(R.string.trip_plane_note), style = MaterialTheme.typography.bodySmall)
            }
            if (reading == null && trip.status == TripStatus.Recording) Text(stringResource(R.string.trip_signal_wait))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) Row(Modifier.fillMaxSize()) {
            MapSurface(detail?.summary?.lines.orEmpty(), Modifier.weight(1f).fillMaxHeight(), live = true)
            Box(Modifier.weight(1f).fillMaxHeight()) { controls() }
        } else Column(Modifier.fillMaxSize()) {
            MapSurface(detail?.summary?.lines.orEmpty(), Modifier.fillMaxWidth().weight(1f), live = true)
            Box(Modifier.fillMaxWidth().weight(1f)) { controls() }
        }
    }
}

@Composable
private fun TripSummary(detail: TripDetail) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ActivityLabel(detail.trip)
            Text(stringResource(detail.trip.status.title()))
            Metric(R.string.trip_distance, stringResource(R.string.trip_distance_value, number(detail.summary.distanceMeters / 1_000)))
            Metric(R.string.trip_duration, stringResource(R.string.trip_duration_value, number(detail.trip.durationNanos / 60_000_000_000.0)))
            Metric(R.string.maximum_speed, detail.summary.maximumSpeedMetersPerSecond?.let { stringResource(R.string.trip_speed_value, number(it * 3.6)) })
            Metric(R.string.trip_points, number(detail.positions.toDouble(), 0))
            Metric(R.string.trip_segments, number(detail.segments.toDouble(), 0))
            Metric(R.string.trip_gaps, number(detail.summary.gapCount.toDouble(), 0))
            Text(stringResource(R.string.trip_gap_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Metric(@StringRes title: Int, value: String?) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.labelMedium)
        Text(value ?: stringResource(R.string.no_value), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun number(value: Double, decimals: Int = 1): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, value, decimals) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = decimals }.format(value) }
}

@StringRes internal fun TripMode.title() = when (this) {
    TripMode.Unknown -> R.string.mode_unknown
    TripMode.CarOrTransit -> R.string.mode_road
    TripMode.BikeOrSki -> R.string.mode_bike_ski
    TripMode.Car -> R.string.mode_car
    TripMode.Transit -> R.string.mode_transit
    TripMode.Plane -> R.string.mode_plane
    TripMode.Bike -> R.string.mode_bike
    TripMode.Ski -> R.string.mode_ski
}

@StringRes private fun TripStatus.title() = when (this) {
    TripStatus.Recording -> R.string.trip_recording
    TripStatus.Paused -> R.string.trip_paused
    TripStatus.Interrupted -> R.string.trip_interrupted
    TripStatus.Finished -> R.string.trip_finished
}
