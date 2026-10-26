package com.example.cvkegps

import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cvkegps.location.HeadingReading
import com.example.cvkegps.location.HeadingSource
import com.example.cvkegps.ui.CompassViewModel
import com.example.cvkegps.ui.collectPhoneHeading
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** A 180 degree turn between the two landscape directions does not change the configuration. The compass must still follow it. */
@RunWith(AndroidJUnit4::class)
class DisplayRotationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun reverseLandscapeTurnsTheCompassAxes() {
        val rotation = shell("settings get system user_rotation").trim()
        val auto = shell("settings get system accelerometer_rotation").trim()
        val requested = CopyOnWriteArrayList<Int>()
        val source = object : HeadingSource {
            override fun updates(displayRotation: Int) = emptyFlow<HeadingReading?>().also { requested += displayRotation }
        }
        try {
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 1")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var rotated = false
                scenario.onActivity { rotated = it.display?.rotation == Surface.ROTATION_90 }
                assumeTrue("The display must accept a landscape rotation", rotated)
                val model = CompassViewModel(source)
                scenario.onActivity { activity -> activity.setContent { collectPhoneHeading(model, 0.0) } }
                compose.waitUntil(5_000) { requested.lastOrNull() == Surface.ROTATION_90 }
                shell("settings put system user_rotation 3")
                compose.waitUntil(5_000) { requested.lastOrNull() == Surface.ROTATION_270 }
            }
        } finally {
            shell("settings put system user_rotation $rotation")
            shell("settings put system accelerometer_rotation $auto")
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
