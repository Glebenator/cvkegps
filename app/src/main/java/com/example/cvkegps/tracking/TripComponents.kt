package com.example.cvkegps.tracking

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.cvkegps.R

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
            Text(estimateText(actions.estimate),
                modifier = Modifier.testTag("activity_estimate"), style = MaterialTheme.typography.labelSmall)
        }
    }
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
internal fun MapWorkspace(lines: List<List<TrackSample>>?, live: Boolean = false, panel: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelHeight = maxHeight * 0.45f
        val panelWidth = (maxWidth * 0.36f).coerceIn(240.dp, 360.dp)
        if (maxWidth > maxHeight) Row(Modifier.fillMaxSize()) {
            MapSurface(lines.orEmpty(), Modifier.weight(1f).fillMaxHeight(), live, routeReady = lines != null)
            Surface(Modifier.width(panelWidth).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    panel()
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            MapSurface(lines.orEmpty(), Modifier.fillMaxWidth().weight(1f), live, routeReady = lines != null)
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

/** A pending value keeps its space and fades in, so the layout does not jump and no status message shows. */
@Composable
internal fun Metric(@StringRes title: Int, value: String?, pending: Boolean = false) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.labelMedium)
        PendingText(if (pending) null else value ?: stringResource(R.string.no_value), style = MaterialTheme.typography.titleMedium)
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
