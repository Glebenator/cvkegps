package com.example.cvkegps.tracking

import com.example.cvkegps.location.TrackSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMathTest {
    private fun sample(seconds: Long, longitude: Double = 0.0, speed: Float = 10f, accuracy: Float = 2f) = TrackSample(
        0.0, Math.toRadians(longitude), 1_000L + seconds * 1_000, seconds * 1_000_000_000,
        seconds * 1_000_000_000, accuracy, speed, 0.1f, null, null, null, null, false,
    )

    @Test fun distanceHandlesTheDateLineAndStationaryNoise() {
        val a = sample(1, 179.999)
        val b = sample(2, -179.999)
        assertEquals(222.39, RouteMath.distance(a, b), 0.1)
        val stopped = RouteMath.summarize(listOf(listOf(sample(1, speed = 0f), sample(2, 0.0001, speed = 0f))))
        assertEquals(0.0, stopped.distanceMeters, 0.0)
    }

    @Test fun lateAndDuplicatePointsDoNotBridgePausesPoorFixesOrSignalGaps() {
        val a = sample(1)
        val b = sample(2, 0.001)
        val poor = sample(3, 1.0, accuracy = 200f)
        val c = sample(4, 0.002)
        val late = sample(20, 0.003)
        val result = RouteMath.summarize(listOf(listOf(b, a, b, poor, c, late), listOf(sample(30, 4.0))))
        assertEquals(111.195, result.distanceMeters, 0.1)
        assertEquals(2, result.gapCount)
        assertEquals(4, result.lines.size)
        assertEquals(16_000_000_000L, result.gapNanos)
    }

    @Test fun liveRouteAddsPointsWithTheSavedRouteRules() {
        val first = listOf(sample(1), sample(2, 0.001), sample(3, 1.0, accuracy = 200f), sample(4, 0.002), sample(20, 0.003))
        val second = listOf(sample(30, 4.0), sample(31, 4.001, speed = 25f))
        val live = LiveRoute("trip")
        val shown = mutableListOf<RouteSummary>()
        (first.map { TripPoint("trip", "a", it) } + second.map { TripPoint("trip", "b", it) }).forEach { point ->
            assertTrue(live.add(point))
            shown += live.summary()
        }
        assertEquals(RouteMath.summarize(listOf(first, second)), live.summary())
        // A summary that a screen holds does not change when later points arrive.
        assertEquals(RouteMath.summarize(listOf(first.take(2))), shown[1])
        // A repeated point changes nothing. An older point needs a new load.
        assertTrue(live.add(TripPoint("trip", "b", second.last())))
        assertTrue(!live.add(TripPoint("trip", "b", sample(30, 4.0))))
        assertTrue(!live.add(TripPoint("trip", "a", sample(21, 0.004))))
    }

    @Test fun averageSpeedUsesRecordingTimeAndNeedsAKnownDistance() {
        assertEquals(5f, averageSpeed(300.0, 60_000_000_000)!!, 0f)
        assertEquals(0f, averageSpeed(0.0, 60_000_000_000)!!, 0f)
        assertNull(averageSpeed(null, 60_000_000_000))
        assertNull(averageSpeed(10.0, 999_999_999))
    }

    @Test fun invalidTimesAndCoordinatesAreRejectedWithoutCappingPlaneSpeed() {
        val plane = sample(1, speed = 250f)
        assertEquals(250f, plane.usableSpeed)
        assertTrue(!plane.copy(latitudeRadians = Double.NaN).valid())
        assertTrue(!plane.copy(elapsedNanos = plane.receivedElapsedNanos + 1).valid())
        assertTrue(!plane.copy(timeMillis = 0).valid())
    }
}
