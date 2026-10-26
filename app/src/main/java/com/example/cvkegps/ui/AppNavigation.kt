package com.example.cvkegps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList

sealed interface Destination {
    data object Speed : Destination
    data object Trips : Destination
    data object GpsDetails : Destination
    data object LiveTrip : Destination
    data object Settings : Destination
    data class SavedTrip(val id: String) : Destination
}

enum class Tab(val root: Destination) { Speed(Destination.Speed), Trips(Destination.Trips) }

/** Keeps one back stack for each tab. Speed is the start tab, so Trips shows above it. */
@Stable
class AppNavigator(tab: Tab = Tab.Speed, speed: List<Destination> = listOf(Destination.Speed), trips: List<Destination> = listOf(Destination.Trips)) {
    var tab by mutableStateOf(tab)
        private set
    private val speed = speed.toMutableStateList()
    private val trips = trips.toMutableStateList()
    private val stack get() = if (tab == Tab.Speed) speed else trips

    val backStack: List<Destination> get() = if (tab == Tab.Speed) speed.toList() else listOf(Destination.Speed) + trips
    val atRoot: Boolean get() = stack.size == 1

    /** Selecting the current tab again returns it to its root. */
    fun select(tab: Tab) {
        if (this.tab == tab) stack.removeRange(1, stack.size) else this.tab = tab
    }

    fun push(destination: Destination) {
        if (stack.last() != destination) stack.add(destination)
    }

    /**
     * Opens a saved trip. A trip that is already open is replaced, so the list and detail layout
     * shows one trip beside the list, and Back returns to the list.
     */
    fun showTrip(id: String) {
        if (stack.last() is Destination.SavedTrip) stack[stack.lastIndex] = Destination.SavedTrip(id) else push(Destination.SavedTrip(id))
    }

    fun open(tab: Tab, vararg path: Destination) {
        this.tab = tab
        stack.removeRange(1, stack.size)
        path.forEach(::push)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else if (tab == Tab.Trips) tab = Tab.Speed
    }

    fun remove(destination: Destination) {
        listOf(speed, trips).forEach { list -> if (list.size > 1) list.removeAll { it == destination } }
    }

    companion object {
        private fun Destination.encode() = when (this) {
            Destination.Speed -> "speed"
            Destination.Trips -> "trips"
            Destination.GpsDetails -> "gps"
            Destination.LiveTrip -> "live"
            Destination.Settings -> "settings"
            is Destination.SavedTrip -> "trip:$id"
        }

        private fun decode(value: String) = when {
            value == "speed" -> Destination.Speed
            value == "trips" -> Destination.Trips
            value == "gps" -> Destination.GpsDetails
            value == "live" -> Destination.LiveTrip
            value == "settings" -> Destination.Settings
            else -> Destination.SavedTrip(value.removePrefix("trip:"))
        }

        val Saver: Saver<AppNavigator, Any> = Saver(
            save = { arrayListOf(it.tab.name, ArrayList(it.speed.map { d -> d.encode() }), ArrayList(it.trips.map { d -> d.encode() })) },
            restore = { saved ->
                val (tab, speed, trips) = saved as List<*>
                AppNavigator(Tab.valueOf(tab as String),
                    (speed as List<*>).map { decode(it as String) }, (trips as List<*>).map { decode(it as String) })
            },
        )
    }
}

@Composable
fun rememberAppNavigator(): AppNavigator = rememberSaveable(saver = AppNavigator.Saver) { AppNavigator() }
