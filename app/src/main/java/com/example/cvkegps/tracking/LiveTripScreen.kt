package com.example.cvkegps.tracking

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.cvkegps.R
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
                MapWorkspace(detail?.summary?.lines, live = true) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(trip.status.title()), Modifier.testTag("recording_status"),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(reading?.speedMetersPerSecond?.let(format::speed) ?: stringResource(R.string.no_value),
                                Modifier.testTag("tracking_speed"), style = MaterialTheme.typography.headlineSmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(format.duration(elapsed), style = MaterialTheme.typography.titleMedium)
                            // The recorder updates the distance with each save, so it does not wait for the route.
                            Text(trip.distanceMeters?.let(format::distance) ?: stringResource(R.string.no_value),
                                Modifier.testTag("live_distance"), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TripActions(trip, actions.pause, { actions.resume(trip.id) }, { actions.stop(trip.id) })
                }
            }
        }
    }
    if (details && trip != null) TripDetailsSheet({ details = false }) {
        Metric(R.string.trip_ground_speed, reading?.speedMetersPerSecond?.let(format::speed))
        if (reading == null && trip.status == TripStatus.Recording) Text(stringResource(R.string.trip_signal_wait))
        TripTotals(trip.distanceMeters, distancePending = false, durationNanos = elapsed,
            maximumSpeed = detail?.summary?.maximumSpeedMetersPerSecond, maximumPending = detail == null)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Metric(R.string.trip_activity, activityText(trip)) }
            TextButton(onClick = { actions.chooseMode(trip.id) }, modifier = Modifier.testTag("change_activity")) {
                Text(stringResource(R.string.mode_change_short))
            }
        }
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
