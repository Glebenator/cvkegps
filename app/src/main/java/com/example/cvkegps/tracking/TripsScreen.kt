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
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cvkegps.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.example.cvkegps.ui.theme.CvkeTheme
import com.example.cvkegps.ui.theme.IconTile
import com.example.cvkegps.ui.theme.Motion
import androidx.compose.animation.core.tween
import com.example.cvkegps.ui.theme.ProblemBanner
import com.example.cvkegps.ui.theme.Radius
import com.example.cvkegps.ui.theme.Size
import com.example.cvkegps.ui.theme.SliceArt
import com.example.cvkegps.ui.theme.StatusText
import com.example.cvkegps.ui.theme.Space
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
            // Rows bring their own 16 dp padding, so the list keeps a narrow edge. Other items add the rest.
            verticalArrangement = Arrangement.spacedBy(Space.s1), contentPadding = PaddingValues(horizontal = Space.s2, vertical = Space.s4)) {
            if (compact) item { PaneHeader(stringResource(R.string.tracking_trips), onBack = null, Modifier.padding(horizontal = Space.s2)) { settingsButton() } }
            actions.error?.let { error -> item { ProblemBanner(stringResource(error), Modifier.padding(horizontal = Space.s2)) } }
            // A failed read is not an empty list. The user can read the trips again.
            if (read is StoredRead.Failed) item { ReadError(actions.vm::retryReads, Modifier.fillMaxWidth().padding(horizontal = Space.s2)) }
            // Before Room answers, show the empty list area instead of a status message.
            if (rows?.isEmpty() == true) item { EmptyTrips(Modifier.fillParentMaxSize()) }
            items(rows.orEmpty(), key = TripRecord::id, contentType = { "trip" }) { trip ->
                val live = trip.id == activeTripId
                // Entries fade in when the first database answer arrives, and move smoothly after a delete.
                val selected = trip.id == selectedId
                // An open row without a divider. The trip that shows beside the list has an accent-subtle fill.
                ListItem(
                    leadingContent = { IconTile(if (live) R.drawable.ic_record else R.drawable.ic_route) },
                    headlineContent = { Text(tripTime(trip.startedMillis), style = MaterialTheme.typography.titleSmall) },
                    // The distance is stored with the trip, so the list does not calculate routes.
                    supportingContent = {
                        Text(stringResource(R.string.trip_card_summary,
                            trip.distanceMeters?.let(format::distance) ?: stringResource(R.string.no_value),
                            format.duration(trip.durationNanos), activityText(trip)), Modifier.testTag("trip_card_summary"),
                            style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                            if (trip.status != TripStatus.Finished) StatusLabel(trip.status)
                            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, Modifier.size(Size.iconMd))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = if (selected) CvkeTheme.colors.accentSubtle else Color.Transparent),
                    modifier = Modifier.animateItem(fadeInSpec = tween(Motion.ENTER_MILLIS, easing = Motion.standard),
                        placementSpec = tween(Motion.EXIT_MILLIS, easing = Motion.standard), fadeOutSpec = tween(Motion.EXIT_MILLIS, easing = Motion.standard)).fillMaxWidth().clip(RoundedCornerShape(Radius.md))
                        .clickable { if (live) onLive() else onTrip(trip.id) }
                        .testTag("saved_trip").semantics { this.selected = selected },
                )
            }
        }
    }
}

/** The detail pane before the user selects a trip. */
@Composable
fun TripPlaceholder() {
    Column(Modifier.fillMaxSize().padding(Space.s6).testTag("trip_placeholder"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s3, Alignment.CenterVertically)) {
        SliceArt(Modifier.padding(bottom = Space.s2))
        Text(stringResource(R.string.trip_select), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** The empty state is a brand moment: a small slice composition, a heading, and one sentence. */
@Composable
private fun EmptyTrips(modifier: Modifier) {
    Column(modifier.padding(Space.s6).testTag("trips_empty"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s3, Alignment.CenterVertically)) {
        // The placeholder beside an empty list keeps its art still, so only one slice moves on the screen.
        SliceArt(Modifier.padding(bottom = Space.s2), animated = true)
        Text(stringResource(R.string.trip_none), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.trip_none_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A trip status as a dot and text. Recording is red, as on a camera. Paused and interrupted trips are warnings. */
@Composable
private fun StatusLabel(status: TripStatus) {
    StatusText(stringResource(status.title()),
        if (status == TripStatus.Recording) CvkeTheme.colors.danger else CvkeTheme.colors.warning)
}
