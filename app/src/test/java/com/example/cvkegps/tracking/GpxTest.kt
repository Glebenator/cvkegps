package com.example.cvkegps.tracking

import com.example.cvkegps.location.TrackSample
import java.io.StringWriter
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTest {
    private fun sample(latitude: Double, longitude: Double, time: Long) = TrackSample(
        Math.toRadians(latitude), Math.toRadians(longitude), time, time * 1_000_000, time * 1_000_000,
        3f, 10f, 0.2f, 100.0, 4f, null, null, false,
    )

    @Test fun gapsStaySeparateAndNumbersUseADecimalPointInEveryLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val out = StringWriter()
            Gpx.write(out, "Trip <1> & \"A\"", listOf(
                listOf(sample(49.2827, -123.1207, 1_000), sample(49.2828, -123.1206, 2_000)),
                listOf(sample(49.29, -123.11, 20_000)),
            ))
            val gpx = out.toString()
            assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
            assertEquals(3, Regex("<trkpt ").findAll(gpx).count())
            assertTrue(gpx.contains("<trkpt lat=\"49.2827000\" lon=\"-123.1207000\"><time>1970-01-01T00:00:01Z</time></trkpt>"))
            assertTrue(gpx.contains("<name>Trip &lt;1&gt; &amp; &quot;A&quot;</name>"))
            // The recorded altitude is ellipsoidal, so it is not written as a GPX elevation.
            assertFalse(gpx.contains("<ele>"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun fileNameUsesTheLocalStartTime() {
        assertEquals("cvke-trip-2026-10-23-1400.gpx", Gpx.fileName(1_792_764_000_000, ZoneOffset.UTC))
    }
}
