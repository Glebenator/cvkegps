package com.example.cvkegps

import android.Manifest
import android.app.NotificationManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.cvkegps.tracking.RouteMath
import com.example.cvkegps.tracking.TripMode
import com.example.cvkegps.tracking.TripStatus
import com.example.cvkegps.tracking.TrackingApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Use a dedicated emulator. Test locations enter through the production Android provider. */
@RunWith(AndroidJUnit4::class)
class TrackingDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as TrackingApplication
    private val manager = context.getSystemService(LocationManager::class.java)
    private val device = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalLocation = "true"
    private var sampleIndex = 0

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
        instrumentation.runOnMainSync { app.recorder.send(com.example.cvkegps.tracking.RecordingCommand.Finish()) }
        waitUntil { !app.recorder.state.value.needsService }
        scenario?.close()
        device.wakeUp()
        manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        shell("appops set ${context.packageName} android:mock_location default")
        shell("cmd location set-location-enabled $originalLocation")
    }

    @Test fun recordingSurvivesLockAndRotationAndNotificationPauseCreatesSegments() {
        launch()
        compose.onNodeWithTag("gps_action").assertDoesNotExist()
        startTrip()
        compose.onNodeWithTag("change_activity").performClick()
        compose.onNodeWithTag("mode_Bike").performClick()
        waitUntil { app.recorder.state.value.trip?.status == TripStatus.Recording }
        val id = app.recorder.state.value.trip!!.id
        deliver(id, 2)
        scenario!!.recreate()
        assertEquals(1, requestCount())
        scenario!!.moveToState(Lifecycle.State.CREATED)
        device.sleep()
        deliver(id, 3)
        assertEquals(1, requestCount())
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == 100 }
        notification.notification.actions[0].actionIntent.send()
        waitUntil { app.recorder.state.value.trip?.status == TripStatus.Paused }
        assertEquals(0, requestCount())
        val before = savedCount(id)
        inject()
        Thread.sleep(1_200)
        assertEquals(before, savedCount(id))
        context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == 100 }
            .notification.actions[0].actionIntent.send()
        waitUntil { app.recorder.state.value.trip?.status == TripStatus.Recording }
        deliver(id, before + 2)
        device.wakeUp()
        shell("wm dismiss-keyguard")
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("trip_pause").performScrollTo().performClick()
        waitUntil { app.recorder.state.value.trip?.status == TripStatus.Paused }
        assertEquals(0, requestCount())
        compose.onNodeWithTag("trip_resume").performScrollTo().performClick()
        waitUntil { app.recorder.state.value.trip?.status == TripStatus.Recording }
        assertEquals(1, requestCount())
        compose.onNodeWithTag("trip_stop").performScrollTo()
        compose.onNodeWithTag("trip_stop").performClick()
        waitUntil { !app.recorder.state.value.needsService }
        val trip = runBlocking { app.database.trips().trip(id) }!!
        assertEquals(TripMode.Bike, trip.mode)
        assertEquals(TripStatus.Finished, trip.status)
        assertEquals(3, runBlocking { app.database.trips().segments(id).size })
        assertEquals(0, requestCount())
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 100 })
        // Stop opens the saved trip directly.
        compose.onNodeWithTag("trips_content").performScrollToNode(hasTestTag("route_map"))
        compose.onNodeWithTag("route_map").assertIsDisplayed()
    }

    @Test fun signalLossRemainsAGapAndSavedTripCanBeDeleted() {
        launch()
        startTrip()
        waitUntil { app.recorder.state.value.trip != null }
        val id = app.recorder.state.value.trip!!.id
        deliver(id, 1)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        Thread.sleep(11_000)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        deliver(id, 2)
        compose.onNodeWithTag("trip_stop").performScrollTo()
        compose.onNodeWithTag("trip_stop").performClick()
        waitUntil { !app.recorder.state.value.needsService }
        val points = runBlocking { app.database.trips().savedPoints(id) }
        val summary = RouteMath.summarize(points.groupBy { it.segmentId }.values.map { it.map { point -> point.sample } })
        assertTrue(summary.gapCount > 0)
        assertEquals(0.0, summary.distanceMeters, 0.001)
        // Stop opens the saved trip directly.
        compose.onNodeWithTag("trips_content").performScrollToNode(hasTestTag("trip_delete"))
        compose.onNodeWithTag("trip_delete").performClick()
        compose.onNodeWithTag("confirm_delete").performClick()
        waitUntil { runBlocking { app.database.trips().trip(id) } == null }
        assertEquals(0, savedCount(id))
    }

    @Test fun disabledLocationBlocksStartAndRecoversWithoutRestart() {
        launch()
        compose.onNodeWithTag("trip_start").performClick()
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("confirm_start").assertIsNotEnabled()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("confirm_start").performClick()
        waitUntil { app.recorder.state.value.trip != null }
        deliver(app.recorder.state.value.trip!!.id, 1)
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun startTrip() {
        compose.onNodeWithTag("trip_start").performClick()
        compose.onNodeWithTag("confirm_start").performClick()
    }

    @Test fun automaticActivityUsesLiveEvidenceAndManualCorrectionSurvivesResume() {
        launch()
        compose.onNodeWithTag("activity_estimate", useUnmergedTree = true).assertTextEquals("Estimate: Activity unknown")
        val deadline = SystemClock.elapsedRealtime() + 18_000
        while (SystemClock.elapsedRealtime() < deadline) { inject(); Thread.sleep(1_100) }
        compose.onNodeWithTag("activity_estimate", useUnmergedTree = true).assertTextEquals("Estimate: Bike or ski")
        Thread.sleep(400)
        shell("screencap -p /sdcard/Download/cvkegps-home-estimate-light.png")
        startTrip()
        waitUntil { app.recorder.state.value.trip != null }
        val id = app.recorder.state.value.trip!!.id
        assertTrue(app.recorder.state.value.trip!!.automaticMode)
        assertEquals(TripMode.BikeOrSki, app.recorder.state.value.trip!!.mode)
        deliver(id, 8)
        fun cameraAtLatest(): Boolean {
            compose.waitForIdle()
            var centred = false
            scenario!!.onActivity { activity ->
                fun findMap(view: android.view.View): org.maplibre.android.maps.MapView? {
                    if (view is org.maplibre.android.maps.MapView) return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                        findMap(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                findMap(activity.window.decorView)?.getMapAsync { map ->
                    val target = map.cameraPosition.target
                    centred = target != null && kotlin.math.abs(target.latitude - 49.2827) < 0.0001 &&
                        kotlin.math.abs(target.longitude - (-123.1207 + sampleIndex * 0.0001)) < 0.0001 &&
                        map.cameraPosition.zoom in 3.0..18.0
                }
            }
            return centred
        }
        waitUntil { cameraAtLatest() }
        compose.onNodeWithTag("route_map").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("map_follow").assertIsDisplayed().performClick()
        waitUntil { cameraAtLatest() }
        compose.onNodeWithTag("route_map").assertIsDisplayed()
        Thread.sleep(400)
        shell("screencap -p /sdcard/Download/cvkegps-live-estimate-light.png")
        compose.onNodeWithTag("change_activity").performClick()
        compose.onNodeWithTag("mode_Ski").performClick()
        waitUntil { app.recorder.state.value.trip?.mode == TripMode.Ski }
        scenario!!.recreate()
        compose.onNodeWithTag("trip_pause").performScrollTo().performClick()
        waitUntil { app.recorder.state.value.trip?.status == TripStatus.Paused }
        assertEquals(0, requestCount())
        compose.onNodeWithTag("trip_resume").performScrollTo().performClick()
        waitUntil { app.recorder.state.value.trip?.status == TripStatus.Recording }
        deliver(id, 9)
        assertEquals(TripMode.Ski, app.recorder.state.value.trip!!.mode)
        assertFalse(app.recorder.state.value.trip!!.automaticMode)
    }

    @Test fun liveTrackerFitsThemesLargeTextAndRotation() {
        val font = shell("settings get system font_scale").trim()
        val rotation = shell("settings get system user_rotation").trim()
        val autoRotation = shell("settings get system accelerometer_rotation").trim()
        val night = shell("cmd uimode night").substringAfter(":").trim()
        try {
            launch()
            startTrip()
            waitUntil { app.recorder.state.value.trip != null }
            val id = app.recorder.state.value.trip!!.id
            for ((name, scale, dark) in listOf(Triple("light", 1f, false), Triple("dark", 1f, true), Triple("large-text", 2f, false))) {
                shell("settings put system font_scale $scale")
                shell("cmd uimode night ${if (dark) "yes" else "no"}")
                for (landscape in listOf(false, true)) {
                    shell("settings put system accelerometer_rotation 0")
                    shell("settings put system user_rotation ${if (landscape) 1 else 0}")
                    compose.waitUntil(5_000) {
                        var ready = false
                        scenario!!.onActivity {
                            ready = it.resources.configuration.fontScale == scale &&
                                (it.resources.configuration.screenWidthDp > it.resources.configuration.screenHeightDp) == landscape
                        }
                        ready
                    }
                    deliver(id, savedCount(id) + 1)
                    compose.onNodeWithTag("route_map").assertIsDisplayed()
                    compose.onNodeWithTag("trip_pause").performScrollTo().assertIsDisplayed()
                    compose.onNodeWithTag("trip_stop").performScrollTo().assertIsDisplayed()
                    Thread.sleep(500)
                    shell("screencap -p /sdcard/Download/cvkegps-tracker-$name-$landscape.png")
                }
            }
        } finally {
            shell("settings put system font_scale $font")
            shell("settings put system user_rotation $rotation")
            shell("settings put system accelerometer_rotation $autoRotation")
            shell("cmd uimode night $night")
        }
    }

    private fun deliver(id: String, count: Int) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (savedCount(id) < count && SystemClock.elapsedRealtime() < deadline) {
            inject()
            Thread.sleep(1_100)
        }
        assertTrue("Saved route did not receive the requested positions", savedCount(id) >= count)
    }

    private fun inject() {
        sampleIndex++
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 49.2827
            longitude = -123.1207 + sampleIndex * 0.0001
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = 3f
            speed = 10f
            speedAccuracyMetersPerSecond = 0.2f
            altitude = 100.0
            verticalAccuracyMeters = 4f
            bearing = 90f
            bearingAccuracyDegrees = 2f
        })
    }

    private fun savedCount(id: String) = runBlocking { app.database.trips().savedPoints(id).size }
    private fun requestCount() = shell("dumpsys location").substringBefore("Historical Aggregate Location Provider Data")
        .lineSequence().count { it.contains(context.packageName) && it.contains("Request[") && !it.contains("service:") }
    private fun grant(permission: String) = instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Timed out waiting for the recording state", condition())
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
