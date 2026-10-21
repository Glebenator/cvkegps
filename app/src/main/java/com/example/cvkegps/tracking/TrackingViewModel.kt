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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TripDetail(val trip: TripRecord, val positions: Int, val segments: Int, val summary: RouteSummary)

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingViewModel(private val dao: TripDao, val recorder: TripRecorder, source: SharedLocationSource) : ViewModel() {
    val trips = dao.trips().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    private val selection = MutableStateFlow<String?>(null)
    val selectedId = selection.asStateFlow()
    private val selectedMode = MutableStateFlow(TripMode.Car)
    val mode = selectedMode.asStateFlow()
    private val mutationError = MutableStateFlow(false)
    val storageError = mutationError.asStateFlow()

    val detail = selection.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else combine(dao.trips(), dao.points(id)) { rows, points -> rows.find { it.id == id } to points }
            .mapLatest { (trip, points) ->
                if (trip == null) null else {
                    val segments = dao.segments(id)
                    withContext(Dispatchers.Default) {
                        val grouped = points.groupBy { it.segmentId }
                        TripDetail(trip, points.size, segments.size,
                            RouteMath.summarize(segments.map { grouped[it.id].orEmpty().map(TripPoint::sample) }))
                    }
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)

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

    fun select(id: String?) { selection.value = id }
    fun selectMode(mode: TripMode) { selectedMode.value = mode }
    fun finishInterrupted(id: String) = recorder.send(RecordingCommand.Finish(id))
    fun delete(id: String) = viewModelScope.launch {
        try { dao.delete(id); selection.value = null }
        catch (_: Exception) { mutationError.value = true }
    }
}
