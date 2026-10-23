package com.example.cvkegps.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import com.example.cvkegps.R
import java.text.NumberFormat
import java.util.Locale

/**
 * Formats SI measurements for display in the user's locale. Measurements stay in SI units until this point.
 * It does not use Compose, so the recording notification uses the same text as the screens.
 */
class DisplayFormat(locale: Locale, private val text: (Int, Array<out Any>) -> String) {
    constructor(resources: Resources) : this(resources.configuration.locales[0], { id, args -> resources.getString(id, *args) })

    private val whole = NumberFormat.getIntegerInstance(locale)
    private val decimal = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    // A speed has a maximum of four digits. Grouping would only add a separator to plane speeds.
    private val speedNumber = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; isGroupingUsed = false }
    private val kilometres = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }

    /** The speed number in km/h, without a unit, for screens that show the unit separately. */
    fun speedNumber(metersPerSecond: Float): String = speedNumber.format(metersPerSecond * KMH_PER_MPS)

    fun speed(metersPerSecond: Float): String = text(R.string.trip_speed_value, arrayOf(speedNumber(metersPerSecond)))

    /** Metres below 1 km, then kilometres with two decimals. */
    fun distance(meters: Double): String = if (meters < 1_000) {
        text(R.string.trip_metres_value, arrayOf(whole.format(meters)))
    } else text(R.string.trip_distance_value, arrayOf(kilometres.format(meters / 1_000)))

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

    /** A plain number with one decimal, for metres and degrees. */
    fun decimal(value: Double): String = decimal.format(value)

    fun whole(value: Number): String = whole.format(value)

    companion object {
        const val KMH_PER_MPS = 3.6
    }
}

/** One formatter for each configuration, so a language change formats again. */
@Composable
fun rememberDisplayFormat(): DisplayFormat {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { DisplayFormat(resources) }
}
