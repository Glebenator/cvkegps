package com.example.cvkegps.tracking

import android.text.format.DateUtils
import android.os.SystemClock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.PaddingValues
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.cvkegps.R
import com.example.cvkegps.location.TrackSample
import com.example.cvkegps.ui.rememberDisplayFormat

/**
 * The Speed tab slot. Pause and Stop move to the recording bar while a trip is active.
 * In landscape the slot shows the recording trip's values, because the navigation rail holds the controls.
 */
@Composable
fun TripStartControls(actions: TrackingActions, canStartTrip: Boolean, landscape: Boolean = false, onOpenTrip: () -> Unit = {}) {
    actions.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    val trip = actions.recording.trip
    if (trip == null && canStartTrip && actions.ready) {
        Button(onClick = actions.start, enabled = !actions.recording.starting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("trip_start")) {
            Icon(painterResource(R.drawable.ic_record), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(if (actions.recording.starting) R.string.trip_starting else R.string.trip_start))
        }
        // The activity for the next trip. Automatic uses the estimate from recent GPS speed.
        val selected = actions.selectedMode
        ActivityChip(when {
            selected != null -> stringResource(R.string.mode_chip_manual, stringResource(selected.title()))
            actions.estimate != TripMode.Unknown -> stringResource(R.string.mode_chip_estimate, stringResource(actions.estimate.title()))
            else -> stringResource(R.string.mode_chip_auto)
        }, onClick = { actions.chooseMode(null) }, tag = "activity_estimate")
    } else if (trip != null && landscape) TripSummaryCard(actions, trip, onOpenTrip)
}

/** Landscape Speed tab: the recording trip's values. Pause and Stop are in the navigation rail. */
@Composable
private fun TripSummaryCard(actions: TrackingActions, trip: TripRecord, onOpenTrip: () -> Unit) {
    val format = rememberDisplayFormat()
    val elapsed = rememberElapsed(actions.recording)
    val noSignal = rememberSignalLost(actions.recording)
    val openLabel = stringResource(R.string.trip_open_live)
    // The whole card opens the current trip, so it needs no separate button.
    Card(onClick = onOpenTrip, modifier = Modifier.fillMaxWidth().testTag("trip_summary").semantics {
        onClick(label = openLabel) { onOpenTrip(); true }
    }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TripStatusChip(trip.status, noSignal)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(R.string.recording_metric_time, format.duration(elapsed))
                Metric(R.string.trip_distance, trip.distanceMeters?.let(format::distance))
                Metric(R.string.recording_metric_average, averageSpeed(trip.distanceMeters, elapsed)?.let(format::speed))
            }
        }
    }
}

/** An assist chip that opens the activity choice. */
@Composable
internal fun ActivityChip(text: String, onClick: () -> Unit, tag: String = "change_activity") {
    AssistChip(onClick = onClick, label = { Text(text, Modifier.testTag(tag)) },
        trailingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
        modifier = Modifier.heightIn(min = 48.dp))
}

/** Recording, Paused, or No GPS signal. The colour and the text both show the state. */
@Composable
internal fun TripStatusChip(status: TripStatus, noSignal: Boolean, modifier: Modifier = Modifier) {
    val recording = status == TripStatus.Recording
    val container = when {
        recording && noSignal -> MaterialTheme.colorScheme.errorContainer
        recording -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Surface(modifier, shape = MaterialTheme.shapes.small, color = container) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(if (recording) R.drawable.ic_record else R.drawable.ic_pause), contentDescription = null,
                Modifier.size(14.dp), tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if (recording && noSignal) R.string.trip_no_signal else status.title()),
                Modifier.testTag("recording_status"), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Pause or Resume, and Stop and save. The buttons stand side by side when both labels fit on one line.
 * Otherwise they stack, so a label never breaks inside a word.
 */
@Composable
internal fun TripActions(trip: TripRecord, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Measure the real labels in the current language and text size. Each button adds padding, an icon, and a gap.
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelLarge
        val density = LocalDensity.current
        val recording = trip.status == TripStatus.Recording
        val labels = listOf(stringResource(if (recording) R.string.trip_pause else R.string.trip_resume), stringResource(R.string.trip_stop))
        val textWidth = labels.sumOf { with(density) { measurer.measure(it, style).size.width.toDp() }.value.toDouble() }.dp
        val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        val sideBySide = maxWidth >= textWidth + (16.dp * 2 + ButtonDefaults.IconSize + ButtonDefaults.IconSpacing) * 2 + 12.dp
        val pause: @Composable (Modifier) -> Unit = { modifier ->
            if (recording) FilledTonalButton(onClick = onPause, modifier = modifier.testTag("trip_pause"), contentPadding = padding) {
                ButtonContent(R.drawable.ic_pause, R.string.trip_pause)
            } else FilledTonalButton(onClick = onResume, modifier = modifier.testTag("trip_resume"), contentPadding = padding) {
                ButtonContent(R.drawable.ic_play, R.string.trip_resume)
            }
        }
        val stop: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = onStop, modifier = modifier.testTag("trip_stop"), contentPadding = padding) {
                ButtonContent(R.drawable.ic_stop, R.string.trip_stop)
            }
        }
        if (sideBySide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pause(Modifier.weight(1f).heightIn(min = 48.dp))
            stop(Modifier.weight(1f).heightIn(min = 48.dp))
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pause(Modifier.fillMaxWidth().heightIn(min = 56.dp))
            stop(Modifier.fillMaxWidth().heightIn(min = 56.dp))
        }
    }
}

/** True while a recording trip has had no fix for 10 seconds. It changes only at that time and at the next fix. */
@Composable
internal fun rememberSignalLost(state: RecordingState): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lost by produceState(state.signalLost(SystemClock.elapsedRealtimeNanos()), state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val now = SystemClock.elapsedRealtimeNanos()
            value = state.signalLost(now)
            val at = state.signalLostAtNanos
            if (at != null && at > now) {
                delay((at - now) / 1_000_000 + 1)
                value = true
            }
        }
    }
    return lost
}

/**
 * True while [sample] is a current position of a recording trip. It changes to false at the sample's own age limit,
 * also when no new point arrives.
 */
@Composable
internal fun rememberPositionCurrent(sample: TrackSample?, recording: Boolean): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun current(now: Long) = recording && sample != null && RouteMath.positionCurrent(sample.elapsedNanos, now)
    val fresh by produceState(current(SystemClock.elapsedRealtimeNanos()), sample, recording, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val now = SystemClock.elapsedRealtimeNanos()
            value = current(now)
            if (value && sample != null) {
                delay((sample.elapsedNanos + RouteMath.SIGNAL_GAP_NANOS - now) / 1_000_000 + 1)
                value = false
            }
        }
    }
    return fresh
}

@Composable
internal fun activityText(trip: TripRecord): String = estimateText(trip.mode, trip.automaticMode)

@Composable
private fun estimateText(mode: TripMode, automatic: Boolean = true): String =
    if (automatic && mode != TripMode.Unknown) stringResource(R.string.mode_estimate, stringResource(mode.title()))
    else stringResource(mode.title())

/** Date and time, with the year only when it is not the current year. Two trips on one day have different titles. */
@Composable
internal fun tripTime(millis: Long): String {
    val context = LocalContext.current
    return remember(millis, LocalConfiguration.current) {
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.testTag("top_back")) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
    }
}

@Composable
internal fun ButtonContent(icon: Int, @StringRes label: Int) {
    Icon(painterResource(icon), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(stringResource(label))
}

/** Keep map gestures separate from the scrolling measurements. Null lines mean the route is still loading. */
@Composable
internal fun MapWorkspace(lines: List<List<TrackSample>>?, live: Boolean = false, position: LivePosition? = null, panel: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelHeight = maxHeight * 0.45f
        val panelWidth = (maxWidth * 0.36f).coerceIn(240.dp, 360.dp)
        if (maxWidth > maxHeight) Row(Modifier.fillMaxSize()) {
            MapSurface(lines.orEmpty(), Modifier.weight(1f).fillMaxHeight(), live, routeReady = lines != null, position = position)
            Surface(Modifier.width(panelWidth).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    panel()
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            MapSurface(lines.orEmpty(), Modifier.fillMaxWidth().weight(1f), live, routeReady = lines != null, position = position)
            Surface {
                Column(Modifier.fillMaxWidth().heightIn(max = panelHeight)
                    .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { panel() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripDetailsSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = 24.dp).testTag("trip_details_content"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trip_details), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("close_trip_details")) {
                    Text(stringResource(R.string.trip_close))
                }
            }
            content()
        }
    }
}

@Composable
internal fun DetailsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("trip_details")) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.trip_details))
    }
}

/** A failed read of saved trips, with an action that reads them again. */
@Composable
internal fun ReadError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.testTag("read_error"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.trip_read_error), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("read_retry")) {
            Text(stringResource(R.string.trip_read_retry))
        }
    }
}

/** A pending value keeps its space and fades in, so the layout does not jump and no status message shows. */
@Composable
internal fun Metric(@StringRes title: Int, value: String?, pending: Boolean = false, tag: String? = null) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.labelMedium)
        PendingText(if (pending) null else value ?: stringResource(R.string.no_value),
            if (tag == null) Modifier else Modifier.testTag(tag), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * The everyday trip values: distance, recording time, average speed, and maximum speed.
 * A missing value shows as unavailable. A pending value keeps its space until it is known.
 */
@Composable
internal fun TripTotals(
    distanceMeters: Double?,
    distancePending: Boolean,
    durationNanos: Long?,
    maximumSpeed: Float?,
    maximumPending: Boolean,
) {
    val format = rememberDisplayFormat()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric(R.string.trip_distance, distanceMeters?.let(format::distance), distancePending, "trip_distance")
        Metric(R.string.trip_duration, durationNanos?.let(format::duration), durationNanos == null)
        Metric(R.string.trip_average_speed, durationNanos?.let { averageSpeed(distanceMeters, it) }?.let(format::speed),
            distancePending || durationNanos == null, "average_speed")
        Metric(R.string.maximum_speed, maximumSpeed?.let(format::speed), maximumPending)
    }
}

/** A collapsed section for values that most users do not need. */
@Composable
internal fun TechnicalDetails(content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (open) R.string.state_expanded else R.string.state_collapsed)
    Column {
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button) { open = !open }
            .semantics { stateDescription = state }
            .heightIn(min = 48.dp).testTag("technical_details"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.trip_technical_details), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, Modifier.rotate(if (open) 180f else 0f))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

/** Null is pending: the text keeps one line of space, is hidden from screen readers, and fades in when ready. */
@Composable
internal fun PendingText(value: String?, modifier: Modifier = Modifier, style: TextStyle, color: Color = Color.Unspecified) {
    val alpha by animateFloatAsState(if (value == null) 0f else 1f, label = "pending")
    val hidden = if (value == null) Modifier.clearAndSetSemantics { } else Modifier
    Text(value ?: " ", modifier.graphicsLayer { this.alpha = alpha }.then(hidden), style = style, color = color)
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
