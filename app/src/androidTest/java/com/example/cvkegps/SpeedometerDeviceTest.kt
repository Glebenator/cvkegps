package com.example.cvkegps

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Full app tests through the Android location service. Run on a dedicated emulator.
 * Orchestrator clears app data between tests, including location permissions.
 */
@RunWith(AndroidJUnit4::class)
class SpeedometerDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val packageName = context.packageName
    private val manager = context.getSystemService(LocationManager::class.java)
    private val device = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalLocationEnabled = "true"

    @Before
    @Suppress("DEPRECATION")
    fun prepareGps() {
        originalLocationEnabled = shell("cmd location is-location-enabled").trim()
        shell("cmd location set-location-enabled true")
        shell("appops set $packageName android:mock_location allow")
        manager.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false, true, false, false, true, true, true,
            android.location.Criteria.POWER_HIGH, android.location.Criteria.ACCURACY_FINE,
        )
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        device.wakeUp()
        device.pressHome()
    }

    @After
    fun restoreDevice() {
        scenario?.close()
        manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        shell("appops set $packageName android:mock_location default")
        shell("cmd location set-location-enabled $originalLocationEnabled")
    }

    @Test
    fun permissionDenialCanRecoverThroughTheSystemDialog() {
        launch(grantPermission = false)
        awaitText("Location access")
        compose.onNodeWithTag("gps_action").performScrollTo().performClick()
        clickSystemPermission("permission_deny_button")
        awaitText("Location access")
        assertFalse(hasGpsRequest())

        compose.onNodeWithTag("gps_action").performScrollTo().performClick()
        clickSystemPermission("permission_allow_foreground_only_button")
        awaitSpeed(10f, "36")
        compose.onNodeWithTag("altitude").assertTextEquals("451")
    }

    @Test
    fun repeatedPermissionDenialCanRecoverThroughAppSettings() {
        launch(grantPermission = false)
        for (denyControl in listOf("permission_deny_button", "permission_deny_and_dont_ask_again_button")) {
            awaitText("Allow precise location")
            compose.onNodeWithTag("gps_action").performScrollTo().performClick()
            clickSystemPermission(denyControl)
        }
        awaitText("Open app settings")
        assertFalse(hasGpsRequest())

        scenario!!.recreate()
        awaitText("Open app settings")
        compose.onNodeWithTag("gps_action").performScrollTo().performClick()
        assertTrue(
            "The app details page must open",
            device.wait(Until.hasObject(By.text("App info").pkg("com.android.settings")), 10_000),
        )
        // Use Android's test API: Settings restricts permission editing for test location providers.
        instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(hasGpsRequest())
        device.pressBack()

        awaitSpeed(10f, "36")
        compose.onNodeWithTag("altitude").assertTextEquals("451")
        compose.onNodeWithTag("gps_action").assertTextEquals("Pause GPS")
    }

    @Test
    fun liveMeasurementsAndMaximumSurviveRecreation() {
        launch()
        awaitSpeed(10f, "36")
        compose.onNodeWithTag("maximum_speed").assertTextEquals("36")
        compose.onNodeWithTag("altitude").assertTextEquals("451")

        scenario!!.recreate()
        awaitSpeed(5f, "18")
        compose.onNodeWithTag("maximum_speed").assertTextEquals("36")
    }

    @Test
    fun poorFixDoesNotCreateAFalseSpeedOrMaximum() {
        launch()
        awaitSpeed(10f, "36")
        awaitFix(
            description = "Current speed unavailable",
            speed = 100f,
            accuracy = 200f,
        )
        compose.onNodeWithTag("maximum_speed").assertTextEquals("36")
        compose.onNodeWithTag("altitude").assertTextEquals("—")
    }

    @Test
    fun oldReadingsClearAndFreshReadingsRecover() {
        launch()
        awaitSpeed(10f, "36")
        awaitText("GPS signal lost", timeout = 13_000)
        compose.onNodeWithTag("maximum_speed").assertTextEquals("36")
        compose.waitUntil(2_000) {
            compose.onAllNodesWithContentDescription("Current speed unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        awaitSpeed(5f, "18")
    }

    @Test
    fun pauseAndBackgroundRemoveGpsRequests() {
        launch()
        awaitSpeed(10f, "36")
        compose.waitUntil(5_000) { hasGpsRequest() }
        compose.onNodeWithTag("gps_action").performScrollTo().performClick()
        awaitText("GPS paused")
        compose.waitUntil(5_000) { !hasGpsRequest() }

        compose.onNodeWithTag("gps_action").performScrollTo().performClick()
        awaitSpeed(5f, "18")
        scenario!!.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5_000) { !hasGpsRequest() }
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        awaitSpeed(5f, "18")
        compose.onNodeWithTag("maximum_speed").assertTextEquals("36")
    }

    @Test
    fun disabledGpsRecoversAfterItIsEnabled() {
        launch()
        awaitSpeed(10f, "36")
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        awaitText("Location is off")
        compose.onNodeWithTag("gps_action").assertTextEquals("Open location settings")
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        awaitSpeed(5f, "18")
    }

    private fun launch(grantPermission: Boolean = true) {
        if (grantPermission) {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun awaitSpeed(speed: Float, displayed: String) {
        awaitFix("Current speed: $displayed kilometres per hour", speed)
    }

    private fun awaitFix(description: String, speed: Float, accuracy: Float = 4f) {
        var lastSent = 0L
        compose.waitUntil(8_000) {
            if (SystemClock.elapsedRealtime() - lastSent > 1_100) {
                manager.setTestProviderLocation(
                    LocationManager.GPS_PROVIDER,
                    Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = 49.2827
                        longitude = -123.1207
                        altitude = 451.0
                        this.speed = speed
                        this.accuracy = accuracy
                        speedAccuracyMetersPerSecond = 0.2f
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    },
                )
                lastSent = SystemClock.elapsedRealtime()
            }
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String, timeout: Long = 5_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun clickSystemPermission(id: String) {
        // Android and Google system images use different permission-controller packages.
        val selector = By.res(Pattern.compile("[^:]+:id/${Pattern.quote(id)}"))
        val button = device.wait(Until.findObject(selector), 5_000)
        assertNotNull("Expected the Android permission control: $id", button)
        button.click()
        assertTrue("The Android permission dialog must close", device.wait(Until.gone(selector), 5_000))
    }

    private fun hasGpsRequest(): Boolean {
        // Exclude historical requests and event logs. Only current listener entries count.
        return shell("dumpsys location")
            .substringBefore("Historical Aggregate Location Provider Data")
            .lineSequence()
            .any { it.contains(packageName) && it.contains("Request[") }
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
