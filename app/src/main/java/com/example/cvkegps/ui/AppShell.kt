package com.example.cvkegps.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cvkegps.tracking.TripPlaceholder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import com.example.cvkegps.R
import com.example.cvkegps.tracking.LiveTripScreen
import com.example.cvkegps.tracking.RecordingBar
import com.example.cvkegps.tracking.RecordingRailControls
import com.example.cvkegps.tracking.SavedTripScreen
import com.example.cvkegps.tracking.TrackingActions
import com.example.cvkegps.tracking.TrackingApplication
import com.example.cvkegps.tracking.TrackingHost
import com.example.cvkegps.tracking.TripStartControls
import com.example.cvkegps.tracking.TripsScreen

// Material motion durations. Navigation 3 uses a 700 ms fade by default, which feels slow.
private const val TRANSITION_MILLIS = 300
private const val FADE_OUT_MILLIS = 90

/** Fade through: the old screen fades out quickly, then the new screen fades in. Tabs use it, so they change in place. */
private fun fadeThrough(): ContentTransform =
    fadeIn(tween(TRANSITION_MILLIS - FADE_OUT_MILLIS, delayMillis = FADE_OUT_MILLIS, easing = LinearOutSlowInEasing)) togetherWith
        fadeOut(tween(FADE_OUT_MILLIS, easing = FastOutLinearInEasing))

/** Shared horizontal axis: a short shift with a fade through. A new screen comes from the end side; Back reverses it. */
private fun sharedAxis(forward: Boolean, rtl: Boolean): ContentTransform {
    val direction = if (forward != rtl) 1 else -1
    val slide = tween<IntOffset>(TRANSITION_MILLIS, easing = FastOutSlowInEasing)
    val fade = fadeThrough()
    return (slideInHorizontally(slide) { width -> direction * width / 12 } + fade.targetContentEnter) togetherWith
        (slideOutHorizontally(slide) { width -> -direction * width / 12 } + fade.initialContentExit)
}

private val tabTransition = NavDisplay.transitionSpec { fadeThrough() } +
    NavDisplay.popTransitionSpec { fadeThrough() } +
    NavDisplay.predictivePopTransitionSpec { fadeThrough() }

/**
 * Where a saved trip can show beside the trip list, for example on an open foldable or a tablet.
 * A phone in landscape is too short for a map in a narrow detail pane, so it shows one screen at a time.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun tripPaneDirective(): PaneScaffoldDirective {
    val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())
    return if (isCompactHeight()) directive.copy(maxHorizontalPartitions = 1) else directive
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppShell(
    app: TrackingApplication,
    speedometer: SpeedometerViewModel,
    compass: CompassViewModel?,
    openTripsRequest: Int,
) {
    val navigator = rememberAppNavigator()
    val access = rememberGpsAccess(speedometer) ?: return
    val directive = tripPaneDirective()
    val listDetail = directive.maxHorizontalPartitions > 1
    val tripsPane = remember { ListDetailSceneStrategy.listPane(detailPlaceholder = { TripPlaceholder() }) }
    val tripPane = remember { ListDetailSceneStrategy.detailPane() }
    // Back closes the trip beside the list. The list and the Trips tab stay.
    val sceneStrategy = rememberListDetailSceneStrategy<Destination>(
        backNavigationBehavior = BackNavigationBehavior.PopLatest, directive = directive)
    TrackingHost(app, navigator, openTripsRequest) { tracking ->
        // The window background is transparent. Screens fade and the bars change height, so paint the theme background behind them.
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // A navigation rail in landscape, and on wide windows such as an open foldable.
            val wide = maxWidth > maxHeight || maxWidth >= 600.dp
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            // A saved trip beside the trip list is still the Trips tab, so the navigation stays visible.
            val bars = navigator.atRoot || (listDetail && navigator.tab == Tab.Trips &&
                navigator.backStack.size == 3 && navigator.backStack.last() is Destination.SavedTrip)
            val recording = tracking.activeTripId != null
            // The bars pad for the system bars. Content must not pad for the same insets again.
            val consumed = when {
                !bars -> WindowInsets(0)
                !wide -> WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                else -> WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
            }
            Row(Modifier.fillMaxSize()) {
                // With a rail, Settings is at the top of the rail, so the screens keep their space for values.
                if (bars && wide) NavigationRail(windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                    header = {
                        IconButton(onClick = { navigator.push(Destination.Settings) }, modifier = Modifier.testTag("open_settings")) {
                            Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
                        }
                    }) {
                    Tab.entries.forEach { tab ->
                        NavigationRailItem(selected = navigator.tab == tab, onClick = { navigator.select(tab) },
                            icon = { TabIcon(tab, recording) }, label = { Text(stringResource(tab.label)) },
                            modifier = Modifier.testTag(tab.testTag))
                    }
                    Spacer(Modifier.weight(1f))
                    RecordingRailControls(tracking, onOpen = { navigator.push(Destination.LiveTrip) })
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    NavDisplay(
                        backStack = navigator.backStack,
                        modifier = Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(consumed),
                        onBack = navigator::pop,
                        transitionSpec = { sharedAxis(forward = true, rtl) },
                        popTransitionSpec = { sharedAxis(forward = false, rtl) },
                        // The list and detail strategy returns no scene for a narrow window. One screen then shows.
                        sceneStrategies = listOf(sceneStrategy, SinglePaneSceneStrategy()),
                        entryProvider = { destination ->
                            val metadata = when (destination) {
                                Destination.Trips -> tabTransition + tripsPane
                                is Destination.SavedTrip -> tripPane
                                else -> emptyMap()
                            }
                            NavEntry(destination, metadata = metadata) {
                                Screen(destination, navigator, tracking, speedometer, compass, access, app, rail = wide)
                            }
                        },
                    )
                    // The bars leave with the screen transition instead of leaving an empty strip at once.
                    AnimatedVisibility(bars && !wide, enter = expandVertically(tween(TRANSITION_MILLIS, easing = FastOutSlowInEasing)) + fadeIn(tween(TRANSITION_MILLIS)),
                        exit = shrinkVertically(tween(TRANSITION_MILLIS, easing = FastOutSlowInEasing)) + fadeOut(tween(TRANSITION_MILLIS))) {
                        Column {
                            RecordingBar(tracking, onOpen = { navigator.push(Destination.LiveTrip) })
                            NavigationBar(windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) {
                                Tab.entries.forEach { tab ->
                                    NavigationBarItem(selected = navigator.tab == tab, onClick = { navigator.select(tab) },
                                        icon = { TabIcon(tab, recording) }, label = { Text(stringResource(tab.label)) },
                                        modifier = Modifier.testTag(tab.testTag))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Screen(
    destination: Destination,
    navigator: AppNavigator,
    tracking: TrackingActions,
    speedometer: SpeedometerViewModel,
    compass: CompassViewModel?,
    access: GpsAccess,
    app: TrackingApplication,
    rail: Boolean,
) {
    val openSettings = { navigator.push(Destination.Settings) }.takeIf { !rail }
    when (destination) {
        Destination.Speed -> {
            // Only while the Speed tab shows, and only when the user chose it in Settings.
            val settings by app.settings.values.collectAsStateWithLifecycle()
            if (settings.keepScreenOn) {
                val view = LocalView.current
                DisposableEffect(view) {
                    view.keepScreenOn = true
                    onDispose { view.keepScreenOn = false }
                }
            }
            SpeedometerRoute(speedometer, access, onDetails = { navigator.push(Destination.GpsDetails) },
                trackerControls = { canStart, landscape ->
                    TripStartControls(tracking, canStart, landscape, onOpenTrip = { navigator.push(Destination.LiveTrip) })
                },
                compassViewModel = compass, onSettings = openSettings)
        }
        Destination.GpsDetails -> GpsDetailsDestination(speedometer, access, onBack = navigator::pop, compassViewModel = compass)
        // Select first, so the route loads while the trip screen opens.
        Destination.Trips -> TripsScreen(tracking, onTrip = { tracking.vm.select(it); navigator.showTrip(it) },
            onLive = { navigator.push(Destination.LiveTrip) }, onSettings = openSettings,
            selectedId = (navigator.backStack.last() as? Destination.SavedTrip)?.id)
        Destination.Settings -> SettingsScreen(app.settings, onBack = navigator::pop)
        Destination.LiveTrip -> LiveTripScreen(tracking, onBack = navigator::pop, onEnded = { navigator.remove(Destination.LiveTrip) })
        is Destination.SavedTrip -> SavedTripScreen(tracking, destination.id, onBack = navigator::pop)
    }
}

@Composable
private fun TabIcon(tab: Tab, recording: Boolean) {
    val icon = painterResource(if (tab == Tab.Speed) R.drawable.ic_speed else R.drawable.ic_route)
    if (tab == Tab.Trips && recording) BadgedBox(badge = { Badge() }) { Icon(icon, contentDescription = null) }
    else Icon(icon, contentDescription = null)
}

private val Tab.label get() = if (this == Tab.Speed) R.string.nav_speed else R.string.tracking_trips
private val Tab.testTag get() = if (this == Tab.Speed) "nav_tab_speed" else "nav_tab_trips"
