package com.example.cvkegps.tracking

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cvkegps.location.GpsEvent
import com.example.cvkegps.location.GpsReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class TripDetail(val trip: TripRecord, val positions: Int, val segments: Int, val summary: RouteSummary)

/** [exports] is a private cache folder for shared GPX files. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingViewModel(
    private val dao: TripDao,
    val recorder: TripRecorder,
    source: SharedLocationSource,
    private val exports: File,
) : ViewModel() {
    // Null until Room answers, so the list never claims that there are no trips before it knows.
    val trips = dao.trips().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null as List<TripRecord>?)
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
    private fun detailOf(id: String) = dao.watchTrip(id).distinctUntilChanged().mapLatest { trip ->
        trip ?: return@mapLatest null
        val segments = dao.segments(id)
        val points = dao.savedPoints(id)
        withContext(Dispatchers.Default) {
            val grouped = points.groupBy { it.segmentId }
            TripDetail(trip, points.size, segments.size, RouteMath.summarize(segments.map { grouped[it.id].orEmpty().map(TripPoint::sample) }))
        }
    }

    val detail = selection.flatMapLatest { id -> if (id == null) flowOf(null) else detailOf(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)

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
     * Earlier exports are removed first, so the private cache keeps only one route.
     */
    suspend fun exportGpx(id: String, name: String): File? = withContext(Dispatchers.IO) {
        try {
            val trip = dao.trip(id) ?: return@withContext null
            val lines = RouteMath.summarize(dao.route(id)).lines
            clearExports()
            if (!exports.mkdirs() && !exports.isDirectory) return@withContext null
            File(exports, Gpx.fileName(trip.startedMillis)).also { file -> file.bufferedWriter().use { Gpx.write(it, name, lines) } }
        } catch (_: IOException) { null }
    }

    private fun clearExports() { exports.listFiles()?.forEach { it.delete() } }
}
