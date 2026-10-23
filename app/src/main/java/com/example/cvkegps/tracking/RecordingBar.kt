package com.example.cvkegps.tracking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.cvkegps.R
import com.example.cvkegps.ui.rememberDisplayFormat

/** Shows the active trip above the navigation bar. The text area opens the live map. */
@Composable
fun RecordingBar(actions: TrackingActions, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val trip = actions.recording.trip ?: return
    val recording = trip.status == TripStatus.Recording
    val elapsed = rememberElapsed(actions.recording)
    Surface(modifier.fillMaxWidth().testTag("recording_bar"), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        // The text has 16 dp from the start edge through its touch area. The buttons use the same edge margin.
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = stringResource(R.string.trip_open_live), role = Role.Button, onClick = onOpen)
                .heightIn(min = 48.dp).padding(horizontal = 8.dp).testTag("show_route"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_record), contentDescription = null, Modifier.size(16.dp),
                    tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    Text(stringResource(trip.status.title()), Modifier.testTag("recording_status"),
                        style = MaterialTheme.typography.titleSmall)
                    Text(rememberDisplayFormat().duration(elapsed), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (recording) FilledTonalIconButton(onClick = actions.pause, modifier = Modifier.size(48.dp).testTag("trip_pause")) {
                Icon(painterResource(R.drawable.ic_pause), contentDescription = stringResource(R.string.trip_pause))
            } else FilledTonalIconButton(onClick = { actions.resume(trip.id) }, modifier = Modifier.size(48.dp).testTag("trip_resume")) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.trip_resume))
            }
            FilledIconButton(onClick = { actions.stop(trip.id) }, modifier = Modifier.size(48.dp).testTag("trip_stop")) {
                Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.trip_stop))
            }
        }
    }
}

/** The landscape form sits in the navigation rail, so the screen keeps its full height. */
@Composable
fun RecordingRailControls(actions: TrackingActions, onOpen: () -> Unit) {
    val trip = actions.recording.trip ?: return
    val recording = trip.status == TripStatus.Recording
    val status = stringResource(trip.status.title())
    Column(Modifier.testTag("recording_bar"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onOpen, modifier = Modifier.size(48.dp).testTag("show_route")
            .semantics { stateDescription = status }) {
            Icon(painterResource(R.drawable.ic_record), contentDescription = stringResource(R.string.trip_open_live),
                Modifier.size(16.dp), tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (recording) FilledTonalIconButton(onClick = actions.pause, modifier = Modifier.size(48.dp).testTag("trip_pause")) {
            Icon(painterResource(R.drawable.ic_pause), contentDescription = stringResource(R.string.trip_pause))
        } else FilledTonalIconButton(onClick = { actions.resume(trip.id) }, modifier = Modifier.size(48.dp).testTag("trip_resume")) {
            Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.trip_resume))
        }
        FilledIconButton(onClick = { actions.stop(trip.id) }, modifier = Modifier.size(48.dp).testTag("trip_stop")) {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.trip_stop))
        }
    }
}
