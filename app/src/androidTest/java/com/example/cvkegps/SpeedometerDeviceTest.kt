package com.example.cvkegps

import android.Manifest
import android.content.res.Configuration
import android.location.Location
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
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
        compose.onNodeWithTag("gps_action").performClick()
        clickSystemPermission("permission_deny_button")
        awaitText("Location access")
        assertFalse(hasGpsRequest())

        compose.onNodeWithTag("gps_action").performClick()
        clickSystemPermission("permission_allow_foreground_only_button")
        awaitSpeed(10f, "36")
        compose.onNodeWithTag("altitude").assertTextEquals("451")
    }

    @Test
    fun repeatedPermissionDenialCanRecoverThroughAppSettings() {
        launch(grantPermission = false)
        for (denyControl in listOf("permission_deny_button", "permission_deny_and_dont_ask_again_button")) {
            awaitText("Allow precise location")
            compose.onNodeWithTag("gps_action").performClick()
            clickSystemPermission(denyControl)
        }
        awaitText("Open app settings")
        assertFalse(hasGpsRequest())

        scenario!!.recreate()
        awaitText("Open app settings")
        compose.onNodeWithTag("gps_action").performClick()
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
        compose.onNodeWithTag("gps_action").performClick()
        awaitText("GPS paused")
        compose.waitUntil(5_000) { !hasGpsRequest() }

        compose.onNodeWithTag("gps_action").performClick()
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

    @Test
    fun homeFitsWithoutScrollWithLargeTextAndRotation() {
        val fontScale = shell("settings get system font_scale").trim()
        val rotation = shell("settings get system user_rotation").trim()
        val autoRotation = shell("settings get system accelerometer_rotation").trim()
        val nightMode = shell("cmd uimode night").substringAfter(":").trim()
        try {
            shell("cmd uimode night no")
            launch(grantPermission = false)
            for (scale in listOf(1f, 1.1f, 2f)) {
                for (landscape in listOf(false, true)) {
                    setDisplay(scale, landscape)
                    awaitText("Location access")
                    captureLayout("permission-$scale-$landscape")
                    assertHomeFits()
                }
            }
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.ACCESS_FINE_LOCATION)
            scenario!!.recreate()
            for (scale in listOf(1f, 1.1f, 2f)) {
                for (landscape in listOf(false, true)) {
                    setDisplay(scale, landscape)
                    awaitSpeed(34.3f, "123.5")
                    compose.onNodeWithTag("maximum_speed").assertTextEquals("123.5")
                    captureLayout("live-$scale-$landscape")
                    assertHomeFits()
                    compose.onNodeWithTag("gps_action").performClick()
                    awaitText("GPS paused")
                    assertHomeFits()
                    compose.onNodeWithTag("gps_action").performClick()
                    awaitSpeed(34.3f, "123.5")
                    manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                    awaitText("Location is off")
                    assertHomeFits()
                    manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                    awaitSpeed(34.3f, "123.5")
                }
            }
            setDisplay(1.1f, landscape = false)
            shell("cmd uimode night yes")
            compose.waitUntil(5_000) {
                var dark = false
                scenario!!.onActivity {
                    dark = it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
                }
                dark
            }
            awaitSpeed(34.3f, "123.5")
            captureLayout("live-dark")
            assertHomeFits()
        } finally {
            shell("settings put system font_scale $fontScale")
            shell("settings put system user_rotation $rotation")
            shell("settings put system accelerometer_rotation $autoRotation")
            shell("cmd uimode night $nightMode")
        }
    }

    private fun setDisplay(scale: Float, landscape: Boolean) {
        shell("settings put system font_scale $scale")
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation ${if (landscape) 1 else 0}")
        compose.waitUntil(5_000) {
            var matches = false
            scenario!!.onActivity {
                val config = it.resources.configuration
                matches = config.fontScale == scale &&
                    (config.screenWidthDp > config.screenHeightDp) == landscape
            }
            matches
        }
        compose.waitForIdle()
    }

    private fun assertHomeFits() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .assertCountEquals(0)
        val home = compose.onNodeWithTag("home_content").getUnclippedBoundsInRoot()
        for (tag in listOf("current_speed", "altitude", "maximum_speed", "gps_status", "gps_action")) {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$tag must fit inside the home screen: $bounds in $home",
                bounds.left >= home.left && bounds.top >= home.top &&
                    bounds.right <= home.right && bounds.bottom <= home.bottom)
        }
        val textNodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(hasTestTag("home_content")),
            useUnmergedTree = true,
        )
        repeat(textNodes.fetchSemanticsNodes().size) { index ->
            val bounds = textNodes[index].getUnclippedBoundsInRoot()
            assertTrue("Text bounds must fit inside the home screen: $bounds in $home",
                bounds.left >= home.left && bounds.top >= home.top &&
                    bounds.right <= home.right && bounds.bottom <= home.bottom)
            textNodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                action(layouts)
                layouts.forEach {
                    // Compose rebuilds this diagnostic paragraph at the available width.
                    // Check the text lines, not that paragraph's unused width.
                    val widestLine = (0 until it.lineCount).maxOf { line ->
                        it.getLineRight(line) - it.getLineLeft(line)
                    }
                    assertTrue(
                        "Text must fit: ${it.layoutInput.text}; size=${it.size}; " +
                            "lineWidth=$widestLine; textHeight=${it.multiParagraph.height}; bounds=$bounds",
                        !it.multiParagraph.didExceedMaxLines &&
                            widestLine <= it.size.width + 1f &&
                            it.multiParagraph.height <= it.size.height + 1f,
                    )
                }
            }
        }
    }

    private fun captureLayout(name: String) {
        // Keep captures after Orchestrator clears app data. Pull and remove them after the run.
        shell("screencap -p /sdcard/Download/cvkegps-layout-$name.png")
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
