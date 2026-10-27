package com.example.cvkegps

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cvkegps.location.GnssSource
import com.example.cvkegps.location.GpsEvent
import com.example.cvkegps.location.GpsReading
import com.example.cvkegps.location.GpsSource
import com.example.cvkegps.location.HeadingReading
import com.example.cvkegps.location.HeadingSource
import com.example.cvkegps.ui.CompassViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger
import com.example.cvkegps.location.GnssObservation
import com.example.cvkegps.location.GnssSnapshot
import com.example.cvkegps.location.SatelliteSignal
import com.example.cvkegps.location.SatelliteSystem
import com.example.cvkegps.ui.GpsDetailsRoute
import com.example.cvkegps.ui.GpsStatus
import com.example.cvkegps.tracking.TrackingApplication
import com.example.cvkegps.ui.AppShell
import com.example.cvkegps.ui.SpeedometerViewModel
import com.example.cvkegps.ui.SpeedometerUiState
import com.example.cvkegps.ui.theme.CvkeGpsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled reports test the map. They do not test receiver hardware. */
@RunWith(AndroidJUnit4::class)
class SkyMapDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun skyMapHandlesAnglesThemesRotationLargeTextAndOldReports() {
        var report by mutableStateOf(GnssSnapshot())
        var fixTimestamp by mutableLongStateOf(SystemClock.elapsedRealtimeNanos())
        var declination by mutableStateOf<Double?>(null)
        var showDetails by mutableStateOf(true)
        val activeSensors = AtomicInteger(0)
        val observedRotation = AtomicInteger(-1)
        val headings = MutableStateFlow<HeadingReading?>(null)
        val source = object : HeadingSource {
            override fun updates(displayRotation: Int) = flow {
                activeSensors.incrementAndGet()
                observedRotation.set(displayRotation)
                try { emitAll(headings) } finally { activeSensors.decrementAndGet() }
            }
        }
        var dark by mutableStateOf(false)
        var fontScale by mutableFloatStateOf(1f)
        val signals = SatelliteSystem.entries.mapIndexed { index, system ->
            val azimuth = listOf(0.0, 90.0, 180.0, 270.0, 0.0, 45.0, 135.0, 225.0)[index]
            val elevation = listOf(0.0, 0.0, 0.0, 0.0, 90.0, 30.0, 45.0, 60.0)[index]
            SatelliteSignal(system, index + 1, 35f, Math.toRadians(elevation), Math.toRadians(azimuth),
                null, index % 2 == 0)
        }.let { valid -> valid + listOf(valid.first().copy(carrierFrequencyHz = 1.2e9f),
            valid.first().copy(id = 99, azimuthRadians = Double.NaN),
            valid.first().copy(id = 100, elevationRadians = -0.1)) }
        fun fresh(input: List<SatelliteSignal>? = signals, age: Long = 0) {
            compose.runOnIdle {
                fixTimestamp = SystemClock.elapsedRealtimeNanos()
                headings.value = HeadingReading(Math.toRadians(75.0), SystemClock.elapsedRealtimeNanos())
                report = GnssSnapshot(GnssObservation.Waiting, signals = input,
                    reportedAtNanos = SystemClock.elapsedRealtimeNanos() - age)
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun content() {
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity, viewModelFactory {
                        initializer { CompassViewModel(source) }
                    })["compass-test", CompassViewModel::class.java]
                    activity.setContent {
                        val lightSystemBars = !dark
                        SideEffect {
                            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                                isAppearanceLightStatusBars = lightSystemBars
                                isAppearanceLightNavigationBars = lightSystemBars
                            }
                        }
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                            CvkeGpsTheme(darkTheme = dark) {
                                if (showDetails) GpsDetailsRoute(
                                    SpeedometerUiState(GpsStatus.Live, GpsReading(null, null, 4f, fixTimestamp,
                                        magneticDeclinationRadians = declination), lastFixElapsedNanos = fixTimestamp),
                                    report, false, {}, { showDetails = false }, model,
                                )
                            }
                        }
                    }
                }
            }
            fun showMap() {
                revealInDetails("satellite_sky_map")
                compose.onNodeWithTag("satellite_sky_map").assertIsDisplayed()
                revealInDetails("sky_map_count")
                compose.onNodeWithTag("sky_map_count").assertTextEquals("8 of 10 reported satellites plotted")
                revealInDetails("satellite_sky_map")
                val mapBounds = compose.onNodeWithTag("satellite_sky_map").getUnclippedBoundsInRoot()
                // Portrait shows the map in the list. Landscape on a phone shows it beside the list, inside the screen.
                val list = compose.onNodeWithTag("gps_details_list").getUnclippedBoundsInRoot()
                val inList = mapBounds.left >= list.left - 1.dp && mapBounds.right <= list.right + 1.dp
                val viewBounds = if (inList) list else compose.onRoot().getUnclippedBoundsInRoot()
                assertTrue("The full map must be visible", mapBounds.top >= viewBounds.top - 1.dp &&
                    mapBounds.bottom <= viewBounds.bottom + 1.dp)
            }
            content()
            fresh()
            showMap()
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("sky_heading").assertTextEquals(
                    "Waiting for a GPS position to align the compass. North is at the top.") }.isSuccess
            }
            compose.runOnIdle { declination = Math.toRadians(15.0) }
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("sky_heading").assertTextEquals("Phone direction: 90° from true north") }.isSuccess
            }
            assertTrue(activeSensors.get() == 1)
            capture("sky-details-light")
            compose.runOnIdle { dark = true }
            fresh()
            showMap()
            capture("sky-details-dark")
            compose.runOnIdle { dark = false; fontScale = 2f }
            fresh()
            showMap()
            capture("sky-details-large-text")
            val turned = turnToLandscape(scenario)
            content()
            fresh()
            showMap()
            if (turned) assertTrue(observedRotation.get() == 1 || observedRotation.get() == 3)
            capture("sky-details-landscape-large-text")
            scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(5_000) { activeSensors.get() == 0 }
            scenario.moveToState(Lifecycle.State.RESUMED)
            fresh()
            showMap()
            compose.waitUntil(5_000) { activeSensors.get() == 1 }
            compose.runOnIdle { headings.value = HeadingReading(Math.toRadians(75.0), SystemClock.elapsedRealtimeNanos() - 1_900_000_000) }
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("sky_heading").assertTextEquals(
                    "Compass unavailable. North is at the top. Hold the phone flat and away from magnets. A compass sensor is required.") }.isSuccess
            }
            fresh()
            // A lost GPS position keeps the last true-north correction.
            compose.runOnIdle {
                declination = null
                headings.value = HeadingReading(Math.toRadians(165.0), SystemClock.elapsedRealtimeNanos())
            }
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("sky_heading").assertTextEquals("Phone direction: 180° from true north") }.isSuccess
            }
            compose.runOnIdle { declination = Math.toRadians(15.0) }
            compose.runOnIdle { fontScale = 1f }
            fresh(age = 11_000_000_000)
            revealInDetails("sky_map_unavailable")
            compose.onNodeWithTag("satellite_sky_map").assertDoesNotExist()
            fresh(age = -1_000_000_000)
            compose.onNodeWithTag("sky_map_unavailable").assertIsDisplayed()
            compose.onNodeWithTag("satellite_sky_map").assertDoesNotExist()
            fresh(emptyList())
            revealInDetails("sky_map_count")
            compose.onNodeWithTag("sky_map_count").assertTextEquals("0 of 0 reported satellites plotted")
            fresh(null)
            revealInDetails("sky_map_unavailable")
            compose.onNodeWithTag("satellite_sky_map").assertDoesNotExist()
            fresh()
            showMap()
            compose.runOnIdle { showDetails = false }
            compose.waitUntil(5_000) { activeSensors.get() == 0 }
        }
    }

    @Test
    fun homeSkyRingTurnsWithThePhoneAndReleasesTheCompass() {
        val fixes = MutableStateFlow<GpsEvent>(GpsEvent.Searching)
        val reports = MutableStateFlow(GnssSnapshot())
        val headings = MutableStateFlow<HeadingReading?>(null)
        val activeSensors = AtomicInteger(0)
        val gps = object : GpsSource { override fun updates() = fixes }
        val gnss = object : GnssSource { override fun updates() = reports }
        val compassSource = object : HeadingSource {
            override fun updates(displayRotation: Int) = flow {
                activeSensors.incrementAndGet()
                try { emitAll(headings) } finally { activeSensors.decrementAndGet() }
            }
        }
        var dark by mutableStateOf(false)
        var fontScale by mutableFloatStateOf(1f)
        fun fix() {
            fixes.value = GpsEvent.Fix(GpsReading(8.2f, 451.0, 4f, SystemClock.elapsedRealtimeNanos(),
                magneticDeclinationRadians = Math.toRadians(15.0)))
        }
        fun report() {
            reports.value = GnssSnapshot(GnssObservation.Waiting, signals = ringSignals(),
                reportedAtNanos = SystemClock.elapsedRealtimeNanos())
        }
        fun face(magneticDegrees: Double, ageNanos: Long = 0) {
            headings.value = HeadingReading(Math.toRadians(magneticDegrees), SystemClock.elapsedRealtimeNanos() - ageNanos)
        }
        // A heading expires after two seconds, which a slow screen change can exceed. Waits that expect a current
        // heading supply new readings until the screen shows it. Production expiry does not change.
        fun keepFresh() { fix(); report(); face(75.0) }
        fun fresh() = compose.runOnIdle { keepFresh() }
        // The ring has no direction text. Its screen-reader state reports the phone direction.
        fun awaitRing(state: String?, keep: () -> Unit = {}) = compose.waitUntil(5_000) {
            keep()
            runCatching {
                compose.onNodeWithTag("home_sky_ring").assert(
                    if (state == null) SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)
                    else SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state),
                )
            }.isSuccess
        }
        fun ringShown() = compose.onAllNodes(hasTestTag("home_sky_ring")).fetchSemanticsNodes().isNotEmpty()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun content() {
                scenario.onActivity { activity ->
                    val provider = ViewModelProvider(activity, viewModelFactory {
                        initializer { SpeedometerViewModel(gps, gnss) }
                        initializer { CompassViewModel(compassSource) }
                    })
                    val speed = provider["home-ring-speed", SpeedometerViewModel::class.java]
                    val compass = provider["home-ring-compass", CompassViewModel::class.java]
                    activity.setContent {
                        val lightSystemBars = !dark
                        SideEffect {
                            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                                isAppearanceLightStatusBars = lightSystemBars
                                isAppearanceLightNavigationBars = lightSystemBars
                            }
                        }
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                            CvkeGpsTheme(darkTheme = dark) { AppShell(activity.application as TrackingApplication, speed, compass, 0) }
                        }
                    }
                }
            }
            content()
            compose.runOnIdle { fix() }
            // Without a satellite report the ring has no markers and no compass listener.
            awaitRing(null)
            assertEquals(0, activeSensors.get())
            fresh()
            awaitRing(EAST, ::keepFresh)
            assertEquals(1, activeSensors.get())
            capture("sky-home-light")
            compose.runOnIdle { dark = true }
            fresh()
            awaitRing(EAST, ::keepFresh)
            capture("sky-home-dark")
            compose.runOnIdle { dark = false }

            // A lost GPS position keeps the last true-north correction.
            compose.runOnIdle { fixes.value = GpsEvent.Searching; report(); face(165.0) }
            awaitRing("Phone direction: 180° from true north") { report(); face(165.0) }
            compose.runOnIdle { face(75.0, ageNanos = 1_900_000_000) }
            awaitRing("Compass unavailable. North is at the top.")
            fresh()
            awaitRing(EAST, ::keepFresh)

            scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(5_000) { activeSensors.get() == 0 }
            scenario.moveToState(Lifecycle.State.RESUMED)
            fresh()
            awaitRing(EAST, ::keepFresh)
            compose.waitUntil(5_000) { activeSensors.get() == 1 }

            compose.onNodeWithTag("gps_details").performClick()
            compose.onNodeWithTag("gps_details_list").performScrollToNode(hasTestTag("satellite_sky_map"))
            compose.waitUntil(5_000) {
                keepFresh()
                runCatching { compose.onNodeWithTag("sky_heading").assertTextEquals("Phone direction: 90° from true north") }.isSuccess
            }
            assertEquals(1, activeSensors.get())
            compose.onNodeWithTag("details_back").performClick()
            fresh()
            awaitRing(EAST, ::keepFresh)
            assertEquals(1, activeSensors.get())

            // Paused GPS removes the satellite report, the markers, and the compass listener.
            compose.runOnIdle { fixes.value = GpsEvent.Paused }
            awaitRing(null)
            compose.waitUntil(5_000) { activeSensors.get() == 0 }

            // Compass readings expire after two seconds. Check the state before each capture.
            compose.runOnIdle { fontScale = 2f }
            fresh()
            compose.waitForIdle()
            if (ringShown()) awaitRing(EAST, ::keepFresh) else assertEquals(0, activeSensors.get())
            capture("sky-home-large-text")
            compose.runOnIdle { fontScale = 1f }
            val turned = turnToLandscape(scenario)
            content()
            fresh()
            compose.waitForIdle()
            // A short window gives the space to speed. It does not start the compass for a hidden ring.
            if (ringShown()) {
                awaitRing(EAST, ::keepFresh)
                assertEquals(1, activeSensors.get())
            } else {
                compose.waitUntil(5_000) { activeSensors.get() == 0 }
            }
            capture("sky-home-landscape")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        compose.waitUntil(5_000) { activeSensors.get() == 0 }
    }

    /** The list holds the sky map in portrait. Landscape on a phone keeps the map beside the list, so nothing scrolls. */
    private fun revealInDetails(tag: String) {
        runCatching { compose.onNodeWithTag("gps_details_list").performScrollToNode(hasTestTag(tag)) }
    }

    /**
     * Requests landscape. Android 16 and later ignore an app's orientation request on large screens, such as an open
     * foldable, so the result says whether the window turned.
     */
    private fun turnToLandscape(scenario: androidx.test.core.app.ActivityScenario<*>): Boolean {
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        val configuration = { InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration }
        if (configuration().smallestScreenWidthDp >= 600) {
            Thread.sleep(1_000)
            return configuration().orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(5_000) { configuration().orientation == Configuration.ORIENTATION_LANDSCAPE }
        return true
    }

    private companion object {
        const val EAST = "Phone direction: 90° from true north"
    }

    /** Controlled reports with a range of directions, elevations, and signal strengths. */
    private fun ringSignals(): List<SatelliteSignal> = listOf(
        Triple(SatelliteSystem.Gps, 3, Triple(47f, 72.0, 40.0)) to true,
        Triple(SatelliteSystem.Gps, 7, Triple(41f, 35.0, 95.0)) to true,
        Triple(SatelliteSystem.Gps, 12, Triple(22f, 8.0, 200.0)) to false,
        Triple(SatelliteSystem.Galileo, 5, Triple(44f, 55.0, 300.0)) to true,
        Triple(SatelliteSystem.Galileo, 11, Triple(31f, 18.0, 150.0)) to true,
        Triple(SatelliteSystem.Glonass, 4, Triple(38f, 25.0, 260.0)) to true,
        Triple(SatelliteSystem.Glonass, 15, Triple(16f, 5.0, 340.0)) to false,
        Triple(SatelliteSystem.Beidou, 23, Triple(35f, 42.0, 15.0)) to true,
        Triple(SatelliteSystem.Beidou, 30, Triple(27f, 12.0, 120.0)) to false,
        Triple(SatelliteSystem.Qzss, 194, Triple(33f, 64.0, 175.0)) to true,
        Triple(SatelliteSystem.Sbas, 131, Triple(29f, 30.0, 230.0)) to false,
    ).map { (satellite, used) ->
        val (system, id, signal) = satellite
        val (cn0, elevation, azimuth) = signal
        SatelliteSignal(system, id, cn0, Math.toRadians(elevation), Math.toRadians(azimuth), 1.57542e9f, used)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(1_000)
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "screencap -p /sdcard/Download/cvkegps-$name.png"),
        ).use { it.readBytes() }
    }
}
