package com.example.cvkegps.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntry
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

// Tabs change in place. Only screens inside a tab slide.
private val tabTransition = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() } +
    NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() } +
    NavDisplay.predictivePopTransitionSpec { fadeIn() togetherWith fadeOut() }

@Composable
fun AppShell(
    app: TrackingApplication,
    speedometer: SpeedometerViewModel,
    compass: CompassViewModel?,
    openTripsRequest: Int,
) {
    val navigator = rememberAppNavigator()
    val access = rememberGpsAccess(speedometer) ?: return
    TrackingHost(app, navigator, openTripsRequest) { tracking ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight
            val bars = navigator.atRoot
            val recording = tracking.recording.trip != null
            // The bars pad for the system bars. Content must not pad for the same insets again.
            val consumed = when {
                !bars -> WindowInsets(0)
                !wide -> WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                else -> WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
            }
            Row(Modifier.fillMaxSize()) {
                if (bars && wide) NavigationRail(windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)) {
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
                        entryProvider = { destination ->
                            NavEntry(destination, metadata = if (destination == Destination.Trips) tabTransition else emptyMap()) {
                                Screen(destination, navigator, tracking, speedometer, compass, access)
                            }
                        },
                    )
                    // The bars leave with the screen transition instead of leaving an empty strip at once.
                    AnimatedVisibility(bars && !wide, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
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
) {
    when (destination) {
        Destination.Speed -> SpeedometerRoute(speedometer, access, onDetails = { navigator.push(Destination.GpsDetails) },
            trackerControls = { canStart -> TripStartControls(tracking, canStart) }, compassViewModel = compass)
        Destination.GpsDetails -> GpsDetailsDestination(speedometer, access, onBack = navigator::pop, compassViewModel = compass)
        // Select first, so the route loads while the trip screen opens.
        Destination.Trips -> TripsScreen(tracking, onTrip = { tracking.vm.select(it); navigator.push(Destination.SavedTrip(it)) },
            onLive = { navigator.push(Destination.LiveTrip) })
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
