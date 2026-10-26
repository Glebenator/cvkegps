package com.example.cvkegps.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateTest {
    private val trip = TripRecord("trip", TripMode.Unknown, TripStatus.Recording, startedMillis = 1)
    private val second = 1_000_000_000L

    @Test fun noSignalStartsTenSecondsAfterTheNewestFixOrTheSegmentStart() {
        val fresh = RecordingState(trip, segmentStartNanos = 100 * second)
        assertEquals(110 * second, fresh.signalLostAtNanos)
        assertFalse(fresh.signalLost(109 * second))
        assertTrue(fresh.signalLost(110 * second))
        val fixed = fresh.copy(lastFixNanos = 105 * second)
        assertEquals(115 * second, fixed.signalLostAtNanos)
        // A fix from before the segment, for example after a resume, does not shorten the delay.
        assertEquals(110 * second, fresh.copy(lastFixNanos = 90 * second).signalLostAtNanos)
    }

    @Test fun pausedAndEndedTripsDoNotReportNoSignal() {
        assertNull(RecordingState(trip.copy(status = TripStatus.Paused), segmentStartNanos = second).signalLostAtNanos)
        assertNull(RecordingState(segmentStartNanos = second).signalLostAtNanos)
        assertFalse(RecordingState(trip.copy(status = TripStatus.Paused)).signalLost(Long.MAX_VALUE))
    }
}
