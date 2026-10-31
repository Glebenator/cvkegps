package com.example.cvkegps.tracking

import android.text.format.DateUtils
import android.os.SystemClock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.PaddingValues
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import com.example.cvkegps.ui.MetricCard
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.example.cvkegps.ui.theme.Motion
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ElevatedCard
import com.example.cvkegps.ui.theme.CvkeTheme
import com.example.cvkegps.ui.theme.ProblemBanner
import com.example.cvkegps.ui.theme.SecondaryButton
import com.example.cvkegps.ui.theme.Size
import com.example.cvkegps.ui.theme.Space
import com.example.cvkegps.location.TrackSample
import com.example.cvkegps.ui.rememberDisplayFormat

/**
 * The Speed tab slot. Pause and Stop move to the recording bar while a trip is active.
 * In landscape the slot shows the recording trip's values, because the navigation rail holds the controls.
 */
@Composable
fun TripStartControls(actions: TrackingActions, canStartTrip: Boolean, landscape: Boolean = false, onOpenTrip: () -> Unit = {}) {
    actions.error?.let { ProblemBanner(stringResource(it)) }
    val trip = actions.recording.trip
    if (trip == null && canStartTrip && actions.ready) {
        // The activity for the next trip. Automatic uses the estimate from recent GPS speed, and the icon shows that estimate.
        // The chip shows the short name. Screen readers hear the full choice, with "estimate" for an automatic activity.
        val selected = actions.selectedMode
        val chip = stringResource(selected?.title() ?: R.string.mode_auto)
        val spoken = when {
            selected != null -> stringResource(R.string.mode_chip_manual, stringResource(selected.title()))
            actions.estimate != TripMode.Unknown -> stringResource(R.string.mode_chip_estimate, stringResource(actions.estimate.title()))
            else -> stringResource(R.string.mode_chip_auto)
        }
        val start = stringResource(if (actions.recording.starting) R.string.trip_starting else R.string.trip_start)
        val activity: @Composable () -> Unit = {
            ActivityChip(chip, onClick = { actions.chooseMode(null) }, tag = "activity_estimate", icon = (selected ?: actions.estimate).icon(),
                trailing = R.drawable.ic_expand_more, spoken = spoken)
        }
        val button: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = actions.start, enabled = !actions.recording.starting, modifier = modifier.heightIn(min = Size.touch).testTag("trip_start")) {
                Icon(painterResource(R.drawable.ic_record), contentDescription = null, Modifier.size(Size.iconSm))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(start)
            }
        }
        // Choose, then start: the chip and the button share a row when both labels fit on one line. Otherwise they stack.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            fun width(text: String, style: TextStyle) = with(density) { measurer.measure(text, style).size.width.toDp() }
            // The chip adds two 18 dp icons and four 8 dp gaps. The button adds 24 dp padding on each side, an icon, and a gap.
            val chipWidth = width(chip, MaterialTheme.typography.labelLarge) + 18.dp * 2 + Space.s2 * 4
            val buttonWidth = width(start, MaterialTheme.typography.labelLarge) + Space.s6 * 2 + Size.iconSm + ButtonDefaults.IconSpacing
            if (maxWidth >= chipWidth + buttonWidth + Space.s3) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    activity()
                    button(Modifier.weight(1f))
                }
            } else Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                activity()
                button(Modifier.fillMaxWidth())
            }
        }
    } else if (trip != null && landscape) TripSummaryCard(actions, trip, onOpenTrip)
}

/**
 * Narrow portrait Speed tab while a trip is active: the trip's distance and average speed take the place of altitude and
 * maximum speed. The recording bar below shows the time. Without a trip, [otherwise] shows the usual cards.
 */
@Composable
fun TripMetricCards(actions: TrackingActions, otherwise: @Composable () -> Unit) {
    val trip = actions.recording.trip ?: return otherwise()
    val format = rememberDisplayFormat()
    val elapsed = rememberElapsed(actions.recording)
    val missing = stringResource(R.string.no_value)
    val distance = trip.distanceMeters?.let(format::distanceAmount)
    val average = averageSpeed(trip.distanceMeters, elapsed)
    Row(Modifier.height(IntrinsicSize.Min).testTag("trip_cards"), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        MetricCard(R.string.trip_distance, distance?.let(format::number) ?: missing, distance?.unit.orEmpty(), "trip_card_distance",
            Modifier.weight(1f).fillMaxHeight(), stringResource(R.string.trip_this_trip))
        MetricCard(R.string.trip_average_title, average?.let(format::speedNumber) ?: missing, format.speedUnit, "trip_card_average",
            Modifier.weight(1f).fillMaxHeight(), stringResource(R.string.trip_includes_stops))
    }
}

/** Landscape Speed tab: the recording trip's values. Pause and Stop are in the navigation rail. */
@Composable
private fun TripSummaryCard(actions: TrackingActions, trip: TripRecord, onOpenTrip: () -> Unit) {
    val format = rememberDisplayFormat()
    val elapsed = rememberElapsed(actions.recording)
    val noSignal = rememberSignalLost(actions.recording)
    val openLabel = stringResource(R.string.trip_open_live)
    // The whole card opens the current trip, so it needs no separate button.
    ElevatedCard(onClick = onOpenTrip, modifier = Modifier.fillMaxWidth().testTag("trip_summary").semantics {
        onClick(label = openLabel) { onOpenTrip(); true }
    }) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            TripStatusChip(trip.status, noSignal)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Metric(R.string.recording_metric_time, format.duration(elapsed))
                Metric(R.string.trip_distance, trip.distanceMeters?.let(format::distance))
                Metric(R.string.recording_metric_average, averageSpeed(trip.distanceMeters, elapsed)?.let(format::speed))
            }
        }
    }
}

/**
 * A pill chip that opens the activity choice: `surface`, a `border-control` outline, and `ink` text.
 * The optional [icon] shows the activity. [spoken] replaces the visible text for screen readers when the text is short.
 */
@Composable
internal fun ActivityChip(
    text: String,
    onClick: () -> Unit,
    tag: String = "change_activity",
    @DrawableRes icon: Int? = null,
    @DrawableRes trailing: Int = R.drawable.ic_edit,
    spoken: String? = null,
) {
    val colors = CvkeTheme.colors
    AssistChip(onClick = onClick,
        label = { Text(text, Modifier.testTag(tag).then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)) },
        leadingIcon = icon?.let { { Icon(painterResource(it), contentDescription = null, Modifier.size(Size.iconSm)) } },
        trailingIcon = { Icon(painterResource(trailing), contentDescription = null, Modifier.size(Size.iconSm)) },
        shape = CircleShape,
        colors = AssistChipDefaults.assistChipColors(containerColor = colors.surface, labelColor = colors.ink,
            leadingIconContentColor = colors.ink, trailingIconContentColor = colors.inkMuted),
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = colors.borderControl),
        modifier = Modifier.heightIn(min = Size.touch))
}

/** Recording, Paused, or No GPS signal. The colour and the text both show the state. */
@Composable
internal fun TripStatusChip(status: TripStatus, noSignal: Boolean, modifier: Modifier = Modifier, approximate: Boolean = false) {
    val recording = status == TripStatus.Recording
    val colors = CvkeTheme.colors
    // A lost signal is a problem, so it uses the danger tint. Otherwise the pill is neutral and the icon carries the state.
    Surface(modifier, shape = CircleShape, color = if (recording && noSignal) colors.dangerSubtle else colors.surfaceSunken,
        contentColor = colors.ink) {
        Row(Modifier.padding(horizontal = Space.s3, vertical = Space.s1), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            Icon(painterResource(if (recording) R.drawable.ic_record else R.drawable.ic_pause), contentDescription = null,
                Modifier.size(Size.iconSm), tint = if (recording) colors.danger else colors.inkMuted)
            Text(stringResource(when {
                recording && noSignal && approximate -> R.string.trip_network_position
                recording && noSignal -> R.string.trip_no_signal
                else -> status.title()
            }), Modifier.testTag("recording_status"), style = MaterialTheme.typography.labelLarge)
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
        val padding = PaddingValues(horizontal = Space.s4, vertical = Space.s2)
        val sideBySide = maxWidth >= textWidth + (16.dp * 2 + Size.iconSm + ButtonDefaults.IconSpacing) * 2 + 12.dp
        val pause: @Composable (Modifier) -> Unit = { modifier ->
            if (recording) SecondaryButton(onClick = onPause, modifier = modifier.testTag("trip_pause"), contentPadding = padding) {
                ButtonContent(R.drawable.ic_pause, R.string.trip_pause)
            } else SecondaryButton(onClick = onResume, modifier = modifier.testTag("trip_resume"), contentPadding = padding) {
                ButtonContent(R.drawable.ic_play, R.string.trip_resume)
            }
        }
        val stop: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = onStop, modifier = modifier.testTag("trip_stop"), contentPadding = padding) {
                ButtonContent(R.drawable.ic_stop, R.string.trip_stop)
            }
        }
        if (sideBySide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            pause(Modifier.weight(1f).heightIn(min = Size.touch))
            stop(Modifier.weight(1f).heightIn(min = Size.touch))
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            pause(Modifier.fillMaxWidth().heightIn(min = Size.touch))
            stop(Modifier.fillMaxWidth().heightIn(min = Size.touch))
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

/** True while the trip has no GPS signal and a current network position replaces it. It ends at the position's age limit. */
@Composable
internal fun rememberApproximate(state: RecordingState): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val approximate by produceState(state.approximate(SystemClock.elapsedRealtimeNanos()), state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = SystemClock.elapsedRealtimeNanos()
                value = state.approximate(now)
                // The state can change when the signal-loss time or the network position's age limit passes.
                val next = listOfNotNull(state.signalLostAtNanos, state.approximateUntilNanos).filter { it > now }.minOrNull() ?: break
                delay((next - now) / 1_000_000 + 1)
            }
        }
    }
    return approximate
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
    Icon(painterResource(icon), contentDescription = null, Modifier.size(Size.iconSm))
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
                Column(Modifier.verticalScroll(rememberScrollState()).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    panel()
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            MapSurface(lines.orEmpty(), Modifier.fillMaxWidth().weight(1f), live, routeReady = lines != null, position = position)
            Surface {
                Column(Modifier.fillMaxWidth().heightIn(max = panelHeight)
                    .verticalScroll(rememberScrollState()).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) { panel() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripDetailsSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.s4)
            .padding(bottom = Space.s6).testTag("trip_details_content"), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
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
    ProblemBanner(stringResource(R.string.trip_read_error), modifier.testTag("read_error")) {
        SecondaryButton(onClick = onRetry, modifier = Modifier.padding(top = Space.s2).heightIn(min = Size.touch).testTag("read_retry")) {
            Text(stringResource(R.string.trip_read_retry))
        }
    }
}

/** A pending value keeps its space and fades in, so the layout does not jump and no status message shows. [note] qualifies the value. */
@Composable
internal fun Metric(@StringRes title: Int, value: String?, pending: Boolean = false, tag: String? = null, note: String? = null) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PendingText(if (pending) null else value ?: stringResource(R.string.no_value),
            if (tag == null) Modifier else Modifier.testTag(tag), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The everyday trip values. Distance is the main value, with the [activity] on its line. Recording time, average speed,
 * and maximum speed follow in equal columns, so one long label does not set the layout. With large text they wrap.
 * A missing value shows as unavailable. A pending value keeps its space until it is known.
 */
@Composable
internal fun TripTotals(
    distanceMeters: Double?,
    distancePending: Boolean,
    durationNanos: Long?,
    maximumSpeed: Float?,
    maximumPending: Boolean,
    activity: String? = null,
) {
    val format = rememberDisplayFormat()
    val amount = distanceMeters?.let(format::distanceAmount)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.trip_distance), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
                    PendingText(if (distancePending) null else amount?.let(format::number) ?: stringResource(R.string.no_value),
                        Modifier.alignByBaseline().testTag("trip_distance"),
                        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
                    if (amount != null && !distancePending) Text(amount.unit, Modifier.alignByBaseline(), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (activity != null) Text(activity, Modifier.padding(bottom = Space.s1), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val values = listOf<@Composable () -> Unit>(
            { Metric(R.string.trip_duration, durationNanos?.let(format::duration), durationNanos == null) },
            { Metric(R.string.trip_average_title, durationNanos?.let { averageSpeed(distanceMeters, it) }?.let(format::speed),
                distancePending || durationNanos == null, "average_speed", note = stringResource(R.string.trip_includes_stops)) },
            { Metric(R.string.maximum_speed, maximumSpeed?.let(format::speed), maximumPending) },
        )
        if (LocalDensity.current.fontScale > 1.5f) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s2)) { values.forEach { it() } }
        } else Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            values.forEach { Box(Modifier.weight(1f)) { it() } }
        }
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
        // The section opens so the values below do not jump. It closes faster than it opens.
        AnimatedVisibility(open, enter = fadeIn(tween(Motion.ENTER_MILLIS, easing = Motion.standard)) +
            expandVertically(tween(Motion.ENTER_MILLIS, easing = Motion.standard)),
            exit = fadeOut(tween(Motion.EXIT_MILLIS, easing = Motion.standard)) + shrinkVertically(tween(Motion.EXIT_MILLIS, easing = Motion.standard))) {
            Column(Modifier.padding(top = Space.s1), verticalArrangement = Arrangement.spacedBy(Space.s3)) { content() }
        }
    }
}

/** Null is pending: the text keeps one line of space, is hidden from screen readers, and fades in when ready. */
@Composable
internal fun PendingText(value: String?, modifier: Modifier = Modifier, style: TextStyle, color: Color = Color.Unspecified) {
    val alpha by animateFloatAsState(if (value == null) 0f else 1f, tween(Motion.ENTER_MILLIS, easing = Motion.standard), label = "pending")
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

/** The activity icon. Combined activities use the icon of the first one. An unknown activity has the route icon. */
@DrawableRes internal fun TripMode.icon() = when (this) {
    TripMode.Unknown -> R.drawable.ic_route
    TripMode.CarOrTransit, TripMode.Car, TripMode.Transit -> R.drawable.ic_car
    TripMode.BikeOrSki, TripMode.Bike, TripMode.Ski -> R.drawable.ic_bike
    TripMode.Plane -> R.drawable.ic_plane
}

@StringRes internal fun TripStatus.title() = when (this) {
    TripStatus.Recording -> R.string.trip_recording
    TripStatus.Paused -> R.string.trip_paused
    TripStatus.Interrupted -> R.string.trip_interrupted
    TripStatus.Finished -> R.string.trip_finished
}
