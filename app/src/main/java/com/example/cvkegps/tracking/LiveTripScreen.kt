package com.example.cvkegps.tracking

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.cvkegps.R
import com.example.cvkegps.location.GpsReading
import com.example.cvkegps.ui.MapWithPane
import com.example.cvkegps.ui.isCompactHeight
import com.example.cvkegps.ui.isWideWindow
import com.example.cvkegps.ui.rememberDisplayFormat
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTripScreen(actions: TrackingActions, onBack: () -> Unit, onEnded: () -> Unit) {
    val format = rememberDisplayFormat()
    val state = actions.recording
    val trip = state.trip
    val detail by actions.vm.activeDetail.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val reading by actions.vm.reading.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    var details by rememberSaveable { mutableStateOf(false) }
    val elapsed = rememberElapsed(state)
    val noSignal = rememberSignalLost(state)
    // Close this screen when the trip ends elsewhere, for example from the notification.
    val seen = remember { booleanArrayOf(false) }
    LaunchedEffect(trip == null) { if (trip != null) seen[0] = true else if (seen[0]) onEnded() }
    val lines = detail?.summary?.lines
    // The newest saved point is the position marker. An old fix shows it grey and without a direction.
    val position = lines?.lastOrNull { it.isNotEmpty() }?.last()?.let { LivePosition(it, fresh = reading != null) }
    // The side pane shows details in place, so the wide layouts do not use the sheet.
    val wideTrip = isWideWindow()

    // The side pane: status, a large speed, time and distance, the activity, and details that open in place.
    val pane: @Composable ColumnScope.() -> Unit = {
        if (trip != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TripStatusChip(trip.status, noSignal)
                Spacer(Modifier.weight(1f))
                IconToggleButton(checked = details, onCheckedChange = { details = it }, modifier = Modifier.testTag("trip_details")) {
                    Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.trip_details))
                }
            }
            actions.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            LargeSpeed(reading)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(R.string.recording_metric_time, format.duration(elapsed))
                Metric(R.string.trip_distance, trip.distanceMeters?.let(format::distance), tag = "live_distance")
            }
            ActivityChip(activityText(trip), onClick = { actions.chooseMode(trip.id) })
            if (details) Column(Modifier.testTag("trip_details_content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveDetails(trip, detail, reading, elapsed, includeTotals = true)
            }
        }
    }
    val buttons: @Composable ColumnScope.() -> Unit = {
        if (trip != null) TripActions(trip, actions.pause, { actions.resume(trip.id) }, { actions.stop(trip.id) })
    }

    if (isCompactHeight() && trip != null) {
        // Landscape on a phone: a full-height map and a side pane, without a top app bar.
        MapWithPane(
            map = { modifier, insets -> MapSurface(lines.orEmpty(), modifier, live = true, routeReady = lines != null,
                insets = insets, onBack = onBack, position = position) },
            bottom = buttons, pane = pane,
        )
        return
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.trip_live_title)) }, navigationIcon = { BackButton(onBack) },
            actions = { if (trip != null && !wideTrip) DetailsAction { details = true } })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            actions.error?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            if (trip == null) Text(stringResource(R.string.trip_starting), Modifier.padding(16.dp))
            else Box(Modifier.weight(1f)) {
                // A wide window, such as an open foldable, uses the same side pane as landscape.
                if (wideTrip) MapWithPane(
                    map = { modifier, _ -> MapSurface(lines.orEmpty(), modifier, live = true, routeReady = lines != null, position = position) },
                    edgeToEdge = false, bottom = buttons, pane = pane,
                ) else MapWorkspace(lines, live = true, position = position) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TripStatusChip(trip.status, noSignal)
                            // One line: a long value gets smaller instead of breaking between the number and the unit.
                            BasicText(reading?.speedMetersPerSecond?.let(format::speed) ?: stringResource(R.string.no_value),
                                Modifier.testTag("tracking_speed"), maxLines = 1,
                                style = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = MaterialTheme.typography.headlineSmall.fontSize))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(format.duration(elapsed), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            // The recorder updates the distance with each save, so it does not wait for the route.
                            Text(trip.distanceMeters?.let(format::distance) ?: stringResource(R.string.no_value),
                                Modifier.testTag("live_distance"), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    TripActions(trip, actions.pause, { actions.resume(trip.id) }, { actions.stop(trip.id) })
                }
            }
        }
    }
    if (details && trip != null && !wideTrip) TripDetailsSheet({ details = false }) {
        Metric(R.string.trip_ground_speed, reading?.speedMetersPerSecond?.let(format::speed))
        if (reading == null && trip.status == TripStatus.Recording) Text(stringResource(R.string.trip_signal_wait))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Metric(R.string.trip_activity, activityText(trip)) }
            TextButton(onClick = { actions.chooseMode(trip.id) }, modifier = Modifier.testTag("change_activity")) {
                Text(stringResource(R.string.mode_change_short))
            }
        }
        LiveDetails(trip, detail, reading, elapsed, includeTotals = true)
    }
}

/** The landscape speed: the largest value in the pane. The number gets smaller instead of breaking. */
@Composable
private fun LargeSpeed(reading: GpsReading?) {
    val format = rememberDisplayFormat()
    val speed = reading?.speedMetersPerSecond
    val description = if (speed != null) stringResource(R.string.speed_accessibility, format.speedNumber(speed))
    else stringResource(R.string.speed_unavailable_accessibility)
    Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
        Text(stringResource(R.string.current_speed), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(speed?.let(format::speedNumber) ?: stringResource(R.string.no_value),
                Modifier.weight(1f, fill = false).alignByBaseline().testTag("tracking_speed"), maxLines = 1,
                style = MaterialTheme.typography.displayLarge.copy(color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                // The speed is already the largest text. Large text does not make it larger, so the other values keep their room.
                autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = (72f / LocalDensity.current.fontScale).coerceAtLeast(28f).sp))
            // A dash with a unit reads like a value. Show the unit only with a speed.
            if (speed != null) Text(stringResource(R.string.speed_unit), Modifier.alignByBaseline(), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Values that most users need only sometimes. The portrait sheet and the landscape pane show the same set. */
@Composable
private fun LiveDetails(trip: TripRecord, detail: TripDetail?, reading: GpsReading?, elapsed: Long, includeTotals: Boolean) {
    val format = rememberDisplayFormat()
    if (includeTotals) TripTotals(trip.distanceMeters, distancePending = false, durationNanos = elapsed,
        maximumSpeed = detail?.summary?.maximumSpeedMetersPerSecond, maximumPending = detail == null)
    TechnicalDetails {
        Metric(R.string.trip_altitude, reading?.altitudeMeters?.let {
            stringResource(R.string.trip_altitude_value, format.decimal(it))
        })
        Metric(R.string.trip_quality, reading?.accuracyMeters?.let {
            stringResource(R.string.trip_uncertainty_value, format.decimal(it.toDouble()))
        })
        if (trip.mode == TripMode.Plane) {
            Metric(R.string.trip_track, reading?.bearingRadians?.let {
                stringResource(R.string.trip_track_value, format.decimal(Math.toDegrees(it)))
            })
            Metric(R.string.trip_vertical_quality, reading?.verticalAccuracyMeters?.let {
                stringResource(R.string.trip_uncertainty_value, format.decimal(it.toDouble()))
            })
            Text(stringResource(R.string.trip_plane_note), style = MaterialTheme.typography.bodySmall)
        }
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
                val now = SystemClock.elapsedRealtimeNanos()
                value = state.segmentBaseDurationNanos + (now - (state.segmentStartNanos ?: now)).coerceAtLeast(0)
                delay(1_000)
            }
        }
    }
    return elapsed
}
