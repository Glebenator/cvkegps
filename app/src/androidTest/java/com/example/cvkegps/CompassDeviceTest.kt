package com.example.cvkegps

import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.location.Location
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cvkegps.location.AndroidGpsSource
import com.example.cvkegps.location.screenHeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompassDeviceTest {
    @Test
    fun displayAxesAndGpsDeclinationUseAndroidTransforms() {
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

        val source = AndroidGpsSource(InstrumentationRegistry.getInstrumentation().targetContext)
        val location = Location("gps").apply {
            latitude = 49.2827
            longitude = -123.1207
            altitude = 451.0
            accuracy = 4f
            time = System.currentTimeMillis()
        }
        val reading = source.run { location.toReading() }
        val expected = GeomagneticField(49.2827f, -123.1207f, 451f, location.time).declination
        assertEquals(Math.toRadians(expected.toDouble()), reading.magneticDeclinationRadians!!, 1e-9)
        location.accuracy = 100f
        assertNull(source.run { location.toReading() }.magneticDeclinationRadians)
    }
}
