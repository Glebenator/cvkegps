package com.example.cvkegps.tracking

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cvkegps.R
import com.example.cvkegps.ui.AppNavigator
import com.example.cvkegps.ui.Destination
import com.example.cvkegps.ui.Tab
import java.io.File

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
    val shareGpx: suspend (String) -> Boolean,
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
        initializer { TrackingViewModel(app.database.trips(), app.recorder, app.location, File(app.cacheDir, "exports")) }
    })
    val activity = LocalActivity.current ?: return
    val controller = rememberSaveable(activity, vm, navigator, saver = TrackingController.saver(activity, vm, navigator)) {
        TrackingController(activity, vm, navigator)
    }
    val recordingState = vm.recorder.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val estimateState = vm.estimate.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
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
    LifecycleResumeEffect(controller) {
        controller.refresh()
        onPauseOrDispose { }
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
                null -> null
            }
        }
    }
    val ready = remember(controller) { derivedStateOf { controller.access.precise && controller.access.locationOn } }
    content(remember(vm, controller) {
        TrackingActions(
            vm = vm, recordingState = recordingState, errorState = error, readyState = ready, estimateState = estimateState,
            start = controller::start,
            pause = controller::pause,
            resume = controller::resume,
            stop = controller::stop,
            chooseMode = { id -> modeTrip = id; changeMode = true },
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
                TextButton(onClick = { vm.delete(id); deleteTrip = null; navigator.remove(Destination.SavedTrip(id)) },
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Text(stringResource(if (controller.pendingResume == null) R.string.trip_start else R.string.trip_resume))
            }
        }, dismissButton = { TextButton(onClick = controller::dismissAccess) { Text(stringResource(R.string.trip_cancel)) } })
}
