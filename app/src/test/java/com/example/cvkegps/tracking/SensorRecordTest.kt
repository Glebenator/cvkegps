package com.example.cvkegps.tracking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorRecordTest {
    @Test fun storedReadingsReadBackWithTheirExactTimesAndValues() {
        val chunk = SensorChunk(SensorKind.Rotation, longArrayOf(1L, Long.MAX_VALUE),
            floatArrayOf(0.1f, -0.2f, 0.3f, 0.9f, Float.NaN, 0f, -0f, 1f))
        val read = SensorChunk.decode(SensorKind.Rotation, chunk.encode())!!
        assertEquals(2, read.count)
        assertArrayEquals(chunk.elapsedNanos, read.elapsedNanos)
        assertArrayEquals(chunk.values, read.values, 0f)
    }

    @Test fun dataWithoutWholeReadingsIsNotRead() {
        val pressure = SensorChunk(SensorKind.Pressure, longArrayOf(5L), floatArrayOf(1013.25f)).encode()
        assertNull(SensorChunk.decode(SensorKind.Pressure, pressure.copyOf(pressure.size - 1)))
        assertNull(SensorChunk.decode(SensorKind.Pressure, ByteArray(0)))
        // The data of one sensor kind does not read as another kind.
        assertNull(SensorChunk.decode(SensorKind.Acceleration, pressure))
    }
}
