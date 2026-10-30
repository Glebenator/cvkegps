package com.example.cvkegps.tracking

import com.example.cvkegps.location.FixSource
import com.example.cvkegps.location.TrackSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedColoursTest {
    private val second = 1_000_000_000L

    private fun fix(seconds: Double, speed: Float?, accuracy: Float = 3f, speedAccuracy: Float = 0.3f) = TrackSample(
        0.0, 0.0, 1_000L + (seconds * 1_000).toLong(), (seconds * second).toLong(), (seconds * second).toLong(),
        accuracy, speed, speedAccuracy, 100.0, 3f, null, null, false, FixSource.Gps,
    )

    @Test fun smoothingAveragesFiveSecondsAndSkipsSpeedsThatAreNotUsable() {
        // The fix at 3 s has a speed error of 5 m/s, so its 40 m/s is not used.
        val line = listOf(fix(0.0, 10f), fix(1.0, 12f), fix(2.0, 14f), fix(3.0, 40f, speedAccuracy = 5f), fix(10.0, null), fix(11.0, 20f))
        val speeds = SpeedColours.smoothedSpeeds(line)
        assertEquals(12f, speeds[0]!!, 0.001f)
        assertEquals(12f, speeds[1]!!, 0.001f)
        // At 3 s only the fixes from 1 s to 5 s count, and the fix at 3 s is not usable.
        assertEquals(13f, speeds[3]!!, 0.001f)
        // At 10 s the fix has no speed, but the fix at 11 s is within 2.5 s.
        assertEquals(20f, speeds[4]!!, 0.001f)
        assertEquals(listOf<Float?>(null), SpeedColours.smoothedSpeeds(listOf(fix(0.0, null))))
    }

    @Test fun theScaleIgnoresStopsAndExtremesAndHasAMinimumRange() {
        // Stops at 0 m/s, 98 fixes between 5 and 25 m/s, and one wrong fix at 90 m/s.
        val speeds = listOf(0f, 0f, 0.2f) + (0 until 98).map { 5f + it * 20f / 97 } + listOf(90f)
        val scale = SpeedColours.scale(listOf(speeds))!!
        assertEquals(6f, scale.slowMetersPerSecond, 0.5f)
        assertEquals(24.2f, scale.fastMetersPerSecond, 0.5f)
        // An even speed gets a range around it, so small changes do not use the whole scale and the speed is in the middle colours.
        assertEquals(SpeedScale(9f, 11f), SpeedColours.scale(listOf(listOf(10f, 10f, 10f))))
        assertEquals(SpeedScale(0f, 2f), SpeedColours.scale(listOf(listOf(0.6f, 0.6f))))
        // A trip without moving speeds has no scale, so the route keeps one colour.
        assertNull(SpeedColours.scale(listOf(listOf(0f, null, 0.1f))))
    }

    @Test fun runsJoinEvenColoursAndKeepMissingSpeedsApart() {
        val scale = SpeedScale(10f, 20f)
        assertEquals(0f, SpeedColours.position(2f, scale))
        assertEquals(1f, SpeedColours.position(30f, scale))
        assertEquals(0.5f, SpeedColours.position(15.1f, scale))
        val positions = listOf(0.5f, 0.5f, 0.5f, 0.75f, null, null, 1f, 1f)
        assertEquals(listOf(SpeedRun(2, 0.5f, 0.5f), SpeedRun(1, 0.5f, 0.75f), SpeedRun(3, null, null), SpeedRun(1, 1f, 1f)),
            SpeedColours.runs(positions))
        // Every segment is in a run, so the line has no part in the default colour.
        assertEquals(positions.size - 1, SpeedColours.runs(positions).sumOf { it.segments })
    }
}
