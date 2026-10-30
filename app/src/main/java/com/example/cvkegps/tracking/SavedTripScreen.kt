package com.example.cvkegps.tracking

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cvkegps.R
import com.example.cvkegps.location.FixSource
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import com.example.cvkegps.ui.theme.CvkeTheme
import com.example.cvkegps.ui.theme.ProblemBanner
import com.example.cvkegps.ui.theme.SecondaryButton
import com.example.cvkegps.ui.theme.Size
import com.example.cvkegps.ui.theme.Space
import com.example.cvkegps.ui.MapWithPane
import com.example.cvkegps.ui.PaneHeader
import com.example.cvkegps.ui.isCompactHeight
import com.example.cvkegps.ui.rememberDisplayFormat
import kotlinx.coroutines.launch

/**
 * A saved trip: the map and the totals. Replay changes the pane to the replay and keeps the same map, so the map does not
 * load again. Back, or the back button, leaves the replay. See ADR 0004.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedTripScreen(actions: TrackingActions, id: String, onBack: () -> Unit) {
    // The trip list selects the trip before navigation. This covers a screen restored after process loss.
    LaunchedEffect(id) { if (actions.vm.selectedId.value != id) actions.vm.select(id) }
    val rows by actions.vm.trips.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val detail by actions.vm.detail.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val shown = detail.ready?.takeIf { it.trip.id == id }
    // The list record is already in memory. The screen uses it until the route summary arrives.
    val trip = shown?.trip ?: rows.ready?.find { it.id == id }
    // A failed read shows an error and a retry action, not an empty trip.
    val readFailed = detail is StoredRead.Failed
    var details by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var replaying by rememberSaveable(id) { mutableStateOf(false) }
    val mode = rememberReplayMode(actions, id, replaying)
    // Back in the replay charts returns to the replay glance stop, and then to the trip.
    BackHandler(enabled = mode != null) { if (mode?.details == true) mode.setDetails(false) else replaying = false }
    val back = if (mode != null) { { replaying = false } } else onBack
    val openReplay = { replaying = true }
    val title = if (mode != null) stringResource(R.string.replay_title) else trip?.let { tripTime(it.startedMillis) } ?: ""
    val lines = shown?.summary?.lines
    val largeText = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE
    val chartsOpen = mode?.details == true
    val stopButton: @Composable () -> Unit = { if (mode?.player != null) StopButton(mode.details) { mode.setDetails(!mode.details) } }

    // Stop and save opens this screen. For a short time the trip can continue, which undoes an accidental stop.
    val savedMessage = stringResource(R.string.trip_saved_message)
    val resumeLabel = stringResource(R.string.trip_resume)
    LaunchedEffect(actions.justSaved) {
        if (actions.justSaved == id) {
            val result = snackbar.showSnackbar(savedMessage, resumeLabel, withDismissAction = true, duration = SnackbarDuration.Long)
            actions.consumeJustSaved()
            if (result == SnackbarResult.ActionPerformed) actions.reopen(id)
        }
    }
    // The replay shows the saved trip, so it closes the offer to continue the trip.
    LaunchedEffect(replaying) { if (replaying) snackbar.currentSnackbarData?.dismiss() }

    if (isCompactHeight()) {
        Box(Modifier.fillMaxSize().testTag("trip_review")) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= 600.dp) MapWithPane(map = { modifier, insets -> TripMap(lines, mode, modifier, insets, back) }, widePane = chartsOpen) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        if (mode != null) stopButton() else if (trip != null) TripMenu(actions, id, trip, shown, snackbar, openReplay)
                    }
                    // Landscape on a phone: one chart at a time in the replay details.
                    if (mode != null) ReplayPane(actions, mode, single = true, chartHeight = 120.dp)
                    else SavedTripContent(actions, id, trip, shown, readFailed = readFailed)
                } else {
                    // The detail pane beside the trip list: a header, the map, and the values below it.
                    val panelHeight = maxHeight * 0.45f
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))) {
                        PaneHeader(title, back, Modifier.padding(horizontal = Space.s1)) {
                            if (mode != null) stopButton() else if (trip != null) TripMenu(actions, id, trip, shown, snackbar, openReplay)
                        }
                        TripMap(lines, mode, Modifier.fillMaxWidth().weight(1f), WindowInsets(0), null)
                        Surface {
                            Column(Modifier.fillMaxWidth().heightIn(max = panelHeight).verticalScroll(rememberScrollState())
                                .padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                                if (mode != null) ReplayPane(actions, mode, single = true, chartHeight = 96.dp)
                                else SavedTripContent(actions, id, trip, shown, readFailed = readFailed)
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
        }
        return
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(back) },
            actions = {
                if (trip != null && mode == null) {
                    if (replayable(trip, shown)) IconButton(onClick = openReplay, modifier = Modifier.testTag("open_replay")) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.replay_open))
                    }
                    DetailsAction { details = true }
                    TripMenu(actions, id, trip, shown, snackbar, openReplay)
                }
            },
        )
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).testTag("trip_review")) {
            if (mode == null) {
                actions.error?.let { ProblemBanner(stringResource(it), Modifier.padding(horizontal = Space.s4, vertical = Space.s2)) }
                if (readFailed) ReadError(actions.vm::retryReads, Modifier.fillMaxWidth().padding(horizontal = Space.s4))
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val sideWidth = (maxWidth * 0.36f).coerceIn(240.dp, 360.dp)
                // The replay charts keep a strip of the map at the top. It follows the playhead.
                val strip = (maxHeight * 0.28f).coerceAtLeast(140.dp)
                val panelHeight = maxHeight * if (mode != null) 0.55f else 0.45f
                // A wide area, such as an open foldable or a tablet, uses the side pane. The replay charts make it wide.
                if (maxWidth >= 600.dp) MapWithPane(map = { modifier, _ -> TripMap(lines, mode, modifier, WindowInsets(0), null) },
                    edgeToEdge = false, widePane = chartsOpen) {
                    if (mode != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { stopButton() }
                        ReplayPane(actions, mode, single = largeText, chartHeight = if (largeText) 140.dp else 120.dp)
                    } else SavedTripContent(actions, id, trip, shown, showError = false)
                } else if (maxWidth > maxHeight) Row(Modifier.fillMaxSize()) {
                    TripMap(lines, mode, Modifier.weight(1f).fillMaxHeight(), WindowInsets(0), null)
                    Surface(Modifier.width(sideWidth).fillMaxHeight()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                            if (mode != null) {
                                stopButton()
                                ReplayPane(actions, mode, single = true, chartHeight = 96.dp)
                            } else PortraitTripPanel(actions, id, trip, shown)
                        }
                    }
                } else Column(Modifier.fillMaxSize()) {
                    TripMap(lines, mode, Modifier.fillMaxWidth().then(if (chartsOpen) Modifier.height(strip) else Modifier.weight(1f)),
                        WindowInsets(0), null)
                    Surface(Modifier.then(if (chartsOpen) Modifier.weight(1f) else Modifier)) {
                        Column(Modifier.fillMaxWidth().then(if (chartsOpen) Modifier else Modifier.heightIn(max = panelHeight))) {
                            if (mode?.player != null) SheetHandle(mode.details, onChange = mode.setDetails)
                            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                .padding(start = Space.s4, end = Space.s4, bottom = Space.s4, top = if (mode?.player != null) 0.dp else Space.s4),
                                verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                                // At the largest text sizes, one chart at a time keeps the charts readable.
                                if (mode != null) ReplayPane(actions, mode, single = largeText, chartHeight = if (largeText) 140.dp else 96.dp)
                                else PortraitTripPanel(actions, id, trip, shown)
                            }
                        }
                    }
                }
            }
        }
    }
    if (details && shown != null && mode == null) TripDetailsSheet({ details = false }) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                Column {
                    Text(activityText(shown.trip), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(shown.trip.status.title()), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SavedTotals(shown.trip, shown)
            }
        }
        SavedTechnicalDetails(shown)
        PrivacyNote()
    }
}

/** Portrait content: the totals and, for an interrupted trip, its actions. The details sheet shows the rest. */
@Composable
private fun PortraitTripPanel(actions: TrackingActions, id: String, trip: TripRecord?, shown: TripDetail?) {
    SavedTotals(trip, shown)
    InterruptedActions(actions, id, trip)
}

/** Landscape content: everything that the portrait details sheet shows, inside the pane. */
@Composable
private fun ColumnScope.SavedTripContent(
    actions: TrackingActions, id: String, trip: TripRecord?, shown: TripDetail?, showError: Boolean = true, readFailed: Boolean = false,
) {
    if (showError) actions.error?.let { ProblemBanner(stringResource(it)) }
    if (readFailed) ReadError(actions.vm::retryReads)
    if (trip != null) ActivityChip(activityText(trip), onClick = { actions.chooseMode(id) })
    SavedTotals(trip, shown)
    InterruptedActions(actions, id, trip)
    if (shown != null) SavedTechnicalDetails(shown)
    PrivacyNote()
}

@Composable
private fun InterruptedActions(actions: TrackingActions, id: String, trip: TripRecord?) {
    if (trip?.status != TripStatus.Interrupted) return
    Text(stringResource(R.string.trip_interrupted_note), style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        Button(onClick = { actions.resume(id) }, enabled = actions.recording.trip == null,
            modifier = Modifier.weight(1f).heightIn(min = Size.touch)) {
            ButtonContent(R.drawable.ic_play, R.string.trip_resume)
        }
        SecondaryButton(onClick = { actions.finish(id) }, modifier = Modifier.weight(1f).heightIn(min = Size.touch)) {
            Text(stringResource(R.string.trip_finish))
        }
    }
}

@Composable
private fun SavedTechnicalDetails(shown: TripDetail) {
    val format = rememberDisplayFormat()
    TechnicalDetails {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Metric(R.string.trip_points, format.whole(shown.positions))
            Metric(R.string.trip_segments, format.whole(shown.segments))
            Metric(R.string.trip_gaps, format.whole(shown.summary.gapCount))
        }
        Text(stringResource(R.string.trip_gap_note), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.map_start_end), style = MaterialTheme.typography.bodySmall)
        if (shown.summary.lines.any { line -> line.firstOrNull()?.source == FixSource.Network }) {
            Text(stringResource(R.string.map_network_legend), Modifier.testTag("network_legend"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PrivacyNote() {
    Text(stringResource(R.string.trip_privacy), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Change activity, Share as GPX, and Delete trip. */
@Composable
private fun TripMenu(actions: TrackingActions, id: String, trip: TripRecord, shown: TripDetail?, snackbar: SnackbarHostState, onReplay: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exportError = stringResource(R.string.trip_export_error)
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("trip_menu")) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            // A recording trip still changes, so only a saved trip with positions can replay.
            if (replayable(trip, shown)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.replay_open)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_play), contentDescription = null) },
                    onClick = { menu = false; onReplay() }, modifier = Modifier.testTag("trip_replay"))
            }
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
            // Delete is destructive, so it is last, after a divider, in the danger colour.
            if (trip.status == TripStatus.Finished || trip.status == TripStatus.Interrupted) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.trip_delete)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                    colors = MenuDefaults.itemColors(textColor = CvkeTheme.colors.danger, leadingIconColor = CvkeTheme.colors.danger),
                    onClick = { menu = false; actions.delete(id) }, modifier = Modifier.testTag("trip_delete"))
            }
        }
    }
}

/** The route summary is exact. The stored distance shows until it arrives. */
@Composable
private fun SavedTotals(trip: TripRecord?, shown: TripDetail?) {
    val distance = shown?.summary?.distanceMeters ?: trip?.distanceMeters
    TripTotals(distance, distancePending = trip == null || (distance == null && shown == null), durationNanos = trip?.durationNanos,
        maximumSpeed = shown?.summary?.maximumSpeedMetersPerSecond, maximumPending = shown == null)
}

private fun replayable(trip: TripRecord, shown: TripDetail?) =
    (trip.status == TripStatus.Finished || trip.status == TripStatus.Interrupted) && shown?.summary?.lines?.isNotEmpty() == true
