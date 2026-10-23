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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
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
    home: @Composable (controls: @Composable (canStartTrip: Boolean) -> Unit, onTrips: () -> Unit) -> Unit,
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
    var showLiveMap by rememberSaveable { mutableStateOf(false) }
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
        if (openTripsRequest > 0) { showTrips = false; showLiveMap = true; vm.select(null) }
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
        showLiveMap = false
        pendingResume = null
        if (!explained) showAccess = true else command(RecordingService.START)
    }
    BackHandler(showTrips || (active != null && showLiveMap)) {
        when {
            showTrips && selected != null -> vm.select(null)
            showTrips -> showTrips = false
            else -> showLiveMap = false
        }
    }
    val error = if (storageError) R.string.trip_storage_error else when (recording.error) {
        RecordingError.Access -> R.string.trip_access_missing
        RecordingError.Storage -> R.string.trip_storage_error
        RecordingError.Queue -> R.string.trip_queue_error
        RecordingError.Service -> R.string.trip_service_error
        null -> null
    }
    if (!showTrips && (active == null || !showLiveMap)) {
        home({ canStartTrip ->
            if (error != null) Text(stringResource(error), color = MaterialTheme.colorScheme.error)
            if (active != null) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(active.status.title()), Modifier.weight(1f).testTag("recording_status"),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { showLiveMap = true }, modifier = Modifier.testTag("show_route")) {
                            Text(stringResource(R.string.trip_view_route))
                        }
                    }
                    TripActions(active, { command(RecordingService.PAUSE) },
                        { command(RecordingService.RESUME, active.id) }, { command(RecordingService.STOP, active.id) })
                }
            } else if (canStartTrip && precise && locationOn) Button(onClick = { start() }, enabled = !recording.starting,
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
                    onSpeed = { showLiveMap = false }, error = error,
                    onPause = { command(RecordingService.PAUSE) },
                    onResume = { command(RecordingService.RESUME, active.id) },
                    onStop = { command(RecordingService.STOP, active.id) })
            } else if (selected != null) {
                val shown = detail?.takeIf { it.trip.id == selected }
                if (shown == null) Column(Modifier.padding(16.dp)) {
                    TextButton(onClick = { vm.select(null) }) { Text(stringResource(R.string.trip_back)) }
                    Text(stringResource(R.string.trip_loading))
                } else androidx.compose.runtime.key(shown.trip.id) {
                    SavedTripScreen(shown, error, onBack = { vm.select(null) },
                        onMode = { chooseMode(shown.trip.id) },
                        onResume = { command(RecordingService.RESUME, shown.trip.id) },
                        canResume = active == null,
                        onFinish = { vm.finishInterrupted(shown.trip.id) },
                        onActive = { showTrips = false; showLiveMap = true },
                        onDelete = { deleteTrip = shown.trip.id })
                }
            } else Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { showTrips = false }, modifier = Modifier.testTag("nav_speed")) {
                        Text(stringResource(R.string.tracker_return))
                    }
                }
                LazyColumn(Modifier.weight(1f).testTag("trips_content"), verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                    item { Text(stringResource(R.string.tracking_trips), style = MaterialTheme.typography.headlineMedium) }
                    if (error != null) item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) }
                    if (rows.isEmpty()) item { Text(stringResource(R.string.trip_none)) }
                    items(rows, key = TripRecord::id) { trip ->
                        Card(onClick = { vm.select(trip.id) }, modifier = Modifier.fillMaxWidth().testTag("saved_trip")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(trip.startedMillis)),
                                    style = MaterialTheme.typography.titleMedium)
                                ActivityLabel(trip)
                                Text(stringResource(trip.status.title()), color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge)
                                Text(duration(trip.durationNanos),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
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
private fun TripActions(trip: TripRecord, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (trip.status == TripStatus.Recording) OutlinedButton(onClick = onPause, modifier = Modifier.testTag("trip_pause")) {
            Text(stringResource(R.string.trip_pause))
        } else OutlinedButton(onClick = onResume, modifier = Modifier.testTag("trip_resume")) {
            Text(stringResource(R.string.trip_resume))
        }
        Button(onClick = onStop, modifier = Modifier.testTag("trip_stop")) { Text(stringResource(R.string.trip_stop)) }
    }
}

/** Keep map gestures separate from the scrolling measurements. */
@Composable
private fun MapWorkspace(lines: List<List<TrackSample>>, live: Boolean = false, panel: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelHeight = maxHeight * 0.45f
        val panelWidth = (maxWidth * 0.36f).coerceIn(240.dp, 360.dp)
        if (maxWidth > maxHeight) Row(Modifier.fillMaxSize()) {
            MapSurface(lines, Modifier.weight(1f).fillMaxHeight(), live)
            Surface(Modifier.width(panelWidth).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { panel() }
            }
        } else Column(Modifier.fillMaxSize()) {
            MapSurface(lines, Modifier.fillMaxWidth().weight(1f), live)
            Surface {
                Column(Modifier.fillMaxWidth().heightIn(max = panelHeight)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) { panel() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripDetailsSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = 24.dp).testTag("trip_details_content"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.trip_details), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("close_trip_details")) { Text(stringResource(R.string.trip_close)) }
            }
            content()
        }
    }
}

@Composable
private fun LiveTracker(state: RecordingState, detail: TripDetail?, reading: GpsReading?, onMode: () -> Unit,
    onTrips: () -> Unit, onSpeed: () -> Unit, error: Int?, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val trip = state.trip ?: return
    var details by rememberSaveable { mutableStateOf(false) }
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
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onSpeed, modifier = Modifier.testTag("nav_speed")) { Text(stringResource(R.string.trip_speedometer)) }
            TextButton(onClick = onTrips, modifier = Modifier.testTag("nav_trips")) { Text(stringResource(R.string.tracking_trips)) }
        }
        if (error != null) Text(stringResource(error), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        Box(Modifier.weight(1f)) {
            MapWorkspace(detail?.summary?.lines.orEmpty(), live = true) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(trip.status.title()), Modifier.testTag("recording_status"),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(reading?.speedMetersPerSecond?.let { stringResource(R.string.trip_speed_value, number(it * 3.6)) }
                            ?: stringResource(R.string.no_value), Modifier.testTag("tracking_speed"), style = MaterialTheme.typography.headlineSmall)
                    }
                    TextButton(onClick = { details = true }, modifier = Modifier.testTag("trip_details")) { Text(stringResource(R.string.trip_details)) }
                }
                TripActions(trip, onPause, onResume, onStop)
            }
        }
    }
    if (details) TripDetailsSheet({ details = false }) {
        Metric(R.string.trip_ground_speed, reading?.speedMetersPerSecond?.let { stringResource(R.string.trip_speed_value, number(it * 3.6)) })
        TextButton(onClick = onMode, modifier = Modifier.testTag("change_activity")) { ActivityLabel(trip) }
        Metric(R.string.trip_distance, detail?.let { distance(it.summary.distanceMeters) })
        Metric(R.string.trip_duration, duration(elapsed))
        Metric(R.string.trip_altitude, reading?.altitudeMeters?.let { stringResource(R.string.trip_altitude_value, number(it)) })
        Metric(R.string.trip_quality, reading?.accuracyMeters?.let { stringResource(R.string.trip_altitude_value, number(it.toDouble())) })
        if (trip.mode == TripMode.Plane) {
            Metric(R.string.trip_track, reading?.bearingRadians?.let { stringResource(R.string.trip_track_value, number(Math.toDegrees(it))) })
            Metric(R.string.trip_vertical_quality, reading?.verticalAccuracyMeters?.let { stringResource(R.string.trip_altitude_value, number(it.toDouble())) })
            Text(stringResource(R.string.trip_plane_note), style = MaterialTheme.typography.bodySmall)
        }
        if (reading == null && trip.status == TripStatus.Recording) Text(stringResource(R.string.trip_signal_wait))
    }
}

@Composable
private fun SavedTripScreen(detail: TripDetail, error: Int?, onBack: () -> Unit, onMode: () -> Unit,
    onResume: () -> Unit, canResume: Boolean, onFinish: () -> Unit, onActive: () -> Unit, onDelete: () -> Unit) {
    var details by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("trip_review")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("nav_speed")) { Text(stringResource(R.string.trip_back)) }
            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(detail.trip.startedMillis)),
                Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall)
        }
        if (error != null) Text(stringResource(error), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        Box(Modifier.weight(1f)) {
            MapWorkspace(detail.summary.lines) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric(R.string.trip_distance, distance(detail.summary.distanceMeters))
                    Metric(R.string.trip_duration, duration(detail.trip.durationNanos))
                }
                TextButton(onClick = { details = true }, modifier = Modifier.testTag("trip_details")) { Text(stringResource(R.string.trip_details)) }
                if (detail.trip.status == TripStatus.Interrupted) {
                    Text(stringResource(R.string.trip_interrupted_note), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onResume, enabled = canResume) { Text(stringResource(R.string.trip_resume)) }
                        OutlinedButton(onClick = onFinish) { Text(stringResource(R.string.trip_finish)) }
                    }
                } else if (detail.trip.status != TripStatus.Finished) {
                    TextButton(onClick = onActive) { Text(stringResource(R.string.trip_view_route)) }
                }
            }
        }
    }
    if (details) TripDetailsSheet({ details = false }) {
        TripSummary(detail)
        TextButton(onClick = onMode, modifier = Modifier.testTag("change_activity")) { Text(stringResource(R.string.mode_change)) }
        Text(stringResource(R.string.map_start_end), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.map_privacy), style = MaterialTheme.typography.bodySmall)
        if (detail.trip.status == TripStatus.Finished || detail.trip.status == TripStatus.Interrupted) {
            TextButton(onClick = onDelete, modifier = Modifier.testTag("trip_delete")) {
                Text(stringResource(R.string.trip_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TripSummary(detail: TripDetail) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ActivityLabel(detail.trip)
            Text(stringResource(detail.trip.status.title()))
            Metric(R.string.trip_distance, distance(detail.summary.distanceMeters))
            Metric(R.string.trip_duration, duration(detail.trip.durationNanos))
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
private fun distance(meters: Double): String = if (meters < 1_000) {
    stringResource(R.string.trip_metres_value, number(meters, 0))
} else stringResource(R.string.trip_distance_value, number(meters / 1_000, 2))

@Composable
private fun duration(nanos: Long): String {
    val seconds = (nanos / 1_000_000_000L).coerceAtLeast(0)
    return if (seconds >= 3_600) {
        stringResource(R.string.trip_hours_minutes, number((seconds / 3_600).toDouble(), 0), number((seconds / 60 % 60).toDouble(), 0))
    } else stringResource(R.string.trip_minutes_seconds, number((seconds / 60).toDouble(), 0), number((seconds % 60).toDouble(), 0))
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
