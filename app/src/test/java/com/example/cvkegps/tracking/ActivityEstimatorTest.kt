package com.example.cvkegps.tracking

import com.example.cvkegps.location.TrackSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityEstimatorTest {
    private fun sample(second: Int, speed: Float, accuracy: Float = 3f) = TrackSample(
        0.0, 0.0, 1_000L + second * 1_000, (second + 1L) * 1_000_000_000,
        (second + 1L) * 1_000_000_000, accuracy, speed, 0.3f, null, null, null, null, true,
    )

    @Test fun sustainedMovementProducesTentativeCategoriesNotFalsePrecision() {
        for ((speed, expected) in listOf(0f to TripMode.Unknown, 6f to TripMode.BikeOrSki,
            20f to TripMode.CarOrTransit, 70f to TripMode.Plane)) {
            val detector = ActivityEstimator()
            assertEquals(TripMode.Unknown, detector.accept(sample(0, speed))!!.mode)
            val estimate = (1..16).map { detector.accept(sample(it, speed)) }.last()!!
            assertEquals(expected, estimate.mode)
            assertEquals(TripMode.Unknown, estimate.fresh(estimate.elapsedNanos + 10_000_000_000))
        }
    }

    @Test fun tenFixesEachSecondGiveTheSameEstimateAsOne() {
        val detector = ActivityEstimator()
        var estimate: ActivityEstimate? = null
        for (tenth in 0..160) {
            val nanos = 1_000_000_000L + tenth * 100_000_000L
            estimate = detector.accept(sample(0, 20f).copy(timeMillis = 1_000L + tenth * 100L, elapsedNanos = nanos,
                receivedElapsedNanos = nanos))
        }
        assertEquals(TripMode.CarOrTransit, estimate!!.mode)
    }

    @Test fun noiseGapsAndBadReadingsCannotSupplySustainedEvidence() {
        val detector = ActivityEstimator()
        (0..16).forEach { detector.accept(sample(it, 20f)) }
        assertEquals(TripMode.Unknown, detector.accept(sample(17, 90f))!!.mode)
        assertNull(detector.accept(sample(16, 90f)))
        assertEquals(TripMode.Unknown, detector.accept(sample(18, 20f, 100f))!!.mode)
        assertEquals(TripMode.Unknown, detector.accept(sample(19, 20f))!!.mode)
        (20..35).forEach { detector.accept(sample(it, 20f)) }
        assertEquals(TripMode.Unknown, detector.accept(sample(50, 20f))!!.mode)
        assertNull(detector.accept(sample(51, 20f).let { it.copy(receivedElapsedNanos = it.elapsedNanos + 11_000_000_000) }))
        assertEquals(TripMode.Unknown, detector.accept(sample(52, 20f).copy(speedAccuracyMetersPerSecond = null))!!.mode)
        detector.reset()
        assertEquals(TripMode.Unknown, detector.accept(sample(0, 70f))!!.mode)
    }
}
