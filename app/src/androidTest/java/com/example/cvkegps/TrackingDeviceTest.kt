package com.example.cvkegps

import android.Manifest
import android.app.NotificationManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsEnabled
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
        compose.onNodeWithTag("trip_details").performClick()
        compose.onNodeWithTag("change_activity").performClick()
        compose.onNodeWithTag("mode_Bike").performClick()
        compose.onNodeWithTag("close_trip_details").performClick()
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
        compose.onNodeWithTag("trip_details").performClick()
        compose.onNodeWithTag("trip_delete").performScrollTo()
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
        waitUntil { app.recorder.state.value.trip != null }
        compose.onNodeWithTag("show_route").performClick()
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
        val touchArea = compose.onNodeWithTag("route_map").fetchSemanticsNode().boundsInRoot
        device.swipe((touchArea.left + touchArea.width * 0.6f).toInt(),
            (touchArea.top + touchArea.height * 0.5f).toInt(),
            (touchArea.left + touchArea.width * 0.2f).toInt(),
            (touchArea.top + touchArea.height * 0.5f).toInt(), 30)
        compose.onNodeWithTag("map_follow").assertIsDisplayed().assertIsEnabled().performClick()
        waitUntil { cameraAtLatest() }
        compose.onNodeWithTag("route_map").assertIsDisplayed()
        Thread.sleep(400)
        shell("screencap -p /sdcard/Download/cvkegps-live-estimate-light.png")
        compose.onNodeWithTag("trip_details").performClick()
        compose.onNodeWithTag("change_activity").performClick()
        compose.onNodeWithTag("mode_Ski").performClick()
        compose.onNodeWithTag("close_trip_details").performClick()
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

    @Test fun recordingOnHomeKeepsOneRequestAndGpsBarRecovers() {
        launch()
        compose.onNodeWithTag("trip_start").performClick()
        compose.onNodeWithTag("confirm_start").performClick()
        waitUntil { app.recorder.state.value.trip != null }
        val id = app.recorder.state.value.trip!!.id
        deliver(id, 2)
        compose.onNodeWithTag("current_speed").assertIsDisplayed()
        compose.onNodeWithTag("gps_compact").assertIsDisplayed()
        compose.onNodeWithTag("recording_status").assertTextEquals("Recording")
        compose.onNodeWithTag("route_map").assertDoesNotExist()
        assertEquals(1, requestCount())
        capture("home-light")
        scenario!!.recreate()
        deliver(id, 3)
        compose.onNodeWithTag("current_speed").assertIsDisplayed()
        // A poor position expands the GPS panel. A good position makes it small again.
        inject(30f)
        Thread.sleep(1_200)
        inject(30f)
        waitUntil { compose.onAllNodesWithTag("gps_compact").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("position_accuracy").assertIsDisplayed()
        Thread.sleep(1_100)
        deliver(id, savedCount(id) + 1)
        compose.onNodeWithTag("gps_compact").assertIsDisplayed()
        // Old readings must expand the warning and hide the current speed.
        Thread.sleep(10_500)
        compose.onNodeWithTag("gps_compact").assertDoesNotExist()
        compose.onNodeWithTag("gps_status").assertTextEquals("GPS signal lost")
        deliver(id, savedCount(id) + 1)
        compose.onNodeWithTag("gps_compact").assertIsDisplayed()
        compose.onNodeWithTag("show_route").performClick()
        compose.onNodeWithTag("route_map").assertIsDisplayed()
        device.pressBack()
        compose.onNodeWithTag("current_speed").assertIsDisplayed()
        deliver(id, savedCount(id) + 1)
        compose.onNodeWithTag("nav_trips").performClick()
        deliver(id, savedCount(id) + 1)
        assertEquals(1, requestCount())
        compose.onNodeWithTag("trips_content").assertIsDisplayed()
        device.pressBack()
        waitUntil { runCatching { compose.onNodeWithTag("trip_pause").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("trip_pause").performClick()
        waitUntil { app.recorder.state.value.trip?.status == TripStatus.Paused }
        assertEquals(0, requestCount())
        compose.onNodeWithTag("trip_resume").performClick()
        deliver(id, savedCount(id) + 1)
        compose.onNodeWithTag("trip_stop").performClick()
        waitUntil { !app.recorder.state.value.needsService }
        compose.onNodeWithTag("route_map").assertIsDisplayed()
        assertEquals(0, requestCount())
    }

    @Test fun savedMapPansZoomsAndKeepsCameraThroughDetailsAndRotation() {
        launch()
        startTrip()
        val id = app.recorder.state.value.trip!!.id
        deliver(id, 4)
        compose.onNodeWithTag("trip_stop").performClick()
        waitUntil { !app.recorder.state.value.needsService }
        waitUntil { mapCamera()?.zoom?.let { it > 1 } == true }
        val fitted = mapCamera()!!
        val review = compose.onNodeWithTag("trip_review").getUnclippedBoundsInRoot()
        val mapBounds = compose.onNodeWithTag("route_map").getUnclippedBoundsInRoot()
        assertTrue("The map must use most of the review screen", (mapBounds.bottom - mapBounds.top) > (review.bottom - review.top) * 0.6f)
        capture("saved-before-pan")
        val touchArea = compose.onNodeWithTag("route_map").fetchSemanticsNode().boundsInRoot
        device.swipe((touchArea.left + touchArea.width * 0.3f).toInt(),
            (touchArea.top + touchArea.height * 0.6f).toInt(),
            (touchArea.left + touchArea.width * 0.3f).toInt(),
            (touchArea.top + touchArea.height * 0.25f).toInt(), 30)
        Thread.sleep(700)
        capture("saved-after-pan")
        val moved = mapCamera()!!
        assertTrue("A map gesture must move the camera", moved.target != fitted.target)
        compose.onNodeWithTag("map_zoom_out").performClick()
        waitUntil { mapCamera()!!.zoom < moved.zoom - 0.5 }
        Thread.sleep(600)
        val zoomed = mapCamera()!!
        capture("saved-map-light")
        compose.onNodeWithTag("trip_details").performClick()
        compose.onNodeWithTag("trip_delete").performScrollTo().assertIsDisplayed()
        capture("saved-details")
        compose.onNodeWithTag("close_trip_details").performScrollTo().performClick()
        assertEquals(zoomed.zoom, mapCamera()!!.zoom, 0.01)
        assertEquals(zoomed.target!!.latitude, mapCamera()!!.target!!.latitude, 0.0001)
        scenario!!.recreate()
        waitUntil { mapCamera()?.zoom?.let { kotlin.math.abs(it - zoomed.zoom) < 0.01 } == true }
        assertEquals(zoomed.target!!.latitude, mapCamera()!!.target!!.latitude, 0.0001)
        compose.onNodeWithTag("map_fit").performClick()
        waitUntil { kotlin.math.abs(mapCamera()!!.target!!.latitude - fitted.target!!.latitude) < 0.0001 }
        compose.onNodeWithTag("nav_speed").performClick()
        compose.onNodeWithTag("saved_trip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("route_map").assertIsDisplayed()
    }

    @Test fun homeAndSavedMapFitThemesLargeTextAndRotation() {
        val font = shell("settings get system font_scale").trim()
        val rotation = shell("settings get system user_rotation").trim()
        val auto = shell("settings get system accelerometer_rotation").trim()
        val night = shell("cmd uimode night").substringAfter(":").trim()
        try {
            launch()
            startTrip()
            val id = app.recorder.state.value.trip!!.id
            compose.onNodeWithTag("nav_speed").performClick()
            for ((name, scale, dark) in listOf(Triple("light", 1f, false), Triple("dark", 1f, true), Triple("large-text", 2f, false))) {
                for (landscape in listOf(false, true)) {
                    setDisplay(scale, landscape, dark)
                    deliver(id, savedCount(id) + 1)
                    for (tag in listOf("current_speed", "gps_compact", "show_route", "trip_pause", "trip_stop")) {
                        compose.onNodeWithTag(tag).assertIsDisplayed()
                    }
                    capture("home-$name-$landscape")
                }
            }
            compose.onNodeWithTag("trip_stop").performClick()
            waitUntil { !app.recorder.state.value.needsService }
            for ((name, scale, dark) in listOf(Triple("light", 1f, false), Triple("dark", 1f, true), Triple("large-text", 2f, false))) {
                for (landscape in listOf(false, true)) {
                    setDisplay(scale, landscape, dark)
                    compose.onNodeWithTag("route_map").assertIsDisplayed()
                    compose.onNodeWithTag("map_fit").assertIsDisplayed()
                    compose.onNodeWithTag("trip_details").performScrollTo().assertIsDisplayed()
                    capture("saved-$name-$landscape")
                    compose.onNodeWithTag("trip_details").performClick()
                    compose.onNodeWithTag("trip_delete").performScrollTo().assertIsDisplayed()
                    compose.onNodeWithTag("close_trip_details").performScrollTo().performClick()
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
                ready = it.resources.configuration.fontScale == scale &&
                    (it.resources.configuration.screenWidthDp > it.resources.configuration.screenHeightDp) == landscape &&
                    (it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES) == dark
            }
            ready
        }
    }

    private fun mapCamera(): org.maplibre.android.camera.CameraPosition? {
        compose.waitForIdle()
        var camera: org.maplibre.android.camera.CameraPosition? = null
        scenario!!.onActivity { activity ->
            fun findMap(view: android.view.View): org.maplibre.android.maps.MapView? {
                if (view is org.maplibre.android.maps.MapView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                    findMap(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            findMap(activity.window.decorView)?.getMapAsync { camera = it.cameraPosition }
        }
        return camera
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(500)
        shell("screencap -p /sdcard/Download/cvkegps-polish-$name.png")
    }

    private fun deliver(id: String, count: Int) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (savedCount(id) < count && SystemClock.elapsedRealtime() < deadline) {
            inject()
            Thread.sleep(1_100)
        }
        assertTrue("Saved route did not receive the requested positions", savedCount(id) >= count)
    }

    private fun inject(accuracyMeters: Float = 3f) {
        sampleIndex++
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 49.2827
            longitude = -123.1207 + sampleIndex * 0.0001
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = accuracyMeters
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
