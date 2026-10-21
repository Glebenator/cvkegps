package com.example.cvkegps

import android.Manifest
import android.app.NotificationManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
        compose.onNodeWithTag("mode_Bike").performClick()
        compose.onNodeWithTag("trip_start").performClick()
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
        compose.onNodeWithTag("nav_speed").performClick()
        compose.onNodeWithTag("gps_action").performClick()
        waitUntil { app.recorder.state.value.trip?.status == TripStatus.Paused }
        assertEquals(0, requestCount())
        compose.onNodeWithTag("gps_action").performClick()
        waitUntil { app.recorder.state.value.trip?.status == TripStatus.Recording }
        assertEquals(1, requestCount())
        compose.onNodeWithTag("nav_trips").performClick()
        compose.onNodeWithTag("trips_content").performScrollToNode(hasTestTag("trip_stop"))
        compose.onNodeWithTag("trip_stop").performClick()
        waitUntil { !app.recorder.state.value.needsService }
        val trip = runBlocking { app.database.trips().trip(id) }!!
        assertEquals(TripMode.Bike, trip.mode)
        assertEquals(TripStatus.Finished, trip.status)
        assertEquals(3, runBlocking { app.database.trips().segments(id).size })
        assertEquals(0, requestCount())
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 100 })
        compose.onAllNodesWithTag("saved_trip")[0].performClick()
        compose.onNodeWithTag("trips_content").performScrollToNode(hasTestTag("route_map"))
        compose.onNodeWithTag("route_map").assertIsDisplayed()
    }

    @Test fun signalLossRemainsAGapAndSavedTripCanBeDeleted() {
        launch()
        compose.onNodeWithTag("trip_start").performClick()
        waitUntil { app.recorder.state.value.trip != null }
        val id = app.recorder.state.value.trip!!.id
        deliver(id, 1)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        Thread.sleep(11_000)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        deliver(id, 2)
        compose.onNodeWithTag("trips_content").performScrollToNode(hasTestTag("trip_stop"))
        compose.onNodeWithTag("trip_stop").performClick()
        waitUntil { !app.recorder.state.value.needsService }
        val points = runBlocking { app.database.trips().savedPoints(id) }
        val summary = RouteMath.summarize(points.groupBy { it.segmentId }.values.map { it.map { point -> point.sample } })
        assertTrue(summary.gapCount > 0)
        assertEquals(0.0, summary.distanceMeters, 0.001)
        compose.onAllNodesWithTag("saved_trip")[0].performClick()
        compose.onNodeWithTag("trips_content").performScrollToNode(hasTestTag("trip_delete"))
        compose.onNodeWithTag("trip_delete").performClick()
        compose.onNodeWithTag("confirm_delete").performClick()
        waitUntil { runBlocking { app.database.trips().trip(id) } == null }
        assertEquals(0, savedCount(id))
    }

    @Test fun disabledLocationBlocksStartAndRecoversWithoutRestart() {
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        launch()
        compose.onNodeWithTag("trips_content").performScrollToNode(hasTestTag("trip_start"))
        compose.onNodeWithTag("trip_start").assertIsNotEnabled()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("trip_start").performClick()
        waitUntil { app.recorder.state.value.trip != null }
        deliver(app.recorder.state.value.trip!!.id, 1)
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("nav_trips").performClick()
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
