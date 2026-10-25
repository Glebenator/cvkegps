package com.example.cvkegps

import android.hardware.SensorManager
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.cvkegps.location.screenHeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompassDeviceTest {
    @Test
    fun displayAxesUseAndroidTransforms() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        listOf(Surface.ROTATION_0 to 0.0, Surface.ROTATION_90 to 90.0,
            Surface.ROTATION_180 to 180.0, Surface.ROTATION_270 to 270.0).forEach { (rotation, degrees) ->
            assertEquals(Math.toRadians(degrees), screenHeading(identity, rotation)!!, 1e-6)
        }
        val east = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(Math.PI / 2, screenHeading(east, Surface.ROTATION_0)!!, 1e-6)
        assertEquals(Math.PI, screenHeading(east, Surface.ROTATION_90)!!, 1e-6)
        val tilted = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(tilted, floatArrayOf(0.70710677f, 0f, 0f, 0.70710677f))
        assertNull(screenHeading(tilted, Surface.ROTATION_0))
        val invalid = identity.copyOf().apply { this[1] = Float.NaN }
        assertNull(screenHeading(invalid, Surface.ROTATION_0))
    }
}
