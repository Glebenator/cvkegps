package com.example.cvkegps.tracking

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.example.cvkegps.location.TrackSample
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trips")
data class TripRecord(
    @PrimaryKey val id: String,
    val mode: TripMode,
    val status: TripStatus,
    val startedMillis: Long,
    val endedMillis: Long? = null,
    val durationNanos: Long = 0,
    val lastSavedMillis: Long = startedMillis,
    val policyVersion: Int = 1,
    val automaticMode: Boolean = false,
    /** Null until it is known. The recorder calculates it again from the saved route when it resumes a trip without one. */
    val distanceMeters: Double? = null,
)

@Entity(tableName = "segments", foreignKeys = [ForeignKey(
    entity = TripRecord::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("tripId")])
data class TripSegment(
    @PrimaryKey val id: String,
    val tripId: String,
    val runId: String,
    val sequence: Int,
    val startedElapsedNanos: Long,
    val startedMillis: Long,
    val endedElapsedNanos: Long? = null,
    val reason: BreakReason,
    val endReason: BreakReason? = null,
    /** The recording choices of this segment. See RecordingOptions. */
    val highAccuracy: Boolean = false,
    val networkFallback: Boolean = false,
)

@Entity(tableName = "points", primaryKeys = ["segmentId", "elapsedNanos"], foreignKeys = [ForeignKey(
    entity = TripSegment::class, parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("tripId")])
data class TripPoint(
    val tripId: String,
    val segmentId: String,
    @Embedded val sample: TrackSample,
)

/** Readings of one phone sensor in one segment, as [SensorChunk.encode] stores them. */
@Entity(tableName = "sensor_chunks", primaryKeys = ["segmentId", "kind", "firstElapsedNanos"], foreignKeys = [ForeignKey(
    entity = TripSegment::class, parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("tripId")])
class TripSensorChunk(
    val tripId: String,
    val segmentId: String,
    val kind: SensorKind,
    val firstElapsedNanos: Long,
    val lastElapsedNanos: Long,
    val count: Int,
    val data: ByteArray,
) {
    fun chunk(): SensorChunk? = SensorChunk.decode(kind, data)
}

@Dao
abstract class TripDao {
    @Query("SELECT * FROM trips ORDER BY startedMillis DESC")
    abstract fun trips(): Flow<List<TripRecord>>

    @Query("SELECT * FROM trips WHERE id = :id")
    abstract suspend fun trip(id: String): TripRecord?

    @Query("SELECT * FROM trips WHERE id = :id")
    abstract fun watchTrip(id: String): Flow<TripRecord?>

    @Query("SELECT * FROM segments WHERE tripId = :id ORDER BY sequence")
    abstract suspend fun segments(id: String): List<TripSegment>

    @Query("SELECT * FROM points WHERE tripId = :id ORDER BY elapsedNanos")
    abstract suspend fun savedPoints(id: String): List<TripPoint>

    @Query("SELECT * FROM sensor_chunks WHERE tripId = :id ORDER BY kind, firstElapsedNanos")
    abstract suspend fun sensorChunks(id: String): List<TripSensorChunk>

    @Insert abstract suspend fun insert(trip: TripRecord)
    @Insert abstract suspend fun insert(segment: TripSegment)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insert(points: List<TripPoint>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertSensors(chunks: List<TripSensorChunk>)
    @Update abstract suspend fun update(trip: TripRecord)
    @Update abstract suspend fun update(segment: TripSegment)

    @Query("DELETE FROM trips WHERE id = :id AND status NOT IN ('Recording', 'Paused')")
    abstract suspend fun delete(id: String)

    @Query("UPDATE trips SET status = 'Interrupted' WHERE status IN ('Recording', 'Paused')")
    abstract suspend fun markInterrupted()

    /** The saved samples of each segment, in segment order. */
    @Transaction
    open suspend fun route(id: String): List<List<TrackSample>> {
        val grouped = savedPoints(id).groupBy { it.segmentId }
        return segments(id).map { grouped[it.id].orEmpty().map(TripPoint::sample) }
    }

    @Transaction
    open suspend fun start(trip: TripRecord, segment: TripSegment) {
        insert(trip)
        insert(segment)
    }

    @Transaction
    open suspend fun save(trip: TripRecord, points: List<TripPoint>) {
        insert(points)
        update(trip)
    }

    @Transaction
    open suspend fun closeSegment(trip: TripRecord, segment: TripSegment) {
        update(segment)
        update(trip)
    }

    @Transaction
    open suspend fun resume(trip: TripRecord, segment: TripSegment) {
        insert(segment)
        update(trip)
    }
}

/**
 * The app is not published, so a schema change deletes the saved trips instead of a migration.
 * Add migrations before the first release.
 */
@Database(entities = [TripRecord::class, TripSegment::class, TripPoint::class, TripSensorChunk::class], version = 4, exportSchema = false)
abstract class TripDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
}
