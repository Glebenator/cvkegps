package com.example.cvkegps.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FixInterpreterTest {
    private val interpreter = FixInterpreter { DECLINATION }

    private fun fix(accuracy: Float? = 4f, speed: Float? = 10f, speedAccuracy: Float? = 0.5f, latitude: Double = 0.86) = RawFix(
        latitude, -2.15, 1_000L, 1_000_000_000L, 1_000_000_000L, accuracy, speed, speedAccuracy, altitudeMeters = 451.0,
    )

    /** The Speed tab shows the reading and the trip stores the sample. Both must give the same speed. */
    private fun interpret(raw: RawFix) = interpreter.interpret(raw).also {
        assertEquals(it.sample.usableSpeed, it.reading.speedMetersPerSecond)
    }

    @Test fun networkFixKeepsItsPositionButNoMotionOrHeight() {
        val raw = fix().copy(source = FixSource.Network, bearingRadians = 1.0, verticalAccuracyMeters = 3f, satellitesUsed = 7)
        val (sample, reading) = interpret(raw)
        assertEquals(FixSource.Network, sample.source)
        assertEquals(4f, sample.accuracyMeters)
        assertNull(sample.speedMetersPerSecond)
        assertNull(sample.altitudeMeters)
        assertNull(sample.bearingRadians)
        assertNull(sample.verticalAccuracyMeters)
        assertNull(reading.speedMetersPerSecond)
        assertEquals(SpeedIssue.Missing, reading.speedIssue)
        assertEquals(7, sample.satellitesUsed)
        assertNull(interpret(fix().copy(satellitesUsed = -1)).sample.satellitesUsed)
    }

    @Test fun usableFixGivesSpeedAltitudeAndDeclination() {
        val reading = interpret(fix()).reading
        assertEquals(10f, reading.speedMetersPerSecond)
        assertNull(reading.speedIssue)
        assertEquals(451.0, reading.altitudeMeters!!, 0.0)
        assertEquals(DECLINATION, reading.magneticDeclinationRadians!!, 0.0)
    }

    @Test fun positionWorseThan50MetresRejectsSpeedAltitudeAndDeclination() {
        assertNull(interpret(fix(accuracy = 50f)).reading.speedIssue)
        for (accuracy in listOf(50.01f, null, Float.NaN, -1f)) {
            val reading = interpret(fix(accuracy = accuracy)).reading
            assertEquals(SpeedIssue.PositionAccuracy, reading.speedIssue)
            assertNull(reading.altitudeMeters)
            assertNull(reading.magneticDeclinationRadians)
        }
    }

    @Test fun speedAccuracyWorseThan3MetresPerSecondMakesTheSpeedUncertain() {
        assertEquals(10f, interpret(fix(speedAccuracy = 3f)).reading.speedMetersPerSecond)
        assertEquals(10f, interpret(fix(speedAccuracy = null)).reading.speedMetersPerSecond)
        assertEquals(SpeedIssue.SpeedAccuracy, interpret(fix(speedAccuracy = 3.01f)).reading.speedIssue)
    }

    @Test fun missingAndInvalidSpeedsHaveTheirOwnIssue() {
        assertEquals(SpeedIssue.Missing, interpret(fix(speed = null)).reading.speedIssue)
        assertEquals(SpeedIssue.Invalid, interpret(fix(speed = Float.NaN)).reading.speedIssue)
        assertEquals(SpeedIssue.Invalid, interpret(fix(speed = -1f)).reading.speedIssue)
    }

    /** A stored sample cannot show that the accuracy was reported. It must still give no usable speed (ADR 0001). */
    @Test fun invalidSpeedAccuracyRejectsTheSpeedAlsoInTheStoredSample() {
        for (speedAccuracy in listOf(Float.NaN, -1f)) {
            val result = interpret(fix(speedAccuracy = speedAccuracy))
            assertEquals(SpeedIssue.SpeedAccuracy, result.reading.speedIssue)
            assertNull(result.sample.usableSpeed)
        }
    }

    @Test fun declinationNeedsValidCoordinates() {
        assertNull(interpret(fix(latitude = Double.NaN)).reading.magneticDeclinationRadians)
        assertNull(interpret(fix(latitude = 2.0)).reading.magneticDeclinationRadians)
    }

    private companion object {
        const val DECLINATION = 0.25
    }
}
