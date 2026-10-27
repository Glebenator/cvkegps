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
)

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
    // Values that the user changed before the stored values arrived. The scope's thread reads and writes them.
    private var chosenKeepScreenOn = false
    private var chosenTripAccessExplained = false

    init {
        scope.launch {
            val stored = withContext(storage) {
                val read = preferences()
                Settings(read.getBoolean(KEEP_SCREEN_ON, false), read.getBoolean(TRIP_ACCESS_EXPLAINED, false))
            }
            val current = mutable.value
            mutable.value = Settings(
                keepScreenOn = if (chosenKeepScreenOn) current.keepScreenOn else stored.keepScreenOn,
                tripAccessExplained = if (chosenTripAccessExplained) current.tripAccessExplained else stored.tripAccessExplained,
            )
        }
    }

    fun setKeepScreenOn(value: Boolean) {
        chosenKeepScreenOn = true
        mutable.value = mutable.value.copy(keepScreenOn = value)
        write(KEEP_SCREEN_ON, value)
    }

    fun setTripAccessExplained() {
        chosenTripAccessExplained = true
        mutable.value = mutable.value.copy(tripAccessExplained = true)
        write(TRIP_ACCESS_EXPLAINED, true)
    }

    /** Writes only the changed value, so a change before the first read does not replace the other stored value. */
    private fun write(key: String, value: Boolean) {
        scope.launch {
            // edit applies the change and writes it to disk in the background.
            withContext(storage) { preferences().edit { putBoolean(key, value) } }
        }
    }

    /** Call only on [storage]. */
    private fun preferences(): SharedPreferences = preferences ?: open().also { preferences = it }

    private companion object {
        const val FILE = "settings"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val TRIP_ACCESS_EXPLAINED = "trip_access_explained"
    }
}
