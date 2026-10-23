package com.example.cvkegps.ui

import com.example.cvkegps.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DisplayFormatTest {
    private val templates = mapOf(
        R.string.trip_speed_value to "%1\$s km/h",
        R.string.trip_metres_value to "%1\$s m",
        R.string.trip_distance_value to "%1\$s km",
        R.string.trip_hours_minutes to "%1\$s h %2\$s min",
        R.string.trip_minutes_seconds to "%1\$s min %2\$s s",
        R.string.trip_seconds to "%1\$s s",
    )

    private fun format(locale: Locale) = DisplayFormat(locale) { id, args -> String.format(locale, templates.getValue(id), *args) }

    @Test fun convertsSiUnitsAndUsesTheLocaleDecimalSeparator() {
        val german = format(Locale.GERMANY)
        assertEquals("36 km/h", german.speed(10f))
        assertEquals("12,6", german.speedNumber(3.5f))
        assertEquals("1080", german.speedNumber(300f))
        assertEquals("999 m", german.distance(999.4))
        assertEquals("1,23 km", german.distance(1_234.0))
        assertEquals("1.234,5 km", german.distance(1_234_500.0))
    }

    @Test fun durationShowsOnlyTheUsefulUnits() {
        val english = format(Locale.US)
        assertEquals("0 s", english.duration(-1))
        assertEquals("59 s", english.duration(59_999_999_999))
        assertEquals("1 min 5 s", english.duration(65_000_000_000))
        assertEquals("2 h 3 min", english.duration(7_380_000_000_000))
    }
}
