package com.example.cvkegps.tracking

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TrackingApplication : Application() {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database by lazy { Room.databaseBuilder(this, TripDatabase::class.java, "trips.db").build() }
    val location by lazy { SharedLocationSource(this) }
    val recorder by lazy { TripRecorder(database.trips(), location, processScope) }

    override fun onCreate() {
        super.onCreate()
        // The recorder marks unfinished trips as interrupted through Room's IO executor.
        recorder
    }
}
