package com.example.cvkegps.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import com.example.cvkegps.R
import com.example.cvkegps.location.GpsReading
import com.example.cvkegps.settings.AltitudeReference
import com.example.cvkegps.settings.AltitudeUnit
import com.example.cvkegps.settings.DistanceUnit
import com.example.cvkegps.settings.SpeedUnit
import com.example.cvkegps.settings.Units
import java.text.NumberFormat
import java.util.Locale

/**
 * Formats SI measurements for display in the user's locale and [units]. Measurements stay in SI units until this point.
 * It does not use Compose, so the recording notification uses the same text as the screens.
 */
class DisplayFormat(locale: Locale, val units: Units, private val text: (Int, Array<out Any>) -> String) {
    constructor(resources: Resources, units: Units = Units.forCountry(resources.configuration.locales[0].country)) :
        this(resources.configuration.locales[0], units, { id, args -> resources.getString(id, *args) })

    /** A number in display units, with the unit text and the number of decimals that the number shows. */
    data class Amount(val value: Double, val unit: String, val decimals: Int)

    private val whole = NumberFormat.getIntegerInstance(locale)
    private val decimal = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    // A speed or an altitude has a maximum of five digits. Grouping would only add a separator to plane values.
    private val speedNumber = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; isGroupingUsed = false }
    private val altitudeNumber = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }
    private val twoDecimals = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
    private val twoDigits = NumberFormat.getIntegerInstance(locale).apply { minimumIntegerDigits = 2; isGroupingUsed = false }

    /** The speed number without a unit, for screens that show [speedUnit] separately. */
    fun speedNumber(metersPerSecond: Float): String = speedNumber.format(metersPerSecond * units.speed.perMetrePerSecond)

    val speedUnit: String get() = text(units.speed.label, emptyArray())

    fun speed(metersPerSecond: Float): String = withUnit(speedNumber(metersPerSecond), speedUnit)

    /** The speed for screen readers, with the unit name in full. */
    fun spokenSpeed(metersPerSecond: Float): String =
        text(R.string.speed_accessibility, arrayOf(speedNumber(metersPerSecond), text(units.speed.spoken, emptyArray())))

    fun speedAmount(metersPerSecond: Float) = Amount(metersPerSecond * units.speed.perMetrePerSecond, speedUnit, 1)

    /** Kilometres show metres below 1 km. Miles show feet below 0.1 mi. Nautical miles always show nautical miles. */
    fun distanceAmount(meters: Double): Amount = when {
        units.distance == DistanceUnit.Kilometres && meters < 1_000 -> Amount(meters, text(R.string.unit_metres, emptyArray()), 0)
        units.distance == DistanceUnit.Miles && meters < DistanceUnit.Miles.metres / 10 ->
            Amount(meters / AltitudeUnit.Feet.metres, text(R.string.unit_feet, emptyArray()), 0)
        else -> Amount(meters / units.distance.metres, text(units.distance.label, emptyArray()), 2)
    }

    fun distance(meters: Double): String = distanceAmount(meters).let {
        withUnit(if (it.decimals == 0) whole.format(it.value) else twoDecimals.format(it.value), it.unit)
    }

    /** The altitude number in whole units, without a unit. GPS altitude is not accurate to less than a metre. */
    fun altitudeNumber(meters: Double): String = altitudeNumber.format(meters / units.altitude.metres)

    val altitudeUnit: String get() = text(units.altitude.label, emptyArray())

    fun altitude(meters: Double): String = withUnit(altitudeNumber(meters), altitudeUnit)

    /** Seconds below one minute, minutes and seconds below one hour, then hours and minutes. */
    fun duration(nanos: Long): String {
        val seconds = (nanos / 1_000_000_000L).coerceAtLeast(0)
        return when {
            seconds >= 3_600 ->
                text(R.string.trip_hours_minutes, arrayOf(whole.format(seconds / 3_600), whole.format(seconds / 60 % 60)))
            seconds >= 60 ->
                text(R.string.trip_minutes_seconds, arrayOf(whole.format(seconds / 60), whole.format(seconds % 60)))
            else -> text(R.string.trip_seconds, arrayOf(whole.format(seconds)))
        }
    }

    /** A short clock time for narrow places, for example 12:04 or 1:02:04. */
    fun clock(nanos: Long): String {
        val seconds = (nanos / 1_000_000_000L).coerceAtLeast(0)
        val minutes = (seconds / 60 % 60).let { if (seconds >= 3_600) twoDigits.format(it) else whole.format(it) }
        val rest = "$minutes:${twoDigits.format(seconds % 60)}"
        return if (seconds >= 3_600) "${whole.format(seconds / 3_600)}:$rest" else rest
    }

    /** An acceleration in m/s² with one decimal. The app has no other unit for it. */
    fun acceleration(metersPerSecondSquared: Double): String = text(R.string.replay_acceleration_value, arrayOf(decimal.format(metersPerSecondSquared)))

    /** A plain number with one decimal, for metres and degrees. */
    fun decimal(value: Double): String = decimal.format(value)

    fun whole(value: Number): String = whole.format(value)

    private fun withUnit(number: String, unit: String) = text(R.string.value_with_unit, arrayOf(number, unit))
}

@get:StringRes val SpeedUnit.label get() = when (this) {
    SpeedUnit.KilometresPerHour -> R.string.unit_kmh
    SpeedUnit.MilesPerHour -> R.string.unit_mph
    SpeedUnit.Knots -> R.string.unit_knots
}

/** The unit name in full, for screen readers. */
@get:StringRes val SpeedUnit.spoken get() = when (this) {
    SpeedUnit.KilometresPerHour -> R.string.unit_kmh_spoken
    SpeedUnit.MilesPerHour -> R.string.unit_mph_spoken
    SpeedUnit.Knots -> R.string.unit_knots_spoken
}

@get:StringRes val DistanceUnit.label get() = when (this) {
    DistanceUnit.Kilometres -> R.string.unit_kilometres
    DistanceUnit.Miles -> R.string.unit_miles
    DistanceUnit.NauticalMiles -> R.string.unit_nautical_miles
}

@get:StringRes val DistanceUnit.spoken get() = when (this) {
    DistanceUnit.Kilometres -> R.string.unit_kilometres_spoken
    DistanceUnit.Miles -> R.string.unit_miles_spoken
    DistanceUnit.NauticalMiles -> R.string.unit_nautical_miles_spoken
}

@get:StringRes val AltitudeUnit.label get() = when (this) {
    AltitudeUnit.Metres -> R.string.unit_metres
    AltitudeUnit.Feet -> R.string.unit_feet
}

@get:StringRes val AltitudeUnit.spoken get() = when (this) {
    AltitudeUnit.Metres -> R.string.unit_metres_spoken
    AltitudeUnit.Feet -> R.string.unit_feet_spoken
}

/** The display choices that the screens read. The app shell provides them from the settings. */
data class DisplayChoices(val units: Units?, val altitude: AltitudeReference)

/** Null units follow the region of the current configuration. */
val LocalDisplayChoices = staticCompositionLocalOf { DisplayChoices(null, AltitudeReference.SeaLevel) }

/** The altitude above the chosen reference. Null when the reading has none, for example before Android 14 for sea level. */
fun GpsReading.altitude(reference: AltitudeReference): Double? =
    if (reference == AltitudeReference.SeaLevel) seaLevelAltitudeMeters else altitudeMeters

/** One formatter for each configuration and unit choice, so a language or unit change formats again. */
@Composable
fun rememberDisplayFormat(): DisplayFormat {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val units = LocalDisplayChoices.current.units
    return remember(resources, configuration, units) {
        if (units == null) DisplayFormat(resources) else DisplayFormat(resources, units)
    }
}
