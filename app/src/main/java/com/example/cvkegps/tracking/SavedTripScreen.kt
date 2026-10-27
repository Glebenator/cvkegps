package com.example.cvkegps.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cvkegps.R
import com.example.cvkegps.ui.MapWithPane
import com.example.cvkegps.ui.PaneHeader
import com.example.cvkegps.ui.isCompactHeight
import com.example.cvkegps.ui.rememberDisplayFormat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedTripScreen(actions: TrackingActions, id: String, onBack: () -> Unit) {
    // The trip list selects the trip before navigation. This covers a screen restored after process loss.
    LaunchedEffect(id) { if (actions.vm.selectedId.value != id) actions.vm.select(id) }
    val rows by actions.vm.trips.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val detail by actions.vm.detail.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val shown = detail.ready?.takeIf { it.trip.id == id }
    // The list record is already in memory. The screen uses it until the route summary arrives.
    val trip = shown?.trip ?: rows.ready?.find { it.id == id }
    // A failed read shows an error and a retry action, not an empty trip.
    val readFailed = detail is StoredRead.Failed
    var details by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val title = trip?.let { tripTime(it.startedMillis) } ?: ""

    // Stop and save opens this screen. For a short time the trip can continue, which undoes an accidental stop.
    val savedMessage = stringResource(R.string.trip_saved_message)
    val resumeLabel = stringResource(R.string.trip_resume)
    LaunchedEffect(actions.justSaved) {
        if (actions.justSaved == id) {
            val result = snackbar.showSnackbar(savedMessage, resumeLabel, withDismissAction = true, duration = SnackbarDuration.Long)
            actions.consumeJustSaved()
            if (result == SnackbarResult.ActionPerformed) actions.reopen(id)
        }
    }

    if (isCompactHeight()) {
        val lines = shown?.summary?.lines
        Box(Modifier.fillMaxSize().testTag("trip_review")) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= 600.dp) MapWithPane(map = { modifier, insets ->
                    MapSurface(lines.orEmpty(), modifier, routeReady = lines != null, insets = insets, onBack = onBack)
                }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        if (trip != null) TripMenu(actions, id, trip, shown, snackbar)
                    }
                    SavedTripContent(actions, id, trip, shown, readFailed = readFailed)
                } else {
                    // The detail pane beside the trip list: a header, the map, and the values below it.
                    val panelHeight = maxHeight * 0.45f
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))) {
                        PaneHeader(title, onBack, Modifier.padding(horizontal = 4.dp)) {
                            if (trip != null) TripMenu(actions, id, trip, shown, snackbar)
                        }
                        MapSurface(lines.orEmpty(), Modifier.fillMaxWidth().weight(1f), routeReady = lines != null)
                        Surface {
                            Column(Modifier.fillMaxWidth().heightIn(max = panelHeight).verticalScroll(rememberScrollState())
                                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SavedTripContent(actions, id, trip, shown, readFailed = readFailed)
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
        }
        return
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
            actions = {
                if (trip != null) {
                    DetailsAction { details = true }
                    TripMenu(actions, id, trip, shown, snackbar)
                }
            },
        )
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).testTag("trip_review")) {
            actions.error?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            if (readFailed) ReadError(actions.vm::retryReads, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            BoxWithConstraints(Modifier.weight(1f)) {
                // A wide area, such as an open foldable, uses the landscape side pane. A narrow detail pane keeps the portrait layout.
                if (maxWidth >= 600.dp) MapWithPane(map = { modifier, _ ->
                    MapSurface(shown?.summary?.lines.orEmpty(), modifier, routeReady = shown != null)
                }, edgeToEdge = false) { SavedTripContent(actions, id, trip, shown, showError = false) }
                else MapWorkspace(shown?.summary?.lines) {
                    SavedTotals(trip, shown)
                    InterruptedActions(actions, id, trip)
                }
            }
        }
    }
    if (details && shown != null) TripDetailsSheet({ details = false }) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text(activityText(shown.trip), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(shown.trip.status.title()), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SavedTotals(shown.trip, shown)
            }
        }
        SavedTechnicalDetails(shown)
        PrivacyNote()
    }
}

/** Landscape content: everything that the portrait details sheet shows, inside the pane. */
@Composable
private fun ColumnScope.SavedTripContent(
    actions: TrackingActions, id: String, trip: TripRecord?, shown: TripDetail?, showError: Boolean = true, readFailed: Boolean = false,
) {
    if (showError) actions.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    if (readFailed) ReadError(actions.vm::retryReads)
    if (trip != null) ActivityChip(activityText(trip), onClick = { actions.chooseMode(id) })
    SavedTotals(trip, shown)
    InterruptedActions(actions, id, trip)
    if (shown != null) SavedTechnicalDetails(shown)
    PrivacyNote()
}

@Composable
private fun InterruptedActions(actions: TrackingActions, id: String, trip: TripRecord?) {
    if (trip?.status != TripStatus.Interrupted) return
    Text(stringResource(R.string.trip_interrupted_note), style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { actions.resume(id) }, enabled = actions.recording.trip == null,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            ButtonContent(R.drawable.ic_play, R.string.trip_resume)
        }
        OutlinedButton(onClick = { actions.finish(id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.trip_finish))
        }
    }
}

@Composable
private fun SavedTechnicalDetails(shown: TripDetail) {
    val format = rememberDisplayFormat()
    TechnicalDetails {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(R.string.trip_points, format.whole(shown.positions))
            Metric(R.string.trip_segments, format.whole(shown.segments))
            Metric(R.string.trip_gaps, format.whole(shown.summary.gapCount))
        }
        Text(stringResource(R.string.trip_gap_note), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.map_start_end), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PrivacyNote() {
    Text(stringResource(R.string.trip_privacy), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Change activity, Share as GPX, and Delete trip. */
@Composable
private fun TripMenu(actions: TrackingActions, id: String, trip: TripRecord, shown: TripDetail?, snackbar: SnackbarHostState) {
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exportError = stringResource(R.string.trip_export_error)
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("trip_menu")) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.mode_change)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                onClick = { menu = false; actions.chooseMode(id) }, modifier = Modifier.testTag("change_activity"))
            // Only a route with positions can be shared.
            if (shown?.summary?.lines?.isNotEmpty() == true) {
                DropdownMenuItem(text = { Text(stringResource(R.string.trip_share_gpx)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
                    onClick = {
                        menu = false
                        scope.launch { if (!actions.shareGpx(id)) snackbar.showSnackbar(exportError) }
                    }, modifier = Modifier.testTag("trip_share_gpx"))
            }
            if (trip.status == TripStatus.Finished || trip.status == TripStatus.Interrupted) {
                DropdownMenuItem(text = { Text(stringResource(R.string.trip_delete)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                    onClick = { menu = false; actions.delete(id) }, modifier = Modifier.testTag("trip_delete"))
            }
        }
    }
}

/** The route summary is exact. The stored distance shows until it arrives. */
@Composable
private fun SavedTotals(trip: TripRecord?, shown: TripDetail?) {
    val distance = shown?.summary?.distanceMeters ?: trip?.distanceMeters
    TripTotals(distance, distancePending = trip == null || (distance == null && shown == null), durationNanos = trip?.durationNanos,
        maximumSpeed = shown?.summary?.maximumSpeedMetersPerSecond, maximumPending = shown == null)
}
