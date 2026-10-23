package com.example.cvkegps.location

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkyPositionTest {
    @Test
    fun phoneDirectionMovesToTheTopAndNorthMovesLeft() {
        val ahead = skyPosition(PI / 2, 0.0, PI / 2)!!
        assertEquals(0.0, ahead.x, 1e-9)
        assertEquals(-1.0, ahead.y, 1e-9)
        val north = skyPosition(0.0, 0.0, PI / 2)!!
        assertEquals(-1.0, north.x, 1e-9)
        assertEquals(0.0, north.y, 1e-9)
        val wrap = skyPosition(Math.toRadians(1.0), 0.0, Math.toRadians(359.0))!!
        assertEquals(kotlin.math.sin(Math.toRadians(2.0)), wrap.x, 1e-9)
        assertNull(skyPosition(0.0, 0.0, Double.NaN))
    }

    @Test
    fun magneticDeclinationUsesTheCorrectSignAndWrapsAtNorth() {
        assertEquals(PI / 2, trueHeading(Math.toRadians(75.0), Math.toRadians(15.0))!!, 1e-9)
        assertEquals(Math.toRadians(60.0), trueHeading(Math.toRadians(75.0), Math.toRadians(-15.0))!!, 1e-9)
        assertEquals(Math.toRadians(5.0), trueHeading(Math.toRadians(355.0), Math.toRadians(10.0))!!, 1e-9)
        assertEquals(Math.toRadians(355.0), trueHeading(0.0, Math.toRadians(-5.0))!!, 1e-9)
        assertNull(trueHeading(Double.NaN, 0.0))
        assertNull(trueHeading(0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun cardinalDirectionsAtTheHorizon() {
        listOf(0.0 to -1.0, 1.0 to 0.0, 0.0 to 1.0, -1.0 to 0.0, 0.0 to -1.0)
            .forEachIndexed { index, (x, y) ->
                val point = skyPosition(index * PI / 2, 0.0)!!
                assertEquals(x, point.x, 1e-9)
                assertEquals(y, point.y, 1e-9)
            }
    }

    @Test
    fun elevationMovesFromHorizonToOverhead() {
        val halfway = skyPosition(PI / 2, PI / 4)!!
        assertEquals(0.5, halfway.x, 1e-9)
        assertEquals(0.0, halfway.y, 1e-9)
        listOf(0.0, PI / 2, PI, 2 * PI).forEach { azimuth ->
            val overhead = skyPosition(azimuth, PI / 2)!!
            assertEquals(0.0, overhead.x, 1e-9)
            assertEquals(0.0, overhead.y, 1e-9)
        }
    }

    @Test
    fun aClearCentreKeepsTheHorizonAndScalesElevationInTheBand() {
        val horizon = skyPosition(PI / 2, 0.0, zenithRadius = 0.6)!!
        assertEquals(1.0, horizon.x, 1e-9)
        val halfway = skyPosition(PI / 2, PI / 4, zenithRadius = 0.6)!!
        assertEquals(0.8, halfway.x, 1e-9)
        val overhead = skyPosition(PI, PI / 2, zenithRadius = 0.6)!!
        assertEquals(0.0, overhead.x, 1e-9)
        assertEquals(0.6, overhead.y, 1e-9)
        // Facing east puts an eastern satellite at the top of the band and north on the left.
        val ahead = skyPosition(PI / 2, PI / 4, PI / 2, 0.6)!!
        assertEquals(0.0, ahead.x, 1e-9)
        assertEquals(-0.8, ahead.y, 1e-9)
        listOf(-0.1, 1.0, Double.NaN).forEach { assertNull(skyPosition(0.0, 0.0, zenithRadius = it)) }
    }

    @Test
    fun headingDegreesWrapAtNorthAndAnglesTakeTheShortWay() {
        assertEquals(0, wholeDegrees(Math.toRadians(359.6)))
        assertEquals(90, wholeDegrees(Math.toRadians(-270.0)))
        assertEquals(Math.toRadians(2.0), angularDistance(Math.toRadians(359.0), Math.toRadians(1.0)), 1e-9)
        assertEquals(PI, angularDistance(0.0, PI), 1e-9)
    }

    @Test
    fun invalidAndBelowHorizonAnglesHaveNoPosition() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.01, 2 * PI + 0.01)
            .forEach { assertNull(skyPosition(it, PI / 4)) }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.01, PI / 2 + 0.01)
            .forEach { assertNull(skyPosition(0.0, it)) }
    }
}
