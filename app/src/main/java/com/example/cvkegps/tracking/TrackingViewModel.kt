package com.example.cvkegps.tracking

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cvkegps.location.GpsEvent
import com.example.cvkegps.location.GpsReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TripDetail(val trip: TripRecord, val positions: Int, val segments: Int, val summary: RouteSummary)

/** A read of saved trips: not answered yet, the stored value, or a failure that the user can try again. */
sealed interface StoredRead<out T> {
    data object Loading : StoredRead<Nothing>
    data class Ready<T>(val value: T) : StoredRead<T>
    data object Failed : StoredRead<Nothing>
}

/** The value of a completed read. Null while the read runs or after it failed. */
val <T> StoredRead<T>.ready: T? get() = (this as? StoredRead.Ready)?.value

/**
 * A storage error ends the read and shows as [StoredRead.Failed]. Cancellation is not an error.
 * The database is never deleted or made again to recover.
 */
private fun <T> Flow<T>.asStoredRead(): Flow<StoredRead<T>> = map<T, StoredRead<T>> { StoredRead.Ready(it) }
    .catch { error -> if (error is CancellationException || error !is Exception) throw error else emit(StoredRead.Failed) }

/** [exports] is a private cache folder for shared GPX files. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingViewModel(
    private val dao: TripDao,
    val recorder: TripRecorder,
    source: SharedLocationSource,
    private val exports: File,
) : ViewModel() {
    // Each retry starts new reads of the list and the selected trip.
    private val readAttempt = MutableStateFlow(0)
    // Loading until Room answers, so the list never claims that there are no trips before it knows.
    val trips = readAttempt.flatMapLatest { dao.trips().asStoredRead() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), StoredRead.Loading)
    private val selection = MutableStateFlow<String?>(null)
    val selectedId = selection.asStateFlow()
    private val selectedMode = MutableStateFlow<TripMode?>(null)
    val mode = selectedMode.asStateFlow()
    val estimate = source.activityEstimate.transformLatest { estimate ->
        val now = SystemClock.elapsedRealtimeNanos()
        emit(estimate.fresh(now))
        val remaining = 10_000_000_000L - (now - estimate.elapsedNanos)
        if (remaining in 1..10_000_000_000L) {
            delay((remaining + 999_999) / 1_000_000)
            emit(TripMode.Unknown)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), TripMode.Unknown)
    private val mutationError = MutableStateFlow(false)
    val storageError = mutationError.asStateFlow()

    /**
     * Room invalidates whole tables, so a recording elsewhere re-runs the trip query once for each fix.
     * Each save also changes the saved trip's row, so the route is read again only when this trip's row changes.
     */
    private fun detailOf(id: String) = dao.watchTrip(id).distinctUntilChanged().mapLatest<TripRecord?, TripDetail?> { trip ->
        trip ?: return@mapLatest null
        val segments = dao.segments(id)
        val points = dao.savedPoints(id)
        withContext(Dispatchers.Default) {
            val grouped = points.groupBy { it.segmentId }
            TripDetail(trip, points.size, segments.size, RouteMath.summarize(segments.map { grouped[it.id].orEmpty().map(TripPoint::sample) }))
        }
    }

    val detail = readAttempt.flatMapLatest { selection }.flatMapLatest { id ->
        if (id == null) flowOf(StoredRead.Ready(null)) else detailOf(id).asStoredRead()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), StoredRead.Loading)

    /** The recording trip's route. The recorder keeps it while this flow has a collector. */
    val activeRoute = recorder.route

    val reading = recorder.state.map { it.trip?.status == TripStatus.Recording }.distinctUntilChanged()
        .flatMapLatest { active -> if (active) source.updates() else flowOf(GpsEvent.Paused) }
        .transformLatest { event ->
            val fix = (event as? GpsEvent.Fix)?.reading
            val age = fix?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
            if (fix != null && age != null && age in 0 until RouteMath.SIGNAL_GAP_NANOS) {
                emit(fix)
                delay((RouteMath.SIGNAL_GAP_NANOS - age + 999_999) / 1_000_000)
            }
            emit(null)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), null as GpsReading?)

    /** Call before the trip screen opens, so the route loads during the screen transition. */
    fun select(id: String?) { selection.value = id }
    fun selectMode(mode: TripMode?) { selectedMode.value = mode }
    /** Reads the saved trips again after a failed read. */
    fun retryReads() { readAttempt.value++ }
    fun correctMode(id: String, mode: TripMode?) = recorder.send(
        RecordingCommand.SetMode(id, mode ?: TripMode.Unknown, mode == null))
    fun finishInterrupted(id: String) = recorder.send(RecordingCommand.Finish(id))
    /** A failed delete shows an error until the next delete, or until the screen that showed it closes. */
    fun clearStorageError() { mutationError.value = false }
    fun delete(id: String) = viewModelScope.launch {
        mutationError.value = false
        try { dao.delete(id); selection.value = null; withContext(Dispatchers.IO) { clearExports() } }
        catch (_: Exception) { mutationError.value = true }
    }

    /**
     * Writes the trip as GPX into the private export folder and returns the file, or null on failure.
     * A failure can come from the database read or from the file write. Earlier exports are removed first,
     * so the private cache keeps only one route.
     */
    suspend fun exportGpx(id: String, name: String): File? = withContext(Dispatchers.IO) {
        try {
            val trip = dao.trip(id) ?: return@withContext null
            val lines = RouteMath.summarize(dao.route(id)).lines
            clearExports()
            if (!exports.mkdirs() && !exports.isDirectory) return@withContext null
            File(exports, Gpx.fileName(trip.startedMillis)).also { file -> file.bufferedWriter().use { Gpx.write(it, name, lines) } }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    }

    private fun clearExports() { exports.listFiles()?.forEach { it.delete() } }

    // The newest replay. A rotation or a return to the replay screen does not calculate it again.
    private var replayCache: Pair<String, TripReplay?>? = null

    /**
     * Reads a saved trip with its sensor record and calculates its replay off the main thread.
     * Null for a trip that does not exist. A storage error shows as [StoredRead.Failed].
     */
    fun replay(id: String): Flow<StoredRead<TripReplay?>> = flow {
        replayCache?.takeIf { it.first == id }?.let { emit(it.second); return@flow }
        val trip = dao.trip(id)
        val result = if (trip == null) null else {
            val segments = dao.segments(id)
            val points = dao.savedPoints(id)
            val chunks = dao.sensorChunks(id)
            withContext(Dispatchers.Default) {
                val samples = points.groupBy { it.segmentId }
                val sensors = chunks.groupBy { it.segmentId }
                ReplayAnalysis.analyze(segments.map { segment ->
                    ReplaySegment(segment.startedElapsedNanos, segment.endedElapsedNanos, samples[segment.id].orEmpty().map(TripPoint::sample),
                        sensors[segment.id].orEmpty().mapNotNull(TripSensorChunk::chunk))
                }, gpsHeightOnly = trip.mode == TripMode.Plane)
            }
        }
        replayCache = id to result
        emit(result)
    }.asStoredRead()
}
