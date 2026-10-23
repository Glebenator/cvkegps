package com.example.cvkegps

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cvkegps.location.GnssObservation
import com.example.cvkegps.location.GnssSnapshot
import com.example.cvkegps.location.GnssSource
import com.example.cvkegps.location.GpsEvent
import com.example.cvkegps.location.GpsReading
import com.example.cvkegps.location.GpsSource
import com.example.cvkegps.location.SatelliteSignal
import com.example.cvkegps.location.SatelliteSystem
import com.example.cvkegps.location.distinctSatellites
import com.example.cvkegps.location.positionAccuracyProgress
import com.example.cvkegps.ui.SpeedometerRoute
import com.example.cvkegps.ui.SpeedometerViewModel
import com.example.cvkegps.ui.theme.CvkeGpsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled GNSS reports cover data that Android test locations cannot supply.
 * Production location delivery and callback cleanup are tested in SpeedometerDeviceTest.
 */
@RunWith(AndroidJUnit4::class)
class SatelliteDisplayDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun multipleBandsCountOnceAndInvalidAccuracyIsUnavailable() {
        val signal = sampleSignals().first()
        val satellites = listOf(signal.copy(usedInFix = false, cn0DbHz = 40f),
            signal.copy(usedInFix = true, cn0DbHz = 25f, carrierFrequencyHz = 1.2e9f),
            signal.copy(system = SatelliteSystem.Glonass)).distinctSatellites()
        assertEquals(2, satellites.size)
        assertTrue(satellites.first { it.system == SatelliteSystem.Gps }.usedInFix)
        assertEquals(1f, positionAccuracyProgress(1f)!!, 0f)
        assertEquals(1f, positionAccuracyProgress(0.5f)!!, 0f)
        assertEquals(1f, positionAccuracyProgress(0f)!!, 0f)
        val atFive = positionAccuracyProgress(5f)!!
        val atTwo = positionAccuracyProgress(2f)!!
        assertTrue("The 5 m mark must leave useful space for improvement", atFive in 0.55f..0.65f)
        assertTrue("Better estimates must fill more of the bar below 5 m", atTwo > atFive + 0.2f && atTwo < 1f)
        assertEquals(0f, positionAccuracyProgress(50f)!!, 0f)
        assertEquals(0f, positionAccuracyProgress(200f)!!, 0f)
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach { assertNull(positionAccuracyProgress(it)) }
    }

    @Test
    fun satelliteReportsExpireRecoverAndRenderInBothThemes() {
        val reports = MutableStateFlow(GnssSnapshot())
        val fixes = MutableStateFlow<GpsEvent>(GpsEvent.Searching)
        var dark by mutableStateOf(false)
        var fontScale by mutableFloatStateOf(1f)
        val source = object : GnssSource { override fun updates() = reports }
        val gps = object : GpsSource { override fun updates() = fixes }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity, viewModelFactory {
                    initializer { SpeedometerViewModel(gps, source) }
                })["satellite-display-test", SpeedometerViewModel::class.java]
                activity.setContent {
                    SideEffect {
                        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        CvkeGpsTheme(darkTheme = dark) { SpeedometerRoute(model) }
                    }
                }
            }
            fun report(signals: List<SatelliteSignal>? = sampleSignals(), ageNanos: Long = 0) {
                reports.value = GnssSnapshot(GnssObservation.Waiting, signals = signals,
                    reportedAtNanos = SystemClock.elapsedRealtimeNanos() - ageNanos)
            }
            fun fix(accuracy: Float = 6f) {
                fixes.value = GpsEvent.Fix(GpsReading(8.2f, 451.0, accuracy, SystemClock.elapsedRealtimeNanos()))
            }
            fun awaitCount(text: String) {
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithTag("satellite_count").assertTextEquals(text) }.isSuccess
                }
            }
            awaitCount("Satellite count unavailable")
            fix()
            report(emptyList())
            awaitCount("Satellites: 0 used / 0 seen")
            report()
            awaitCount("Satellites: 9 used / 12 seen")
            compose.onNodeWithTag("accuracy_bar").assertIsDisplayed()
            compose.onNodeWithTag("position_accuracy").assertTextEquals("Position estimate ±6 m")
            compose.onNodeWithTag("accuracy_scale").assertIsDisplayed()
            capture("light")
            compose.runOnIdle { dark = true }
            capture("dark")
            compose.runOnIdle { dark = false; fontScale = 2f }
            fix()
            report()
            capture("large-text")
            compose.onNodeWithTag("current_speed").assertIsDisplayed()
            compose.onNodeWithTag("gps_action").assertDoesNotExist()
            compose.runOnIdle { fontScale = 1f }

            fix(0.5f)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("accuracy_bar").assertContentDescriptionEquals("Position estimate ±0.5 m") }.isSuccess
            }
            capture("full")
            fix(0.04f)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("accuracy_bar").assertContentDescriptionEquals("Position estimate below 0.1 m") }.isSuccess
            }

            fix()
            // Exercise the freshness deadline without waiting for an entire new report period.
            report(ageNanos = 9_000_000_000)
            awaitCount("Satellites: 9 used / 12 seen")
            awaitCount("Satellite count unavailable")
            report(ageNanos = -1_000_000_000)
            awaitCount("Satellite count unavailable")
            report()
            awaitCount("Satellites: 9 used / 12 seen")
            fix(Float.NaN)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("position_accuracy").assertTextEquals("Accuracy unavailable") }.isSuccess
            }
            compose.onNodeWithTag("accuracy_bar").assertDoesNotExist()
            fixes.value = GpsEvent.Paused
            awaitCount("Satellite count unavailable")
            compose.onNodeWithTag("accuracy_bar").assertDoesNotExist()
        }
    }

    private fun sampleSignals(): List<SatelliteSignal> {
        val systems = listOf(SatelliteSystem.Gps, SatelliteSystem.Glonass, SatelliteSystem.Galileo, SatelliteSystem.Beidou)
        val signals = List(12) { index ->
            SatelliteSignal(systems[index / 3], index % 3 + 1, 30f + index,
                Math.toRadians((index % 3 * 22 + 12).toDouble()), Math.toRadians((index * 30 + 8).toDouble()),
                1.57542e9f, usedInFix = index % 4 != 3)
        }
        return signals + signals.first().copy(carrierFrequencyHz = 1.17645e9f, usedInFix = false)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Compose semantics can settle before SurfaceFlinger presents the new frame.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(400)
        val command = "screencap -p /sdcard/Download/cvkegps-accuracy-scale-$name.png"
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { it.readBytes() }
    }
}
