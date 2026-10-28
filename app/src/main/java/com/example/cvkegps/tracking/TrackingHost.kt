package com.example.cvkegps.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cvkegps.R
import com.example.cvkegps.ui.theme.CvkeTheme
import com.example.cvkegps.ui.theme.Space
import com.example.cvkegps.ui.AppNavigator
import com.example.cvkegps.ui.Destination
import com.example.cvkegps.ui.Tab
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Trip state and commands for the screens. The host owns the recording dialogs.
 * Navigation keeps screen content between compositions, so state is read when used, not copied.
 */
@Stable
class TrackingActions(
    val vm: TrackingViewModel,
    private val recordingState: State<RecordingState>,
    private val activeTripState: State<String?>,
    private val errorState: State<Int?>,
    private val readyState: State<Boolean>,
    private val estimateState: State<TripMode>,
    private val selectedModeState: State<TripMode?>,
    private val justSavedState: State<String?>,
    val start: () -> Unit,
    val pause: () -> Unit,
    val resume: (String) -> Unit,
    val stop: (String) -> Unit,
    /** A trip ID changes that trip. Null chooses the activity for the next trip. */
    val chooseMode: (String?) -> Unit,
    val reopen: (String) -> Unit,
    val consumeJustSaved: () -> Unit,
    val finish: (String) -> Unit,
    val delete: (String) -> Unit,
    val shareGpx: suspend (String) -> Boolean,
) {
    /** The recorder state changes with each saved fix. Read [activeTripId] when only the trip matters. */
    val recording get() = recordingState.value
    /** The ID of the trip that records or is paused. It changes only when a trip starts or ends. */
    val activeTripId get() = activeTripState.value
    @get:StringRes val error get() = errorState.value
    val ready get() = readyState.value
    val estimate get() = estimateState.value
    /** The activity that the user chose for the next trip. Null means automatic. */
    val selectedMode get() = selectedModeState.value
    /** The trip that Stop and save ended a moment ago, which the saved trip offers to resume. */
    val justSaved get() = justSavedState.value
}

@Composable
fun TrackingHost(
    app: TrackingApplication,
    navigator: AppNavigator,
    openTripsRequest: Int,
    content: @Composable (TrackingActions) -> Unit,
) {
    val vm: TrackingViewModel = viewModel(factory = viewModelFactory {
        initializer { TrackingViewModel(app.database.trips(), app.recorder, app.location, File(app.cacheDir, "exports")) }
    })
    val activity = LocalActivity.current ?: return
    val controller = rememberSaveable(activity, vm, navigator, saver = TrackingController.saver(activity, vm, navigator, app.settings)) {
        TrackingController(activity, vm, navigator, app.settings)
    }
    val recordingState = vm.recorder.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val estimateState = vm.estimate.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val selectedModeState = vm.mode.collectAsStateWithLifecycle()
    val justSavedState = remember(controller) { derivedStateOf { controller.justSaved } }
    val activeTripState = remember { derivedStateOf { recordingState.value.trip?.id } }
    val storageError by vm.storageError.collectAsStateWithLifecycle()
    var changeMode by rememberSaveable { mutableStateOf(false) }
    var modeTrip by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTrip by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(openTripsRequest) {
        // The recording notification opens the current trip, or the trip list when no trip is active.
        if (openTripsRequest > 0) {
            if (vm.recorder.state.value.trip != null) navigator.open(Tab.Trips, Destination.LiveTrip)
            else navigator.open(Tab.Trips)
        }
    }
    // A resume from a saved trip opens the live map only when the recorder has resumed the trip.
    val awaitedResume = controller.awaitedResume
    LaunchedEffect(controller, awaitedResume) {
        if (awaitedResume != null) controller.resumeAnswered(vm.recorder.state.first { !it.starting })
    }
    LifecycleResumeEffect(controller) {
        controller.refresh()
        // Quick Settings can turn location on or off without pausing the app. Read the access again when it changes.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = controller.refresh()
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        onPauseOrDispose { activity.unregisterReceiver(receiver) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.permissionResult()
    }
    val error = remember {
        derivedStateOf {
            if (storageError) R.string.trip_storage_error else when (recordingState.value.error) {
                RecordingError.Access -> R.string.trip_access_missing
                RecordingError.Storage -> R.string.trip_storage_error
                RecordingError.Queue -> R.string.trip_queue_error
                RecordingError.Service -> R.string.trip_service_error
                RecordingError.Refused -> R.string.trip_resume_refused
                null -> null
            }
        }
    }
    val ready = remember(controller) { derivedStateOf { controller.access.precise && controller.access.locationOn } }
    content(remember(vm, controller) {
        TrackingActions(
            vm = vm, recordingState = recordingState, activeTripState = activeTripState, errorState = error, readyState = ready, estimateState = estimateState,
            selectedModeState = selectedModeState, justSavedState = justSavedState,
            start = controller::start,
            pause = controller::pause,
            resume = controller::resume,
            stop = controller::stop,
            chooseMode = { id -> modeTrip = id; changeMode = true },
            reopen = controller::reopen,
            consumeJustSaved = controller::consumeJustSaved,
            finish = vm::finishInterrupted,
            delete = { id -> deleteTrip = id },
            shareGpx = controller::shareGpx,
        )
    })
    if (controller.accessShown) AccessDialog(controller) {
        controller.permissionRequested()
        permission.launch(controller.permissions)
    }
    if (changeMode) AlertDialog(onDismissRequest = { changeMode = false }, title = { Text(stringResource(R.string.mode_change)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.mode_explanation))
                for (choice in listOf(null, TripMode.Car, TripMode.Transit, TripMode.Plane, TripMode.Bike, TripMode.Ski)) {
                    TextButton(onClick = {
                        modeTrip.let { id -> if (id == null) vm.selectMode(choice) else vm.correctMode(id, choice) }
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
                // The destructive action is a danger fill, and only in this confirmation step.
                Button(onClick = { vm.delete(id); deleteTrip = null; navigator.remove(Destination.SavedTrip(id)) },
                    colors = ButtonDefaults.buttonColors(containerColor = CvkeTheme.colors.danger, contentColor = CvkeTheme.colors.dangerInk),
                    modifier = Modifier.testTag("confirm_delete")) { Text(stringResource(R.string.trip_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTrip = null }) { Text(stringResource(R.string.trip_cancel)) } })
    }
}

/** Explains recording before the first trip. It offers a recovery action for each missing access. */
@Composable
private fun AccessDialog(controller: TrackingController, onRequestPermission: () -> Unit) {
    val access = controller.access
    AlertDialog(onDismissRequest = controller::dismissAccess, title = { Text(stringResource(R.string.tracker_access_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                Text(stringResource(R.string.trip_access_explanation))
                Text(stringResource(R.string.map_privacy))
                if (!access.precise || !access.notifications) {
                    Text(stringResource(R.string.trip_access_missing))
                    Button(onClick = onRequestPermission, modifier = Modifier.testTag("trip_permission")) {
                        Text(stringResource(R.string.trip_allow_access))
                    }
                    if (controller.asked) TextButton(onClick = controller::openAppSettings) {
                        Text(stringResource(R.string.trip_access_settings))
                    }
                    if (!access.notifications) TextButton(onClick = controller::openNotificationSettings) {
                        Text(stringResource(R.string.trip_notification_settings))
                    }
                }
                if (!access.locationOn) Button(onClick = controller::openLocationSettings) {
                    Text(stringResource(R.string.trip_location_settings))
                }
            }
        }, confirmButton = {
            TextButton(onClick = controller::confirmAccess, enabled = access.complete, modifier = Modifier.testTag("confirm_start")) {
                Text(stringResource(if (controller.pending.tripId == null) R.string.trip_start else R.string.trip_resume))
            }
        }, dismissButton = { TextButton(onClick = controller::dismissAccess) { Text(stringResource(R.string.trip_cancel)) } })
}
