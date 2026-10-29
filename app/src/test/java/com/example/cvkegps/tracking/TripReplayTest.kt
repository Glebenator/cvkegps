package com.example.cvkegps.tracking

import com.example.cvkegps.location.FixSource
import com.example.cvkegps.location.TrackSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class TripReplayTest {
    private val second = 1_000_000_000L

    /** A fix moving east at [speed] m/s, [east] metres from the start. */
    private fun fix(seconds: Double, speed: Float, east: Double = 0.0, altitude: Double? = 100.0, accuracy: Float = 3f,
        source: FixSource = FixSource.Gps) = TrackSample(
        0.0, east / 6_371_008.8, 1_000L + (seconds * 1_000).toLong(), (seconds * second).toLong(), (seconds * second).toLong(),
        accuracy, speed, 0.3f, altitude, 3f, Math.PI / 2, 0.05, false, source,
    )

    @Test fun theReplayClockJoinsSegmentsAndAnOldFixIsNotCurrent() {
        val first = ReplaySegment(10 * second, 40 * second, listOf(fix(10.0, 5f), fix(11.0, 6f)), emptyList())
        // A pause of 60 seconds, then a second segment.
        val next = ReplaySegment(100 * second, 130 * second, listOf(fix(100.0, 7f), fix(101.0, 8f)), emptyList())
        val replay = ReplayAnalysis.analyze(listOf(first, next))
        assertEquals(60 * second, replay.durationNanos)
        assertEquals(listOf(30 * second), replay.segmentStarts)
        assertEquals(6f, replay.valuesAt(1 * second).speedMetersPerSecond)
        assertEquals(6f, replay.valuesAt(11 * second - 1).speedMetersPerSecond)
        // Ten seconds after the newest fix, the speed and the position are unavailable.
        assertNull(replay.valuesAt(11 * second).speedMetersPerSecond)
        assertNull(replay.valuesAt(11 * second).latitudeRadians)
        assertEquals(7f, replay.valuesAt(30 * second).speedMetersPerSecond)
        // Each segment has a GPS gap after its last fix.
        assertEquals(listOf(11 * second..30 * second, 41 * second..60 * second), replay.gpsGaps)
        val fastest = replay.moments.single { it.kind == MomentKind.FastestSpeed }
        assertEquals(8.0, fastest.value, 0.0)
        assertEquals(31 * second, fastest.timeNanos)
    }

    @Test fun theMapKnowsThePlayedPartOfEachRouteLine() {
        val first = ReplaySegment(10 * second, 20 * second, listOf(fix(10.0, 10f, 0.0), fix(11.0, 10f, 10.0), fix(12.0, 10f, 20.0)), emptyList())
        val next = ReplaySegment(100 * second, 110 * second, listOf(fix(100.0, 10f, 30.0), fix(101.0, 10f, 40.0)), emptyList())
        val replay = ReplayAnalysis.analyze(listOf(first, next))
        assertEquals(2, replay.lines.size)
        assertEquals(listOf(0L, second, 2 * second), replay.lineTimes[0].toList())
        assertEquals(2, replay.playedPoints(0, second))
        assertEquals(3, replay.playedPoints(0, 15 * second))
        // The pause is not replayed, so the second line starts at 10 seconds.
        assertEquals(0, replay.playedPoints(1, 10 * second - 1))
        assertEquals(1, replay.playedPoints(1, 10 * second))
        assertEquals(0, replay.playedPoints(2, 10 * second))
    }

    @Test fun aPositionIsInterpolatedOnlyWithinOneLine() {
        // A fix needs a time after 0, so the segment starts 1 second before the first fix.
        val samples = listOf(fix(1.0, 10f, 0.0), fix(2.0, 10f, 10.0), fix(16.0, 10f, 150.0), fix(17.0, 10f, 160.0))
        val replay = ReplayAnalysis.analyze(listOf(ReplaySegment(second, 21 * second, samples, emptyList())))
        val middle = replay.valuesAt(second / 2)
        assertEquals(5.0, middle.longitudeRadians!! * 6_371_008.8, 0.01)
        // After the last fix before the gap, the position stays on that fix until it is old. It does not move toward the next fix.
        assertEquals(10.0, replay.valuesAt(5 * second).longitudeRadians!! * 6_371_008.8, 0.01)
        assertNull(replay.valuesAt(12 * second).longitudeRadians)
    }

    @Test fun aNetworkPositionGivesAnApproximateRangeWithoutSpeed() {
        val samples = listOf(fix(0.0, 10f), fix(12.0, 10f, source = FixSource.Network).copy(speedMetersPerSecond = null),
            fix(17.0, 10f, source = FixSource.Network).copy(speedMetersPerSecond = null), fix(20.0, 10f))
        val replay = ReplayAnalysis.analyze(listOf(ReplaySegment(0, 21 * second, samples, emptyList())))
        assertEquals(listOf(12 * second..20 * second), replay.approximate)
        val values = replay.valuesAt(13 * second)
        assertEquals(FixSource.Network, values.fix!!.source)
        assertNull(values.speedMetersPerSecond)
        assertTrue(values.latitudeRadians != null)
    }

    @Test fun theBarometerGivesTheShapeAndTheGpsHeightGivesTheLevel() {
        // The road climbs 30 m in 30 s. The GPS height is noisy around the true height of 200 m plus the climb.
        val samples = (0..30).map { t -> fix(t.toDouble(), 10f, altitude = 200.0 + t + if (t % 2 == 0) 4.0 else -4.0) }
        val pressure = SensorChunk(SensorKind.Pressure, LongArray(301) { it * second / 10 },
            FloatArray(301) { heightToPressure(1_000.0 + it / 10.0).toFloat() })
        val segment = ReplaySegment(0, 30 * second, samples, listOf(pressure))
        val replay = ReplayAnalysis.analyze(listOf(segment))
        assertEquals(HeightSource.Barometer, replay.heightSource)
        assertEquals(215.0, replay.valuesAt(15 * second + second / 2).heightMeters!!.toDouble(), 4.5)
        // A 1 second mean applies for half a second past its centre, so the end of the replay has a height.
        val early = ReplayAnalysis.analyze(listOf(ReplaySegment(0, 30 * second, samples,
            listOf(SensorChunk(SensorKind.Pressure, pressure.elapsedNanos.copyOf(300), pressure.values.copyOf(300))))))
        assertTrue(early.valuesAt(early.durationNanos).heightMeters != null)
        val highest = replay.moments.single { it.kind == MomentKind.HighestPoint }
        assertEquals(230.0, highest.value, 4.5)
        // A plane trip ignores the cabin pressure.
        assertEquals(HeightSource.Gps, ReplayAnalysis.analyze(listOf(segment), gpsHeightOnly = true).heightSource)
    }

    @Test fun theAccelerometerGivesAccelerationAndBrakingAlongTheTrack() {
        // A car drives east. It accelerates at 2 m/s² for 10 s, drives at 20 m/s, then brakes at 3 m/s².
        fun speedAt(t: Double) = when {
            t < 5 -> 0.0
            t < 15 -> 2 * (t - 5)
            t < 25 -> 20.0
            t < 31 -> 20 - 3 * (t - 25)
            else -> 2.0
        }
        fun accelerationAt(t: Double) = when {
            t in 5.0..15.0 -> 2.0
            t in 25.0..31.0 -> -3.0
            else -> 0.0
        }
        val samples = (0..34).map { t -> fix(t.toDouble(), speedAt(t.toDouble()).toFloat()) }
        // The game frame is turned 40 degrees from east and north. The phone lies flat in that frame.
        val yaw = Math.toRadians(40.0)
        val times = LongArray(35 * 50) { it * second / 50 }
        val acceleration = FloatArray(times.size * 3)
        times.forEachIndexed { i, time ->
            val a = accelerationAt(time / 1e9)
            // East acceleration in a frame turned by the yaw.
            acceleration[i * 3] = (a * cos(yaw)).toFloat()
            acceleration[i * 3 + 1] = (-a * sin(yaw)).toFloat()
            acceleration[i * 3 + 2] = 9.80665f
        }
        val rotation = FloatArray(times.size * 4) { if (it % 4 == 3) 1f else 0f }
        val segment = ReplaySegment(0, 35 * second, samples, listOf(
            SensorChunk(SensorKind.Acceleration, times, acceleration), SensorChunk(SensorKind.Rotation, times, rotation)))
        val replay = ReplayAnalysis.analyze(listOf(segment))
        assertTrue(replay.accelerationFromSensors)
        assertEquals(2.0, replay.valuesAt(10 * second + second / 2).accelerationMetersPerSecondSquared!!.toDouble(), 0.2)
        val accelerating = replay.moments.single { it.kind == MomentKind.Acceleration }
        val braking = replay.moments.single { it.kind == MomentKind.Braking }
        assertEquals(2.0, accelerating.value, 0.2)
        assertEquals(-3.0, braking.value, 0.3)
        assertTrue(accelerating.timeNanos in 5 * second..15 * second)
        assertTrue(braking.timeNanos in 25 * second..31 * second)
        // Moments have numbers in time order.
        assertEquals(replay.moments.sortedBy { it.timeNanos }.map { it.number }, (1..replay.moments.size).toList())
        // Without the motion sensors, the change of GPS speed gives the acceleration.
        val gpsOnly = ReplayAnalysis.analyze(listOf(ReplaySegment(0, 35 * second, samples, emptyList())))
        assertFalse(gpsOnly.accelerationFromSensors)
        assertEquals(2.0, gpsOnly.moments.single { it.kind == MomentKind.Acceleration }.value, 0.01)
        assertEquals(-3.0, gpsOnly.moments.single { it.kind == MomentKind.Braking }.value, 0.01)
    }

    @Test fun aQuaternionTurnsTheAccelerationAndRemovesGravity() {
        // 90 degrees about the vertical axis: the phone's x axis points along the frame's y axis.
        val half = Math.sqrt(0.5).toFloat()
        val world = ReplayAnalysis.rotate(floatArrayOf(0f, 0f, half, half), floatArrayOf(1f, 0f, 9.80665f))
        assertEquals(0.0, world[0], 1e-6)
        assertEquals(1.0, world[1], 1e-6)
        assertEquals(0.0, world[2], 1e-5)
    }

    @Test fun aSteadyTripHasNoAccelerationOrHeightMoments() {
        val samples = (0..20).map { fix(it.toDouble(), 10f) }
        val replay = ReplayAnalysis.analyze(listOf(ReplaySegment(0, 20 * second, samples, emptyList())))
        assertEquals(listOf(MomentKind.FastestSpeed), replay.moments.map { it.kind })
    }

    private fun heightToPressure(meters: Double) = 1_013.25 * Math.pow(1 - meters / 44_330.0, 5.255)
}
