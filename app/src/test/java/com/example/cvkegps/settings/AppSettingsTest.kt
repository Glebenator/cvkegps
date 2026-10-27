package com.example.cvkegps.settings

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A user choice made while the stored settings still load must win, also when it is false. */
class AppSettingsTest {
    private val ui = Executors.newSingleThreadExecutor { Thread(it, "ui") }
    private val uiDispatcher = ui.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + uiDispatcher)

    @After fun close() {
        scope.cancel()
        ui.shutdownNow()
    }

    @Test fun anOffChoiceBeforeTheLoadWinsAndKeepsTheOtherStoredValue() {
        val stored = FakePreferences(mapOf(KEEP_SCREEN_ON to true, TRIP_ACCESS_EXPLAINED to true))
        val load = CountDownLatch(1)
        val opened = mutableListOf<String>()
        val settings = onUi { AppSettings({ opened += Thread.currentThread().name; load.await(); stored }, scope) }
        onUi { settings.setKeepScreenOn(false) }
        load.countDown()
        waitFor { settings.values.value.tripAccessExplained }
        assertEquals(Settings(keepScreenOn = false, tripAccessExplained = true), settings.values.value)
        waitFor { stored.values[KEEP_SCREEN_ON] == false }
        assertEquals(true, stored.values[TRIP_ACCESS_EXPLAINED])
        // The preference file is opened and read away from the screen thread.
        assertTrue(opened.isNotEmpty() && "ui" !in opened)
        assertTrue(stored.readThreads.isNotEmpty() && "ui" !in stored.readThreads)
    }

    @Test fun anOnChoiceBeforeTheLoadWins() {
        val stored = FakePreferences(mapOf(KEEP_SCREEN_ON to false))
        val load = CountDownLatch(1)
        val settings = onUi { AppSettings({ load.await(); stored }, scope) }
        onUi { settings.setTripAccessExplained() }
        onUi { settings.setKeepScreenOn(true) }
        load.countDown()
        waitFor { stored.values[KEEP_SCREEN_ON] == true && stored.values[TRIP_ACCESS_EXPLAINED] == true }
        assertEquals(Settings(keepScreenOn = true, tripAccessExplained = true), settings.values.value)
    }

    @Test fun anUnknownStoredMapTypeShowsTheDefaultMapAndAMapChoiceIsStored() {
        val stored = FakePreferences(mapOf(MAP_LAYER to "street-view", MAP_TRAFFIC to true))
        val settings = onUi { AppSettings({ stored }, scope) }
        waitFor { settings.values.value.mapTraffic }
        assertEquals(MapLayer.Default, settings.values.value.mapLayer)
        onUi { settings.setMapLayer(MapLayer.Terrain) }
        waitFor { stored.values[MAP_LAYER] == "terrain" }
        assertEquals(true, stored.values[MAP_TRAFFIC])
    }

    private fun <T> onUi(block: () -> T): T = runBlocking(uiDispatcher) { block() }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Timed out", condition())
    }

    private class FakePreferences(initial: Map<String, Any>) : SharedPreferences {
        val values = ConcurrentHashMap<String, Any>(initial)
        val readThreads: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            readThreads += Thread.currentThread().name
            return values[key] as Boolean? ?: defValue
        }
        override fun getString(key: String, defValue: String?): String? {
            readThreads += Thread.currentThread().name
            return values[key] as String? ?: defValue
        }
        override fun contains(key: String) = values.containsKey(key)
        override fun getAll(): Map<String, *> = values
        override fun getStringSet(key: String, defValues: Set<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any>()
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun putString(key: String, value: String?) = apply { if (value != null) changes[key] = value }
            override fun apply() { values.putAll(changes) }
            override fun commit(): Boolean { apply(); return true }
            override fun putStringSet(key: String, values: Set<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun remove(key: String) = this
            override fun clear() = this
        }
    }

    private companion object {
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val TRIP_ACCESS_EXPLAINED = "trip_access_explained"
        const val MAP_LAYER = "map_layer"
        const val MAP_TRAFFIC = "map_traffic"
    }
}
