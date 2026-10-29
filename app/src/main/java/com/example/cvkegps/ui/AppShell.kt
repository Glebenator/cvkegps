package com.example.cvkegps.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import com.example.cvkegps.location.seaLevelAltitudeSupported
import com.example.cvkegps.settings.AltitudeReference
import com.example.cvkegps.settings.ScreenOn
import com.example.cvkegps.settings.Settings
import com.example.cvkegps.tracking.TripStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.platform.LocalDensity
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
import com.example.cvkegps.ui.theme.Motion
import com.example.cvkegps.tracking.LiveTripScreen
import com.example.cvkegps.tracking.RecordingBar
import com.example.cvkegps.tracking.RecordingRailControls
import com.example.cvkegps.tracking.SavedTripScreen
import com.example.cvkegps.tracking.TripReplayScreen
import com.example.cvkegps.tracking.TrackingActions
import com.example.cvkegps.tracking.TrackingApplication
import com.example.cvkegps.tracking.TrackingHost
import com.example.cvkegps.tracking.TripStartControls
import com.example.cvkegps.tracking.TripsScreen

private val instant = EnterTransition.None togetherWith ExitTransition.None

/** Tabs change often, so a tab change is instant. Navigation 3 would otherwise use a 700 ms fade. */
private val tabTransition = NavDisplay.transitionSpec { instant } +
    NavDisplay.popTransitionSpec { instant } +
    NavDisplay.predictivePopTransitionSpec { instant }

/**
 * Drill-in: the new screen fades in from [move] pixels in the direction of travel, and the old screen fades out the other way.
 * A new screen comes from the end side; Back reverses it. Exits are shorter than entrances.
 */
private fun push(forward: Boolean, rtl: Boolean, move: Int): ContentTransform {
    val direction = if (forward != rtl) 1 else -1
    val enter = tween<Float>(Motion.ENTER_MILLIS, easing = Motion.standard)
    val exit = tween<Float>(Motion.EXIT_MILLIS, easing = Motion.standard)
    return (slideInHorizontally(tween(Motion.ENTER_MILLIS, easing = Motion.standard)) { direction * move } + fadeIn(enter)) togetherWith
        (slideOutHorizontally(tween(Motion.EXIT_MILLIS, easing = Motion.standard)) { -direction * move } + fadeOut(exit))
}

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

@Composable
fun AppShell(
    app: TrackingApplication,
    speedometer: SpeedometerViewModel,
    compass: CompassViewModel?,
    openTripsRequest: Int,
    lastTouch: StateFlow<Long>,
) {
    val settings by app.settings.values.collectAsStateWithLifecycle()
    ProvideDisplayChoices(settings) { Shell(app, speedometer, compass, openTripsRequest, settings, lastTouch) }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun Shell(
    app: TrackingApplication,
    speedometer: SpeedometerViewModel,
    compass: CompassViewModel?,
    openTripsRequest: Int,
    settings: Settings,
    lastTouch: StateFlow<Long>,
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
        KeepScreenOnWhenChosen(app, settings, navigator.backStack.last(), lastTouch)
        // The window background is transparent. Screens fade and the bars change height, so paint the theme background behind them.
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // A navigation rail in landscape, and on wide windows such as an open foldable.
            val wide = maxWidth > maxHeight || maxWidth >= 600.dp
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            val move = with(LocalDensity.current) { Motion.moveMd.roundToPx() }
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
                            icon = { TabIcon(tab, recording) },
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
                        transitionSpec = { push(forward = true, rtl, move) },
                        popTransitionSpec = { push(forward = false, rtl, move) },
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
                    AnimatedVisibility(bars && !wide,
                        enter = expandVertically(tween(Motion.ENTER_MILLIS, easing = Motion.standard)) + fadeIn(tween(Motion.ENTER_MILLIS, easing = Motion.standard)),
                        exit = shrinkVertically(tween(Motion.EXIT_MILLIS, easing = Motion.standard)) + fadeOut(tween(Motion.EXIT_MILLIS, easing = Motion.standard))) {
                        Column {
                            RecordingBar(tracking, onOpen = { navigator.push(Destination.LiveTrip) })
                            NavigationBar(windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) {
                                Tab.entries.forEach { tab ->
                                    NavigationBarItem(selected = navigator.tab == tab, onClick = { navigator.select(tab) },
                                        icon = { TabIcon(tab, recording) },
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

/** Gives the screens the chosen units and altitude. Sea level needs Android 14, so earlier versions show the GPS height. */
@Composable
private fun ProvideDisplayChoices(settings: Settings, content: @Composable () -> Unit) {
    val country = LocalConfiguration.current.locales[0].country
    val units = settings.units(country)
    val altitude = if (seaLevelAltitudeSupported) settings.altitudeReference else AltitudeReference.Ellipsoid
    val choices = remember(units, altitude) { DisplayChoices(units, altitude) }
    CompositionLocalProvider(LocalDisplayChoices provides choices, content = content)
}

/**
 * Keeps the screen on only where and when the user chose it in Settings: on Speed or the current trip map,
 * during a recording trip or always. The shell sets it once from the top screen, so a screen that leaves during
 * a transition cannot clear it. A dimmed screen becomes bright again at the next touch.
 */
@Composable
private fun KeepScreenOnWhenChosen(app: TrackingApplication, settings: Settings, top: Destination, lastTouch: StateFlow<Long>) {
    val recording by remember(app) { app.recorder.state.map { it.trip?.status == TripStatus.Recording }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(false)
    val screen = (top == Destination.Speed && settings.screenOnSpeed) || (top == Destination.LiveTrip && settings.screenOnTripMap)
    val keepOn = screen && when (settings.screenOn) {
        ScreenOn.Off -> false
        ScreenOn.DuringTrip -> recording
        ScreenOn.Always -> true
    }
    val view = LocalView.current
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    val dim = keepOn && settings.dimScreen
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(dim, lastTouch) {
        dimmed = false
        // Each touch restarts the wait. The effect does no work between touches.
        if (dim) lastTouch.collectLatest { touched ->
            dimmed = false
            delay(DIM_AFTER_MILLIS - (SystemClock.uptimeMillis() - touched))
            dimmed = true
        }
    }
    val window = LocalActivity.current?.window
    DisposableEffect(window, dimmed) {
        fun brightness(value: Float) = window?.let { it.attributes = it.attributes.apply { screenBrightness = value } }
        brightness(if (dimmed) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        onDispose { brightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }
}

private const val DIM_AFTER_MILLIS = 60_000L
// A tenth of the full screen brightness: dark enough to save power, bright enough to see that the app is on.
private const val DIM_BRIGHTNESS = 0.1f

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
        is Destination.SavedTrip -> SavedTripScreen(tracking, destination.id, onBack = navigator::pop,
            onReplay = { navigator.push(Destination.Replay(destination.id)) })
        is Destination.Replay -> TripReplayScreen(tracking, destination.id, onBack = navigator::pop)
    }
}

@Composable
// The bars show icons only. The tab name is the accessible name.
private fun TabIcon(tab: Tab, recording: Boolean) {
    val icon = painterResource(if (tab == Tab.Speed) R.drawable.ic_speed else R.drawable.ic_route)
    val name = stringResource(tab.label)
    if (tab == Tab.Trips && recording) BadgedBox(badge = { Badge() }) { Icon(icon, contentDescription = name) }
    else Icon(icon, contentDescription = name)
}

private val Tab.label get() = if (this == Tab.Speed) R.string.nav_speed else R.string.tracking_trips
private val Tab.testTag get() = if (this == Tab.Speed) "nav_tab_speed" else "nav_tab_trips"
