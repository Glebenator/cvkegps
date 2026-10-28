package com.example.cvkegps.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User choices that stay after the app closes. Defaults apply until the stored values are read. */
data class Settings(
    /** When the app keeps the screen on. Off by default, because it uses more battery. */
    val screenOn: ScreenOn = ScreenOn.Off,
    /** Where the screen stays on: the Speed tab and the current trip map. */
    val screenOnSpeed: Boolean = true,
    val screenOnTripMap: Boolean = true,
    /** Dim a screen that the app keeps on after a time without touch. */
    val dimScreen: Boolean = false,
    /** Null units follow the region of the device. */
    val speedUnit: SpeedUnit? = null,
    val distanceUnit: DistanceUnit? = null,
    val altitudeUnit: AltitudeUnit? = null,
    val altitudeReference: AltitudeReference = AltitudeReference.SeaLevel,
    /** The user has read the trip recording explanation once. */
    val tripAccessExplained: Boolean = false,
    /** The map type on the trip maps. */
    val mapLayer: MapLayer = MapLayer.Default,
    /** Live traffic on the current trip map. Off by default, because it loads more map data. */
    val mapTraffic: Boolean = false,
    /** How often a trip records a position. It applies to the next trip that starts or resumes. */
    val recordingAccuracy: RecordingAccuracy = RecordingAccuracy.Standard,
    /** A recording trip uses a network position while it has no GPS signal. It applies to the next trip that starts or resumes. */
    val networkFallback: Boolean = true,
) {
    /** The chosen units, with the region's units where the user made no choice. */
    fun units(country: String): Units = Units.forCountry(country).let {
        Units(speedUnit ?: it.speed, distanceUnit ?: it.distance, altitudeUnit ?: it.altitude)
    }
}

/** The map types that the trip maps offer. */
enum class MapLayer(override val key: String) : StoredChoice {
    Default("default"),
    Satellite("satellite"),
    Terrain("terrain"),
}

/** How a trip uses the GPS receiver. [High] uses much more battery. */
enum class RecordingAccuracy(override val key: String) : StoredChoice {
    /** One position each second. While no screen shows, Android can deliver positions in groups. */
    Standard("standard"),
    /** The fastest positions that the phone can supply, delivered at once, with the GPS receiver fully on. */
    High("high"),
}

/**
 * Local settings in private shared preferences. All preference access runs in order on [storage], off the main thread.
 * A choice that the user makes before the stored values are read wins over the stored value, also when it is false.
 */
class AppSettings(
    private val open: () -> SharedPreferences,
    private val scope: CoroutineScope,
    private val storage: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    constructor(context: Context, scope: CoroutineScope) :
        this({ context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }, scope)

    private val mutable = MutableStateFlow(Settings())
    val values = mutable.asStateFlow()
    private var preferences: SharedPreferences? = null
    // Keys of the values that the user changed before the stored values arrived. The scope's thread reads and writes them.
    private val chosen = mutableSetOf<String>()

    init {
        scope.launch {
            val stored = withContext(storage) { read(preferences()) }
            val current = mutable.value
            fun <T> pick(key: String, choice: Settings.() -> T): T = (if (key in chosen) current else stored).choice()
            mutable.value = Settings(
                screenOn = pick(SCREEN_ON) { screenOn },
                screenOnSpeed = pick(SCREEN_ON_SPEED) { screenOnSpeed },
                screenOnTripMap = pick(SCREEN_ON_TRIP_MAP) { screenOnTripMap },
                dimScreen = pick(DIM_SCREEN) { dimScreen },
                speedUnit = pick(SPEED_UNIT) { speedUnit },
                distanceUnit = pick(DISTANCE_UNIT) { distanceUnit },
                altitudeUnit = pick(ALTITUDE_UNIT) { altitudeUnit },
                altitudeReference = pick(ALTITUDE_REFERENCE) { altitudeReference },
                tripAccessExplained = pick(TRIP_ACCESS_EXPLAINED) { tripAccessExplained },
                mapLayer = pick(MAP_LAYER) { mapLayer },
                mapTraffic = pick(MAP_TRAFFIC) { mapTraffic },
                recordingAccuracy = pick(RECORDING_ACCURACY) { recordingAccuracy },
                networkFallback = pick(NETWORK_FALLBACK) { networkFallback },
            )
        }
    }

    fun setScreenOn(value: ScreenOn) = change(SCREEN_ON, { copy(screenOn = value) }) { putString(SCREEN_ON, value.key) }
    fun setScreenOnSpeed(value: Boolean) = change(SCREEN_ON_SPEED, { copy(screenOnSpeed = value) }) { putBoolean(SCREEN_ON_SPEED, value) }
    fun setScreenOnTripMap(value: Boolean) =
        change(SCREEN_ON_TRIP_MAP, { copy(screenOnTripMap = value) }) { putBoolean(SCREEN_ON_TRIP_MAP, value) }
    fun setDimScreen(value: Boolean) = change(DIM_SCREEN, { copy(dimScreen = value) }) { putBoolean(DIM_SCREEN, value) }
    fun setSpeedUnit(value: SpeedUnit) = change(SPEED_UNIT, { copy(speedUnit = value) }) { putString(SPEED_UNIT, value.key) }
    fun setDistanceUnit(value: DistanceUnit) = change(DISTANCE_UNIT, { copy(distanceUnit = value) }) { putString(DISTANCE_UNIT, value.key) }
    fun setAltitudeUnit(value: AltitudeUnit) = change(ALTITUDE_UNIT, { copy(altitudeUnit = value) }) { putString(ALTITUDE_UNIT, value.key) }
    fun setAltitudeReference(value: AltitudeReference) =
        change(ALTITUDE_REFERENCE, { copy(altitudeReference = value) }) { putString(ALTITUDE_REFERENCE, value.key) }
    fun setTripAccessExplained() =
        change(TRIP_ACCESS_EXPLAINED, { copy(tripAccessExplained = true) }) { putBoolean(TRIP_ACCESS_EXPLAINED, true) }
    fun setMapLayer(value: MapLayer) = change(MAP_LAYER, { copy(mapLayer = value) }) { putString(MAP_LAYER, value.key) }
    fun setMapTraffic(value: Boolean) = change(MAP_TRAFFIC, { copy(mapTraffic = value) }) { putBoolean(MAP_TRAFFIC, value) }
    fun setRecordingAccuracy(value: RecordingAccuracy) =
        change(RECORDING_ACCURACY, { copy(recordingAccuracy = value) }) { putString(RECORDING_ACCURACY, value.key) }
    fun setNetworkFallback(value: Boolean) = change(NETWORK_FALLBACK, { copy(networkFallback = value) }) { putBoolean(NETWORK_FALLBACK, value) }

    /** Shows the change at once and writes only the changed value, so a change before the first read keeps the other stored values. */
    private fun change(key: String, update: Settings.() -> Settings, write: SharedPreferences.Editor.() -> Unit) {
        mutable.value = mutable.value.update()
        chosen += key
        scope.launch {
            // edit applies the change and writes it to disk in the background.
            withContext(storage) { preferences().edit(action = write) }
        }
    }

    /** Call only on [storage]. */
    private fun preferences(): SharedPreferences = preferences ?: open().also { preferences = it }

    private companion object {
        const val FILE = "settings"
        // Earlier builds stored one switch that kept the screen on for Speed and the current trip. It reads as Always.
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val SCREEN_ON = "screen_on"
        const val SCREEN_ON_SPEED = "screen_on_speed"
        const val SCREEN_ON_TRIP_MAP = "screen_on_trip_map"
        const val DIM_SCREEN = "dim_screen"
        const val SPEED_UNIT = "speed_unit"
        const val DISTANCE_UNIT = "distance_unit"
        const val ALTITUDE_UNIT = "altitude_unit"
        const val ALTITUDE_REFERENCE = "altitude_reference"
        const val TRIP_ACCESS_EXPLAINED = "trip_access_explained"
        const val MAP_LAYER = "map_layer"
        const val MAP_TRAFFIC = "map_traffic"
        const val RECORDING_ACCURACY = "recording_accuracy"
        const val NETWORK_FALLBACK = "network_fallback"

        /** An unknown stored value, for example from a later app version, reads as the default. */
        fun read(read: SharedPreferences) = Settings(
            screenOn = read.getString(SCREEN_ON, null).toChoice<ScreenOn>()
                ?: if (read.getBoolean(KEEP_SCREEN_ON, false)) ScreenOn.Always else ScreenOn.Off,
            screenOnSpeed = read.getBoolean(SCREEN_ON_SPEED, true),
            screenOnTripMap = read.getBoolean(SCREEN_ON_TRIP_MAP, true),
            dimScreen = read.getBoolean(DIM_SCREEN, false),
            speedUnit = read.getString(SPEED_UNIT, null).toChoice<SpeedUnit>(),
            distanceUnit = read.getString(DISTANCE_UNIT, null).toChoice<DistanceUnit>(),
            altitudeUnit = read.getString(ALTITUDE_UNIT, null).toChoice<AltitudeUnit>(),
            altitudeReference = read.getString(ALTITUDE_REFERENCE, null).toChoice<AltitudeReference>() ?: AltitudeReference.SeaLevel,
            tripAccessExplained = read.getBoolean(TRIP_ACCESS_EXPLAINED, false),
            mapLayer = read.getString(MAP_LAYER, null).toChoice<MapLayer>() ?: MapLayer.Default,
            mapTraffic = read.getBoolean(MAP_TRAFFIC, false),
            recordingAccuracy = read.getString(RECORDING_ACCURACY, null).toChoice<RecordingAccuracy>() ?: RecordingAccuracy.Standard,
            networkFallback = read.getBoolean(NETWORK_FALLBACK, true),
        )
    }
}
