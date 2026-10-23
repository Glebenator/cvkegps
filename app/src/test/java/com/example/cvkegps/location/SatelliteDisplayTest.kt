package com.example.cvkegps.location

import org.junit.Assert.assertEquals
import org.junit.Test

class SatelliteDisplayTest {
    @Test
    fun signalStrengthIsLinearBetweenTheReferencesAndZeroWhenMissing() {
        assertEquals(0f, signalStrength(SIGNAL_WEAK_DBHZ), 0f)
        assertEquals(0.5f, signalStrength(30f), 1e-6f)
        assertEquals(1f, signalStrength(SIGNAL_STRONG_DBHZ), 0f)
        assertEquals(0f, signalStrength(0f), 0f)
        assertEquals(1f, signalStrength(60f), 0f)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { assertEquals(0f, signalStrength(it), 0f) }
    }
}
