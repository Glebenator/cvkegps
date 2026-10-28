package com.example.cvkegps.ui

import com.example.cvkegps.R
import com.example.cvkegps.settings.AltitudeUnit
import com.example.cvkegps.settings.DistanceUnit
import com.example.cvkegps.settings.SpeedUnit
import com.example.cvkegps.settings.Units
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DisplayFormatTest {
    private val templates = mapOf(
        R.string.value_with_unit to "%1\$s %2\$s",
        R.string.unit_kmh to "km/h",
        R.string.unit_mph to "mph",
        R.string.unit_knots to "kn",
        R.string.unit_metres to "m",
        R.string.unit_kilometres to "km",
        R.string.unit_feet to "ft",
        R.string.unit_miles to "mi",
        R.string.unit_nautical_miles to "nmi",
        R.string.trip_hours_minutes to "%1\$s h %2\$s min",
        R.string.trip_minutes_seconds to "%1\$s min %2\$s s",
        R.string.trip_seconds to "%1\$s s",
    )

    private fun format(locale: Locale, units: Units = Units.Metric) =
        DisplayFormat(locale, units) { id, args -> String.format(locale, templates.getValue(id), *args) }

    @Test fun convertsSiUnitsAndUsesTheLocaleDecimalSeparator() {
        val german = format(Locale.GERMANY)
        assertEquals("36 km/h", german.speed(10f))
        assertEquals("12,6", german.speedNumber(3.5f))
        assertEquals("1080", german.speedNumber(300f))
        assertEquals("999 m", german.distance(999.4))
        assertEquals("1,23 km", german.distance(1_234.0))
        assertEquals("1.234,5 km", german.distance(1_234_500.0))
    }

    @Test fun imperialAndNauticalUnitsConvertFromSiUnits() {
        val imperial = format(Locale.US, Units.forCountry("US"))
        assertEquals("22.4 mph", imperial.speed(10f))
        assertEquals("528 ft", imperial.distance(160.9))
        assertEquals("0.1 mi", imperial.distance(161.0))
        assertEquals("1,000 mi", imperial.distance(1_609_344.0))
        assertEquals("29032 ft", imperial.altitude(8_849.0))
        assertEquals("29032", imperial.altitudeNumber(8_849.0))
        val nautical = format(Locale.US, Units(SpeedUnit.Knots, DistanceUnit.NauticalMiles, AltitudeUnit.Metres))
        assertEquals("19.4 kn", nautical.speed(10f))
        assertEquals("0.05 nmi", nautical.distance(92.6))
        assertEquals("-12 m", nautical.altitude(-12.4))
    }

    @Test fun regionsGetTheirRoadUnitsAndOtherRegionsGetMetricUnits() {
        assertEquals(Units(SpeedUnit.MilesPerHour, DistanceUnit.Miles, AltitudeUnit.Feet), Units.forCountry("us"))
        assertEquals(Units(SpeedUnit.MilesPerHour, DistanceUnit.Miles, AltitudeUnit.Metres), Units.forCountry("GB"))
        assertEquals(Units.Metric, Units.forCountry("DE"))
        assertEquals(Units.Metric, Units.forCountry(""))
    }

    @Test fun durationShowsOnlyTheUsefulUnits() {
        val english = format(Locale.US)
        assertEquals("0 s", english.duration(-1))
        assertEquals("59 s", english.duration(59_999_999_999))
        assertEquals("1 min 5 s", english.duration(65_000_000_000))
        assertEquals("2 h 3 min", english.duration(7_380_000_000_000))
    }

    @Test fun clockPadsMinutesAndSecondsOnlyWhereNeeded() {
        val english = format(Locale.US)
        assertEquals("0:00", english.clock(-1))
        assertEquals("0:09", english.clock(9_999_999_999))
        assertEquals("12:04", english.clock(724_000_000_000))
        assertEquals("1:02:04", english.clock(3_724_000_000_000))
    }
}
