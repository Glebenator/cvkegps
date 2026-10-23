package com.example.cvkegps.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cvkegps.R
import com.example.cvkegps.ui.rememberDisplayFormat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedTripScreen(actions: TrackingActions, id: String, onBack: () -> Unit) {
    val format = rememberDisplayFormat()
    // The trip list selects the trip before navigation. This covers a screen restored after process loss.
    LaunchedEffect(id) { if (actions.vm.selectedId.value != id) actions.vm.select(id) }
    val rows by actions.vm.trips.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val detail by actions.vm.detail.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val shown = detail?.takeIf { it.trip.id == id }
    // The list record is already in memory. The screen uses it until the route summary arrives.
    val trip = shown?.trip ?: rows?.find { it.id == id }
    var details by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val exportError = stringResource(R.string.trip_export_error)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(
            title = { Text(trip?.let { tripTime(it.startedMillis) } ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
            actions = {
                if (trip != null) {
                    DetailsAction { details = true }
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
            },
        )
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).testTag("trip_review")) {
            actions.error?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            Box(Modifier.weight(1f)) {
                MapWorkspace(shown?.summary?.lines) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(R.string.trip_distance, shown?.let { format.distance(it.summary.distanceMeters) }, pending = shown == null)
                        Metric(R.string.trip_duration, trip?.let { format.duration(it.durationNanos) }, pending = trip == null)
                        Metric(R.string.maximum_speed, shown?.summary?.maximumSpeedMetersPerSecond?.let(format::speed),
                            pending = shown == null)
                    }
                    if (trip?.status == TripStatus.Interrupted) {
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
                }
            }
        }
    }
    if (details && shown != null) TripDetailsSheet({ details = false }) {
        TripSummary(shown)
        Text(stringResource(R.string.map_start_end), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.map_privacy), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.trip_share_gpx_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TripSummary(detail: TripDetail) {
    val format = rememberDisplayFormat()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(activityText(detail.trip))
            Text(stringResource(detail.trip.status.title()))
            Metric(R.string.trip_distance, format.distance(detail.summary.distanceMeters))
            Metric(R.string.trip_duration, format.duration(detail.trip.durationNanos))
            Metric(R.string.maximum_speed, detail.summary.maximumSpeedMetersPerSecond?.let(format::speed))
            Metric(R.string.trip_points, format.whole(detail.positions))
            Metric(R.string.trip_segments, format.whole(detail.segments))
            Metric(R.string.trip_gaps, format.whole(detail.summary.gapCount))
            Text(stringResource(R.string.trip_gap_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}
