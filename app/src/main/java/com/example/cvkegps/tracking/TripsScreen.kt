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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.example.cvkegps.ui.PaneHeader
import com.example.cvkegps.ui.isCompactHeight
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
fun TripsScreen(actions: TrackingActions, onTrip: (String) -> Unit, onLive: () -> Unit, onSettings: (() -> Unit)?, selectedId: String? = null) {
    val format = rememberDisplayFormat()
    val read by actions.vm.trips.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val rows = read.ready
    val activeTripId = actions.activeTripId
    // A failed delete shows its error here. It does not stay after the user leaves the list.
    DisposableEffect(actions.vm) { onDispose { actions.vm.clearStorageError() } }
    val compact = isCompactHeight()
    // With a navigation rail, Settings is at the top of the rail instead.
    val settingsButton: @Composable () -> Unit = {
        if (onSettings != null) IconButton(onClick = onSettings, modifier = Modifier.testTag("open_settings")) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
        }
    }
    // In landscape the title scrolls with the list instead of using a top app bar.
    Scaffold(topBar = { if (!compact) TopAppBar(title = { Text(stringResource(R.string.tracking_trips)) }, actions = { settingsButton() }) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).testTag("trips_content"),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(16.dp)) {
            if (compact) item { PaneHeader(stringResource(R.string.tracking_trips), onBack = null) { settingsButton() } }
            actions.error?.let { error -> item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) } }
            // A failed read is not an empty list. The user can read the trips again.
            if (read is StoredRead.Failed) item { ReadError(actions.vm::retryReads, Modifier.fillMaxWidth()) }
            // Before Room answers, show the empty list area instead of a status message.
            if (rows?.isEmpty() == true) item { EmptyTrips(Modifier.fillParentMaxSize()) }
            items(rows.orEmpty(), key = TripRecord::id, contentType = { "trip" }) { trip ->
                val live = trip.id == activeTripId
                // Entries fade in when the first database answer arrives, and move smoothly after a delete.
                val selected = trip.id == selectedId
                // The trip that shows beside the list has a highlighted card.
                Card(onClick = { if (live) onLive() else onTrip(trip.id) },
                    modifier = Modifier.animateItem().fillMaxWidth().testTag("saved_trip").semantics { this.selected = selected },
                    colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()) {
                    ListItem(
                        headlineContent = { Text(tripTime(trip.startedMillis)) },
                        // The distance is stored with the trip, so the list does not calculate routes.
                        supportingContent = {
                            Text(stringResource(R.string.trip_card_summary,
                                trip.distanceMeters?.let(format::distance) ?: stringResource(R.string.no_value),
                                format.duration(trip.durationNanos), activityText(trip)), Modifier.testTag("trip_card_summary"))
                        },
                        trailingContent = if (trip.status != TripStatus.Finished) { { StatusLabel(trip.status) } } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

/** The detail pane before the user selects a trip. */
@Composable
fun TripPlaceholder() {
    Column(Modifier.fillMaxSize().padding(24.dp).testTag("trip_placeholder"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Icon(painterResource(R.drawable.ic_route), contentDescription = null, Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.trip_select), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
