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
    /** Keep the screen on while the Speed tab is open. Off by default, because it uses more battery. */
    val keepScreenOn: Boolean = false,
    /** The user has read the trip recording explanation once. */
    val tripAccessExplained: Boolean = false,
    /** The map type on the trip maps. */
    val mapLayer: MapLayer = MapLayer.Default,
    /** Live traffic on the current trip map. Off by default, because it loads more map data. */
    val mapTraffic: Boolean = false,
)

/** The map types that the trip maps offer. [key] is the stored value, so a rename does not change it. */
enum class MapLayer(val key: String) {
    Default("default"),
    Satellite("satellite"),
    Terrain("terrain"),
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
            val stored = withContext(storage) {
                val read = preferences()
                val layer = read.getString(MAP_LAYER, null)
                Settings(read.getBoolean(KEEP_SCREEN_ON, false), read.getBoolean(TRIP_ACCESS_EXPLAINED, false),
                    // An unknown stored value, for example from a later app version, shows the default map.
                    MapLayer.entries.firstOrNull { it.key == layer } ?: MapLayer.Default, read.getBoolean(MAP_TRAFFIC, false))
            }
            val current = mutable.value
            mutable.value = Settings(
                keepScreenOn = if (KEEP_SCREEN_ON in chosen) current.keepScreenOn else stored.keepScreenOn,
                tripAccessExplained = if (TRIP_ACCESS_EXPLAINED in chosen) current.tripAccessExplained else stored.tripAccessExplained,
                mapLayer = if (MAP_LAYER in chosen) current.mapLayer else stored.mapLayer,
                mapTraffic = if (MAP_TRAFFIC in chosen) current.mapTraffic else stored.mapTraffic,
            )
        }
    }

    fun setKeepScreenOn(value: Boolean) {
        mutable.value = mutable.value.copy(keepScreenOn = value)
        write(KEEP_SCREEN_ON) { putBoolean(KEEP_SCREEN_ON, value) }
    }

    fun setTripAccessExplained() {
        mutable.value = mutable.value.copy(tripAccessExplained = true)
        write(TRIP_ACCESS_EXPLAINED) { putBoolean(TRIP_ACCESS_EXPLAINED, true) }
    }

    fun setMapLayer(value: MapLayer) {
        mutable.value = mutable.value.copy(mapLayer = value)
        write(MAP_LAYER) { putString(MAP_LAYER, value.key) }
    }

    fun setMapTraffic(value: Boolean) {
        mutable.value = mutable.value.copy(mapTraffic = value)
        write(MAP_TRAFFIC) { putBoolean(MAP_TRAFFIC, value) }
    }

    /** Writes only the changed value, so a change before the first read does not replace the other stored values. */
    private fun write(key: String, change: SharedPreferences.Editor.() -> Unit) {
        chosen += key
        scope.launch {
            // edit applies the change and writes it to disk in the background.
            withContext(storage) { preferences().edit(action = change) }
        }
    }

    /** Call only on [storage]. */
    private fun preferences(): SharedPreferences = preferences ?: open().also { preferences = it }

    private companion object {
        const val FILE = "settings"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val TRIP_ACCESS_EXPLAINED = "trip_access_explained"
        const val MAP_LAYER = "map_layer"
        const val MAP_TRAFFIC = "map_traffic"
    }
}
