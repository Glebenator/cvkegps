package com.example.cvkegps.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cvkegps.R
import com.example.cvkegps.ui.rememberDisplayFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(actions: TrackingActions, onTrip: (String) -> Unit, onLive: () -> Unit) {
    val format = rememberDisplayFormat()
    val rows by actions.vm.trips.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tracking_trips)) }) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).testTag("trips_content"),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(16.dp)) {
            actions.error?.let { error -> item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) } }
            // Before Room answers, show the empty list area instead of a status message.
            if (rows?.isEmpty() == true) item { EmptyTrips(Modifier.fillParentMaxSize()) }
            items(rows.orEmpty(), key = TripRecord::id) { trip ->
                val live = trip.id == actions.recording.trip?.id
                // Entries fade in when the first database answer arrives, and move smoothly after a delete.
                Card(onClick = { if (live) onLive() else onTrip(trip.id) },
                    modifier = Modifier.animateItem().fillMaxWidth().testTag("saved_trip")) {
                    ListItem(
                        headlineContent = { Text(tripTime(trip.startedMillis)) },
                        supportingContent = {
                            Text(stringResource(R.string.trip_summary_line, activityText(trip), format.duration(trip.durationNanos)))
                        },
                        trailingContent = if (trip.status != TripStatus.Finished) { { StatusLabel(trip.status) } } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTrips(modifier: Modifier) {
    Column(modifier.padding(24.dp).testTag("trips_empty"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Icon(painterResource(R.drawable.ic_route), contentDescription = null, Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.trip_none), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.trip_none_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatusLabel(status: TripStatus) {
    val active = status == TripStatus.Recording || status == TripStatus.Paused
    Surface(shape = MaterialTheme.shapes.small,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer) {
        Text(stringResource(status.title()), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}
