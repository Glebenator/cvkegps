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
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import com.example.cvkegps.ui.AppNavigator
import com.example.cvkegps.ui.Destination
import com.example.cvkegps.ui.Tab
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * Trip state and commands for the screens. The host owns the recording dialogs.
 * Navigation keeps screen content between compositions, so state is read when used, not copied.
 */
@Stable
class TrackingActions(
    val vm: TrackingViewModel,
    private val recordingState: State<RecordingState>,
    private val errorState: State<Int?>,
    private val readyState: State<Boolean>,
    private val estimateState: State<TripMode>,
    val start: () -> Unit,
    val pause: () -> Unit,
    val resume: (String) -> Unit,
    val stop: (String) -> Unit,
    val chooseMode: (String?) -> Unit,
    val finish: (String) -> Unit,
    val delete: (String) -> Unit,
) {
    val recording get() = recordingState.value
    @get:StringRes val error get() = errorState.value
    val ready get() = readyState.value
    val estimate get() = estimateState.value
}

@Composable
fun TrackingHost(
    app: TrackingApplication,
    navigator: AppNavigator,
    openTripsRequest: Int,
    content: @Composable (TrackingActions) -> Unit,
) {
    val vm: TrackingViewModel = viewModel(factory = viewModelFactory {
        initializer { TrackingViewModel(app.database.trips(), app.recorder, app.location) }
    })
    val activity = LocalActivity.current ?: return
    val recordingState = vm.recorder.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val mode by vm.mode.collectAsStateWithLifecycle()
    val estimateState = vm.estimate.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val estimate by estimateState
    val storageError by vm.storageError.collectAsStateWithLifecycle()
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

    LaunchedEffect(openTripsRequest) {
        // The recording notification opens the current trip, or the trip list when no trip is active.
        if (openTripsRequest > 0) {
            if (vm.recorder.state.value.trip != null) navigator.open(Tab.Trips, Destination.LiveTrip)
            else navigator.open(Tab.Trips)
        }
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
            // A resumed saved trip continues on the live map. Other resumes stay on the current screen.
            if (action == RecordingService.RESUME && navigator.backStack.last() is Destination.SavedTrip) {
                navigator.open(Tab.Trips, Destination.LiveTrip)
            }
            if (action == RecordingService.STOP && id != null) navigator.open(Tab.Trips, Destination.SavedTrip(id))
        } catch (_: RuntimeException) { vm.recorder.send(RecordingCommand.Interrupt(RecordingError.Service)) }
    }
    val error = remember {
        derivedStateOf {
            if (storageError) R.string.trip_storage_error else when (recordingState.value.error) {
                RecordingError.Access -> R.string.trip_access_missing
                RecordingError.Storage -> R.string.trip_storage_error
                RecordingError.Queue -> R.string.trip_queue_error
                RecordingError.Service -> R.string.trip_service_error
                null -> null
            }
        }
    }
    val ready = remember { derivedStateOf { precise && locationOn } }
    content(remember(vm) { TrackingActions(
        vm = vm, recordingState = recordingState, errorState = error, readyState = ready, estimateState = estimateState,
        start = {
            pendingResume = null
            if (!explained) showAccess = true else command(RecordingService.START)
        },
        pause = { command(RecordingService.PAUSE) },
        resume = { id -> command(RecordingService.RESUME, id) },
        stop = { id -> command(RecordingService.STOP, id) },
        chooseMode = { id -> modeTrip = id; changeMode = true },
        finish = vm::finishInterrupted,
        delete = { id -> deleteTrip = id },
    ) })
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
            confirmButton = {
                TextButton(onClick = { vm.delete(id); deleteTrip = null; navigator.remove(Destination.SavedTrip(id)) },
                    modifier = Modifier.testTag("confirm_delete")) { Text(stringResource(R.string.trip_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTrip = null }) { Text(stringResource(R.string.trip_cancel)) } })
    }
}

/** The Speed tab slot. Pause and Stop move to the recording bar while a trip is active. */
@Composable
fun TripStartControls(actions: TrackingActions, canStartTrip: Boolean) {
    actions.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    if (actions.recording.trip == null && canStartTrip && actions.ready) Button(onClick = actions.start,
        enabled = !actions.recording.starting, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("trip_start")) {
        Icon(painterResource(R.drawable.ic_record), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(if (actions.recording.starting) R.string.trip_starting else R.string.trip_start))
            Text(stringResource(R.string.mode_estimate, stringResource(actions.estimate.title())),
                modifier = Modifier.testTag("activity_estimate"), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(actions: TrackingActions, onTrip: (String) -> Unit, onLive: () -> Unit) {
    val rows by actions.vm.trips.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tracking_trips)) }) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).testTag("trips_content"),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(16.dp)) {
            actions.error?.let { error -> item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) } }
            if (rows.isEmpty()) item { Text(stringResource(R.string.trip_none)) }
            items(rows, key = TripRecord::id) { trip ->
                val live = trip.id == actions.recording.trip?.id
                Card(onClick = { if (live) onLive() else onTrip(trip.id) }, modifier = Modifier.fillMaxWidth().testTag("saved_trip")) {
                    ListItem(
                        headlineContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(trip.startedMillis))) },
                        supportingContent = { Text(stringResource(R.string.trip_summary_line, activityText(trip), duration(trip.durationNanos))) },
                        trailingContent = if (trip.status != TripStatus.Finished) { { StatusLabel(trip.status) } } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(status: TripStatus) {
    val active = status == TripStatus.Recording || status == TripStatus.Paused
    Surface(shape = MaterialTheme.shapes.small,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer) {
        Text(stringResource(status.title()), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun activityText(trip: TripRecord): String =
    if (trip.automaticMode) stringResource(R.string.mode_estimate, stringResource(trip.mode.title())) else stringResource(trip.mode.title())

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.testTag("top_back")) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
    }
}

@Composable
private fun TripActions(trip: TripRecord, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val modifier = Modifier.weight(1f).heightIn(min = 48.dp)
        if (trip.status == TripStatus.Recording) FilledTonalButton(onClick = onPause, modifier = modifier.testTag("trip_pause")) {
            ButtonContent(R.drawable.ic_pause, R.string.trip_pause)
        } else FilledTonalButton(onClick = onResume, modifier = modifier.testTag("trip_resume")) {
            ButtonContent(R.drawable.ic_play, R.string.trip_resume)
        }
        Button(onClick = onStop, modifier = modifier.testTag("trip_stop")) { ButtonContent(R.drawable.ic_stop, R.string.trip_stop) }
    }
}

@Composable
private fun ButtonContent(icon: Int, @StringRes label: Int) {
    Icon(painterResource(icon), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(stringResource(label))
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
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { panel() }
            }
        } else Column(Modifier.fillMaxSize()) {
            MapSurface(lines, Modifier.fillMaxWidth().weight(1f), live)
            Surface {
                Column(Modifier.fillMaxWidth().heightIn(max = panelHeight)
                    .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { panel() }
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trip_details), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("close_trip_details")) { Text(stringResource(R.string.trip_close)) }
            }
            content()
        }
    }
}

@Composable
private fun DetailsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("trip_details")) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.trip_details))
    }
}

/** Recording time that counts each second only while the screen is resumed. */
@Composable
internal fun rememberElapsed(state: RecordingState): Long {
    val trip = state.trip ?: return 0
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
    return elapsed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTripScreen(actions: TrackingActions, onBack: () -> Unit, onEnded: () -> Unit) {
    val state = actions.recording
    val trip = state.trip
    val detail by actions.vm.activeDetail.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val reading by actions.vm.reading.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    var details by rememberSaveable { mutableStateOf(false) }
    val elapsed = rememberElapsed(state)
    // Close this screen when the trip ends elsewhere, for example from the notification.
    val seen = remember { booleanArrayOf(false) }
    LaunchedEffect(trip == null) { if (trip != null) seen[0] = true else if (seen[0]) onEnded() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.trip_live_title)) }, navigationIcon = { BackButton(onBack) },
            actions = { if (trip != null) DetailsAction { details = true } })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            actions.error?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            if (trip == null) Text(stringResource(R.string.trip_starting), Modifier.padding(16.dp))
            else Box(Modifier.weight(1f)) {
                MapWorkspace(detail?.summary?.lines.orEmpty(), live = true) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(trip.status.title()), Modifier.testTag("recording_status"),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(reading?.speedMetersPerSecond?.let { stringResource(R.string.trip_speed_value, number(it * 3.6)) }
                                ?: stringResource(R.string.no_value), Modifier.testTag("tracking_speed"), style = MaterialTheme.typography.headlineSmall)
                        }
                        Text(duration(elapsed), style = MaterialTheme.typography.titleMedium)
                    }
                    TripActions(trip, actions.pause, { actions.resume(trip.id) }, { actions.stop(trip.id) })
                }
            }
        }
    }
    if (details && trip != null) TripDetailsSheet({ details = false }) {
        Metric(R.string.trip_ground_speed, reading?.speedMetersPerSecond?.let { stringResource(R.string.trip_speed_value, number(it * 3.6)) })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Metric(R.string.trip_activity, activityText(trip)) }
            TextButton(onClick = { actions.chooseMode(trip.id) }, modifier = Modifier.testTag("change_activity")) {
                Text(stringResource(R.string.mode_change_short))
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedTripScreen(actions: TrackingActions, id: String, onBack: () -> Unit) {
    LaunchedEffect(id) { actions.vm.select(id) }
    val detail by actions.vm.detail.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val shown = detail?.takeIf { it.trip.id == id }
    var details by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(shown?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.trip.startedMillis)) } ?: "") },
            navigationIcon = { BackButton(onBack) },
            actions = {
                if (shown != null) {
                    DetailsAction { details = true }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("trip_menu")) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.mode_change)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                                onClick = { menu = false; actions.chooseMode(id) }, modifier = Modifier.testTag("change_activity"))
                            if (shown.trip.status == TripStatus.Finished || shown.trip.status == TripStatus.Interrupted) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.trip_delete)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                                    onClick = { menu = false; actions.delete(id) }, modifier = Modifier.testTag("trip_delete"))
                            }
                        }
                    }
                }
            },
        )
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).testTag("trip_review")) {
            actions.error?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            if (shown == null) Text(stringResource(R.string.trip_loading), Modifier.padding(16.dp))
            else Box(Modifier.weight(1f)) {
                MapWorkspace(shown.summary.lines) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(R.string.trip_distance, distance(shown.summary.distanceMeters))
                        Metric(R.string.trip_duration, duration(shown.trip.durationNanos))
                    }
                    if (shown.trip.status == TripStatus.Interrupted) {
                        Text(stringResource(R.string.trip_interrupted_note), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { actions.resume(id) }, enabled = actions.recording.trip == null,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { ButtonContent(R.drawable.ic_play, R.string.trip_resume) }
                            OutlinedButton(onClick = { actions.finish(id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.trip_finish))
                            }
                        }
                    }
                }
            }
        }
    }
    if (details && shown != null) TripDetailsSheet({ details = false }) {
        TripSummary(shown)
        Text(stringResource(R.string.map_start_end), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.map_privacy), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TripSummary(detail: TripDetail) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(activityText(detail.trip))
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
internal fun duration(nanos: Long): String {
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

@StringRes internal fun TripStatus.title() = when (this) {
    TripStatus.Recording -> R.string.trip_recording
    TripStatus.Paused -> R.string.trip_paused
    TripStatus.Interrupted -> R.string.trip_interrupted
    TripStatus.Finished -> R.string.trip_finished
}
