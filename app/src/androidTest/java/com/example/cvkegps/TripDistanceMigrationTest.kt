package com.example.cvkegps

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cvkegps.tracking.TripDatabase
import com.example.cvkegps.tracking.TripDistances
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrades a version 2 trip database and fills in the distance of trips saved before version 3. All positions are simulated. */
@RunWith(AndroidJUnit4::class)
class TripDistanceMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @After fun deleteDatabase() {
        context.deleteDatabase(name)
    }

    @Test fun versionTwoTripsGetTheirRouteDistance() {
        context.deleteDatabase(name)
        // The version 2 schema, as Room created it.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE `trips` (`id` TEXT NOT NULL, `mode` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`startedMillis` INTEGER NOT NULL, `endedMillis` INTEGER, `durationNanos` INTEGER NOT NULL, " +
                "`lastSavedMillis` INTEGER NOT NULL, `policyVersion` INTEGER NOT NULL, `automaticMode` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE `segments` (`id` TEXT NOT NULL, `tripId` TEXT NOT NULL, `runId` TEXT NOT NULL, " +
                "`sequence` INTEGER NOT NULL, `startedElapsedNanos` INTEGER NOT NULL, `startedMillis` INTEGER NOT NULL, " +
                "`endedElapsedNanos` INTEGER, `reason` TEXT NOT NULL, `endReason` TEXT, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX `index_segments_tripId` ON `segments` (`tripId`)")
            db.execSQL("CREATE TABLE `points` (`tripId` TEXT NOT NULL, `segmentId` TEXT NOT NULL, `latitudeRadians` REAL NOT NULL, " +
                "`longitudeRadians` REAL NOT NULL, `timeMillis` INTEGER NOT NULL, `elapsedNanos` INTEGER NOT NULL, " +
                "`receivedElapsedNanos` INTEGER NOT NULL, `accuracyMeters` REAL, `speedMetersPerSecond` REAL, " +
                "`speedAccuracyMetersPerSecond` REAL, `altitudeMeters` REAL, `verticalAccuracyMeters` REAL, `bearingRadians` REAL, " +
                "`bearingAccuracyRadians` REAL, `isMock` INTEGER NOT NULL, PRIMARY KEY(`segmentId`, `elapsedNanos`), " +
                "FOREIGN KEY(`segmentId`) REFERENCES `segments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX `index_points_tripId` ON `points` (`tripId`)")
            for (trip in listOf("route", "empty")) {
                db.execSQL("INSERT INTO trips VALUES ('$trip', 'Bike', 'Finished', 1000, 61000, 60000000000, 61000, 1, 0)")
                db.execSQL("INSERT INTO segments VALUES ('$trip-1', '$trip', 'run', 0, 1, 1000, 60000000000, 'Start', 'Stop')")
            }
            // Three points 0.001 radians of longitude apart on the equator, at 10 m/s: about 12.7 km.
            for (i in 0..2) {
                val nanos = (i + 1) * 1_000_000_000L
                db.execSQL("INSERT INTO points VALUES ('route', 'route-1', 0.0, ${i * 0.001}, ${1000 + i * 1000}, $nanos, $nanos, " +
                    "3.0, 10.0, 0.2, NULL, NULL, NULL, NULL, 1)")
            }
            db.version = 2
        }
        val database = Room.databaseBuilder(context, TripDatabase::class.java, name)
            .addMigrations(TripDatabase.MIGRATION_1_2, TripDatabase.MIGRATION_2_3).build()
        try {
            val dao = database.trips()
            runBlocking {
                assertNull(dao.trip("route")!!.distanceMeters)
                TripDistances.fillMissing(dao)
                assertEquals(2 * 6_371_008.8 * 0.001, dao.trip("route")!!.distanceMeters!!, 0.5)
                // A trip without positions has a known distance of zero.
                assertEquals(0.0, dao.trip("empty")!!.distanceMeters!!, 0.0)
            }
        } finally {
            database.close()
        }
    }
}
