package com.example.cvkegps.settings

/** A choice that the settings store. [key] is the stored value, so a rename in code does not change it. */
interface StoredChoice {
    val key: String
}

/** Returns the choice with this stored key, or null for an unknown key, for example from a later app version. */
inline fun <reified T> String?.toChoice(): T? where T : Enum<T>, T : StoredChoice = enumValues<T>().firstOrNull { it.key == this }

/** Speed units for display. [perMetrePerSecond] converts 1 m/s to this unit. */
enum class SpeedUnit(override val key: String, val perMetrePerSecond: Double) : StoredChoice {
    KilometresPerHour("kmh", 3.6),
    MilesPerHour("mph", 3_600 / METRES_PER_MILE),
    Knots("kn", 3_600 / METRES_PER_NAUTICAL_MILE),
}

/** Distance units for display. [metres] is the length of one unit. */
enum class DistanceUnit(override val key: String, val metres: Double) : StoredChoice {
    Kilometres("km", 1_000.0),
    Miles("mi", METRES_PER_MILE),
    NauticalMiles("nmi", METRES_PER_NAUTICAL_MILE),
}

/** Altitude units for display. [metres] is the length of one unit. */
enum class AltitudeUnit(override val key: String, val metres: Double) : StoredChoice {
    Metres("m", 1.0),
    Feet("ft", 0.3048),
}

/** The units that the screens and the notification use. Measurements stay in SI units until display. */
data class Units(val speed: SpeedUnit, val distance: DistanceUnit, val altitude: AltitudeUnit) {
    companion object {
        val Metric = Units(SpeedUnit.KilometresPerHour, DistanceUnit.Kilometres, AltitudeUnit.Metres)

        /**
         * The usual road units of a region, from its ISO 3166 country code. The United Kingdom uses miles and metres.
         * The United States, Liberia, and Myanmar use miles and feet. All other regions use metric units.
         */
        fun forCountry(country: String): Units = when (country.uppercase()) {
            "US", "LR", "MM" -> Units(SpeedUnit.MilesPerHour, DistanceUnit.Miles, AltitudeUnit.Feet)
            "GB" -> Units(SpeedUnit.MilesPerHour, DistanceUnit.Miles, AltitudeUnit.Metres)
            else -> Metric
        }
    }
}

/** The zero height of a displayed altitude. */
enum class AltitudeReference(override val key: String) : StoredChoice {
    /** Mean sea level, from the geoid model in Android 14 and later. */
    SeaLevel("sea_level"),
    /** The WGS 84 ellipsoid that GPS receivers report. It can differ from sea level by up to about 100 m. */
    Ellipsoid("ellipsoid"),
}

/** When the app keeps the screen on. The screens in [Settings] limit where. */
enum class ScreenOn(override val key: String) : StoredChoice {
    Off("off"),
    /** Only while a trip records. A paused trip lets the screen turn off. */
    DuringTrip("trip"),
    Always("always"),
}

const val METRES_PER_MILE = 1_609.344
const val METRES_PER_NAUTICAL_MILE = 1_852.0
