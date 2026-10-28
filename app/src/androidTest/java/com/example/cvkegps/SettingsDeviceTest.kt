package com.example.cvkegps

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.cvkegps.tracking.RecordingCommand
import com.example.cvkegps.tracking.TrackingApplication
import com.example.cvkegps.tracking.TripStatus
import com.example.cvkegps.ui.DisplayFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings change what the screens show and where the screen stays on. Test locations enter through the Android provider. */
@RunWith(AndroidJUnit4::class)
class SettingsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as TrackingApplication
    private val manager = context.getSystemService(LocationManager::class.java)
    private val device = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalLocation = "true"
    private var lastSent = 0L

    @Before @Suppress("DEPRECATION") fun prepare() {
        originalLocation = shell("cmd location is-location-enabled").trim()
        shell("cmd location set-location-enabled true")
        shell("appops set ${context.packageName} android:mock_location allow")
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
            android.location.Criteria.POWER_HIGH, android.location.Criteria.ACCURACY_FINE)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        device.wakeUp()
        device.pressHome()
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) grant(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun restore() {
        instrumentation.runOnMainSync { app.recorder.send(RecordingCommand.Finish()) }
        waitUntil { !app.recorder.state.value.needsService }
        scenario?.close()
        device.wakeUp()
        manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        shell("appops set ${context.packageName} android:mock_location default")
        shell("cmd location set-location-enabled $originalLocation")
    }

    @Test fun unitsAndAltitudeChangeTheSpeedTabAndStayAfterRecreation() {
        launch()
        // Without a choice, the units follow the device region.
        val region = DisplayFormat(context.resources)
        awaitSpeed(region.spokenSpeed(SPEED))
        compose.onAllNodesWithText(region.speedUnit).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        openSettings()
        capture("defaults")
        compose.onNodeWithTag("speed_unit_kn").performClick()
        compose.onNodeWithTag("altitude_unit_m").performClick()
        compose.onNodeWithTag("settings_back").performClick()
        awaitSpeed("Current speed: 19.4 knots")
        compose.onAllNodesWithText("kn").fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        if (Build.VERSION.SDK_INT >= 34) {
            // Sea level is the default. Near Vancouver the geoid is about 17 m below the WGS 84 ellipsoid.
            compose.onAllNodesWithText(context.getString(R.string.altitude_sea_level)).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
            awaitAltitude { it in 110..125 }
            capture("speed-knots-sea-level")
        } else {
            awaitText("altitude", "100")
        }
        openSettings()
        compose.onNodeWithTag("altitude_ellipsoid").performScrollTo().performClick()
        compose.onNodeWithTag("altitude_ellipsoid").assertIsSelected()
        compose.onNodeWithTag("settings_back").performClick()
        awaitText("altitude", "100")
        capture("speed-gps-height")
        compose.onAllNodesWithText(context.getString(R.string.altitude_ellipsoid)).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        // The choices stay after a new activity and after the app reads them from storage again.
        scenario!!.recreate()
        awaitSpeed("Current speed: 19.4 knots")
        awaitText("altitude", "100")
        openSettings()
        compose.onNodeWithTag("speed_unit_kn").assertIsSelected()
        if (Build.VERSION.SDK_INT < 34) compose.onNodeWithTag("altitude_sea_level").assertIsNotEnabled()
    }

    @Test fun screenStaysOnOnlyWhereAndWhenChosen() {
        launch()
        assertFalse(screenKeptOn())
        openSettings()
        // Where the screen stays on applies only after a choice of when.
        compose.onNodeWithTag("setting_screen_speed").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("screen_on_always").performScrollTo().performClick()
        compose.onNodeWithTag("setting_screen_speed").assertIsEnabled()
        capture("screen-always")
        compose.onNodeWithTag("settings_back").performClick()
        compose.waitUntil(5_000) { screenKeptOn() }
        compose.onNodeWithTag("gps_details").performClick()
        compose.waitUntil(5_000) { !screenKeptOn() }
        compose.onNodeWithTag("details_back").performClick()
        compose.waitUntil(5_000) { screenKeptOn() }
        compose.onNodeWithTag("nav_tab_trips").performClick()
        compose.waitUntil(5_000) { !screenKeptOn() }
        // The current trip map keeps the screen on. Open it from Trips, where the screen is otherwise allowed to turn off.
        compose.onNodeWithTag("nav_tab_speed").performClick()
        startTrip()
        compose.waitUntil(5_000) { screenKeptOn() }
        device.pressBack()
        compose.onNodeWithTag("nav_tab_trips").performClick()
        compose.waitUntil(5_000) { !screenKeptOn() }
        compose.onNodeWithTag("nav_tab_speed").performClick()
        // During a trip: on while the trip records, off while it is paused.
        openSettings()
        compose.onNodeWithTag("screen_on_trip").performScrollTo().performClick()
        compose.onNodeWithTag("settings_back").performClick()
        compose.waitUntil(5_000) { screenKeptOn() }
        instrumentation.runOnMainSync { app.recorder.send(RecordingCommand.Pause) }
        waitUntil { app.recorder.state.value.trip?.status == TripStatus.Paused }
        compose.waitUntil(5_000) { !screenKeptOn() }
        instrumentation.runOnMainSync { app.recorder.send(RecordingCommand.Resume(app.recorder.state.value.trip!!.id)) }
        compose.waitUntil(5_000) { screenKeptOn() }
        // Without the Speed tab in the chosen screens, Speed lets the screen turn off. The trip map still keeps it on.
        openSettings()
        compose.onNodeWithTag("setting_screen_speed").performScrollTo().performClick()
        compose.onNodeWithTag("settings_back").performClick()
        compose.waitUntil(5_000) { !screenKeptOn() }
        compose.onNodeWithTag("show_route").performClick()
        compose.waitUntil(5_000) { screenKeptOn() }
        device.pressBack()
        // The choices stay after a new activity.
        scenario!!.close()
        launch()
        compose.onNodeWithTag("show_route").performClick()
        compose.waitUntil(5_000) { screenKeptOn() }
    }

    @Test fun aKeptOnScreenDimsAfterAMinuteWithoutTouchAndATouchMakesItBright() {
        launch()
        openSettings()
        compose.onNodeWithTag("screen_on_always").performScrollTo().performClick()
        compose.onNodeWithTag("setting_dim").performScrollTo().performClick()
        compose.onNodeWithTag("settings_back").performClick()
        compose.waitUntil(5_000) { screenKeptOn() }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness())
        compose.waitUntil(75_000) { brightness() == 0.1f }
        assertTrue(screenKeptOn())
        // A touch on a part of the screen without a control.
        device.click(device.displayWidth / 2, device.displayHeight / 3)
        compose.waitUntil(5_000) { brightness() == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        // A screen that the app does not keep on is never dimmed.
        compose.onNodeWithTag("gps_details").performClick()
        compose.waitUntil(5_000) { !screenKeptOn() }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness())
    }

    @Test fun settingsFitThemesLargeTextAndRotation() {
        val font = shell("settings get system font_scale").trim()
        val rotation = shell("settings get system user_rotation").trim()
        val auto = shell("settings get system accelerometer_rotation").trim()
        val night = shell("cmd uimode night").substringAfter(":").trim()
        launch()
        openSettings()
        try {
            for ((name, scale, dark) in listOf(Triple("light", 1f, false), Triple("dark", 1f, true), Triple("large-text", 2f, false))) {
                for (landscape in listOf(false, true)) {
                    setDisplay(scale, landscape, dark)
                    compose.onNodeWithTag("settings_back").assertIsDisplayed()
                    compose.onNodeWithTag("speed_unit_kmh").performScrollTo().assertIsDisplayed()
                    capture("$name-$landscape-units")
                    compose.onNodeWithTag("screen_on_always").performScrollTo().assertIsDisplayed()
                    capture("$name-$landscape-screen")
                    compose.onNodeWithTag("setting_traffic").performScrollTo().assertIsDisplayed()
                    capture("$name-$landscape-maps")
                }
            }
        } finally {
            shell("settings put system font_scale $font")
            shell("settings put system user_rotation $rotation")
            shell("settings put system accelerometer_rotation $auto")
            shell("cmd uimode night $night")
        }
    }

    private fun setDisplay(scale: Float, landscape: Boolean, dark: Boolean) {
        shell("settings put system font_scale $scale")
        shell("cmd uimode night ${if (dark) "yes" else "no"}")
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation ${if (landscape) 1 else 0}")
        compose.waitUntil(5_000) {
            var ready = false
            scenario!!.onActivity {
                val configuration = it.resources.configuration
                ready = configuration.fontScale == scale && (configuration.screenWidthDp > configuration.screenHeightDp) == landscape &&
                    (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES) == dark
            }
            ready
        }
        compose.waitForIdle()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun openSettings() {
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_content").assertIsDisplayed()
    }

    private fun startTrip() {
        compose.onNodeWithTag("trip_start").performClick()
        compose.onNodeWithTag("confirm_start").performClick()
        waitUntil { app.recorder.state.value.trip != null }
        compose.onNodeWithTag("show_route").performClick()
    }

    /** Sends a fix each second until the speed shows. The speed's text is its screen reader description. */
    private fun awaitSpeed(description: String) = compose.waitUntil(15_000) {
        inject()
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
    }

    /** Sends a fix each second until the tagged text shows the expected value. */
    private fun awaitText(tag: String, expected: String) = compose.waitUntil(15_000) {
        inject()
        runCatching { compose.onNodeWithTag(tag).assertTextEquals(expected) }.isSuccess
    }

    private fun awaitAltitude(condition: (Int) -> Boolean) = compose.waitUntil(15_000) {
        inject()
        compose.onAllNodesWithTag("altitude").fetchSemanticsNodes().firstOrNull()?.config
            ?.let { if (SemanticsProperties.Text in it) it[SemanticsProperties.Text].joinToString() else null }
            ?.toIntOrNull()?.let(condition) == true
    }

    private fun inject() {
        if (SystemClock.elapsedRealtime() - lastSent < 1_000) return
        lastSent = SystemClock.elapsedRealtime()
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 49.2827
            longitude = -123.1207
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = 3f
            speed = SPEED
            speedAccuracyMetersPerSecond = 0.2f
            altitude = 100.0
            verticalAccuracyMeters = 4f
        })
    }

    private fun brightness(): Float {
        compose.waitForIdle()
        var value = 0f
        scenario!!.onActivity { value = it.window.attributes.screenBrightness }
        return value
    }

    private fun screenKeptOn(): Boolean {
        compose.waitForIdle()
        var on = false
        scenario!!.onActivity { activity ->
            fun any(view: android.view.View): Boolean = view.keepScreenOn ||
                (view is android.view.ViewGroup && (0 until view.childCount).any { any(view.getChildAt(it)) })
            on = any(activity.window.decorView)
        }
        return on
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(500)
        shell("screencap -p /sdcard/Download/cvkegps-settings-$name.png")
    }

    private fun grant(permission: String) = instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Timed out waiting for the recording state", condition())
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private companion object {
        const val SPEED = 10f
    }
}
