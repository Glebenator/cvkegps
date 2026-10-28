package com.example.cvkegps.tracking

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.example.cvkegps.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TrackingApplication : Application() {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database by lazy {
        Room.databaseBuilder(this, TripDatabase::class.java, "trips.db")
            .addMigrations(TripDatabase.MIGRATION_1_2, TripDatabase.MIGRATION_2_3).build()
    }
    val location by lazy { SharedLocationSource(this, processScope) }
    val recorder by lazy { TripRecorder(database.trips(), location, processScope) }
    val settings by lazy { AppSettings(this, processScope) }

    override fun onCreate() {
        super.onCreate()
        // The recorder marks unfinished trips as interrupted through Room's IO executor.
        recorder
        settings
        // GPS stays on while the app is visible, so a change of screen or a rotation does not restart it.
        // The process lifecycle waits briefly before it stops, so an activity that is recreated keeps the request.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = location.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = location.setForeground(false)
        })
    }
}
