package com.example.cvkegps.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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

/** Local settings in private shared preferences. The first read runs on the IO dispatcher. */
class AppSettings(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val mutable = MutableStateFlow(Settings())
    val values = mutable.asStateFlow()
    private var preferences: SharedPreferences? = null

    init {
        scope.launch {
            val stored = withContext(Dispatchers.IO) { this@AppSettings.context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
            preferences = stored
            // A change made before the read finished wins over the stored value.
            mutable.value = Settings(
                keepScreenOn = mutable.value.keepScreenOn || stored.getBoolean(KEEP_SCREEN_ON, false),
                tripAccessExplained = mutable.value.tripAccessExplained || stored.getBoolean(TRIP_ACCESS_EXPLAINED, false),
            )
        }
    }

    fun setKeepScreenOn(value: Boolean) = update { it.copy(keepScreenOn = value) }

    fun setTripAccessExplained() = update { it.copy(tripAccessExplained = true) }

    private fun update(change: (Settings) -> Settings) {
        val next = change(mutable.value)
        mutable.value = next
        scope.launch {
            val stored = preferences ?: withContext(Dispatchers.IO) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
            // edit applies the change and writes it to disk in the background.
            stored.edit {
                putBoolean(KEEP_SCREEN_ON, next.keepScreenOn)
                putBoolean(TRIP_ACCESS_EXPLAINED, next.tripAccessExplained)
            }
        }
    }

    private companion object {
        const val FILE = "settings"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val TRIP_ACCESS_EXPLAINED = "trip_access_explained"
    }
}
