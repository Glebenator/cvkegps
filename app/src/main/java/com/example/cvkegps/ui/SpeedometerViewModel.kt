package com.example.cvkegps.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cvkegps.location.GpsEvent
import com.example.cvkegps.location.GpsReading
import com.example.cvkegps.location.GpsSource
import com.example.cvkegps.location.GnssSource
import com.example.cvkegps.location.GnssSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

enum class GpsStatus {
    PermissionRequired, Disabled, Unavailable, Searching, Live, Stale, Paused, Error,
}

data class SpeedometerUiState(
    val status: GpsStatus = GpsStatus.Searching,
    val reading: GpsReading? = null,
    val maximumSpeedMetersPerSecond: Float? = null,
    val receivedFixes: Long = 0,
    val lastFixElapsedNanos: Long? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedometerViewModel(source: GpsSource, gnssSource: GnssSource) : ViewModel() {
    private val paused = MutableStateFlow(false)
    private val refresh = MutableStateFlow(0)
    private var maximumSpeed: Float? = null

    val uiState = combine(paused, refresh) { isPaused, _ -> isPaused }
        .flatMapLatest { isPaused ->
            if (isPaused) {
                flowOf(SpeedometerUiState(GpsStatus.Paused, maximumSpeedMetersPerSecond = maximumSpeed))
            } else {
                source.updates().transformLatest { event ->
                    val status = when (event) {
                        GpsEvent.PermissionRequired -> GpsStatus.PermissionRequired
                        GpsEvent.Disabled -> GpsStatus.Disabled
                        GpsEvent.Unavailable -> GpsStatus.Unavailable
                        GpsEvent.Searching -> GpsStatus.Searching
                        GpsEvent.Paused -> GpsStatus.Paused
                        GpsEvent.Error -> GpsStatus.Error
                        is GpsEvent.Fix -> GpsStatus.Live
                    }
                    if (event is GpsEvent.Fix) {
                        val age = SystemClock.elapsedRealtimeNanos() - event.reading.elapsedRealtimeNanos
                        if (age < 0 || age >= FRESHNESS_NANOS) {
                            emit(SpeedometerUiState(GpsStatus.Stale, maximumSpeedMetersPerSecond = maximumSpeed,
                                receivedFixes = event.receivedCount, lastFixElapsedNanos = event.reading.elapsedRealtimeNanos))
                            return@transformLatest
                        }
                        event.reading.speedMetersPerSecond?.let { speed ->
                            maximumSpeed = maxOf(maximumSpeed ?: speed, speed)
                        }
                        emit(SpeedometerUiState(GpsStatus.Live, event.reading, maximumSpeed,
                            event.receivedCount, event.reading.elapsedRealtimeNanos))
                        // One cancellable deadline per fix; no continuous timer or GPS polling.
                        delay((FRESHNESS_NANOS - age) / 1_000_000)
                        emit(SpeedometerUiState(GpsStatus.Stale, maximumSpeedMetersPerSecond = maximumSpeed,
                            receivedFixes = event.receivedCount, lastFixElapsedNanos = event.reading.elapsedRealtimeNanos))
                    } else {
                        emit(SpeedometerUiState(status, maximumSpeedMetersPerSecond = maximumSpeed))
                    }
                }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
            SpeedometerUiState(),
        )

    // Home and details share one callback. It does not create another location request.
    val gnssState = uiState.map {
        it.status == GpsStatus.Searching || it.status == GpsStatus.Live || it.status == GpsStatus.Stale
    }
        .distinctUntilChanged()
        .flatMapLatest { active -> if (active) gnssSource.updates() else flowOf(GnssSnapshot()) }
        .transformLatest { snapshot ->
            val age = snapshot.reportedAtNanos?.let { SystemClock.elapsedRealtimeNanos() - it }
            if (snapshot.signals == null || age == null || age < 0 || age >= FRESHNESS_NANOS) {
                emit(snapshot.copy(signals = null))
            } else {
                emit(snapshot)
                // Remove old markers and counts even when Android sends no further callback.
                delay((FRESHNESS_NANOS - age + 999_999) / 1_000_000)
                emit(snapshot.copy(signals = null))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), GnssSnapshot())

    fun refreshAccess() = refresh.update { it + 1 }

    fun setPaused(value: Boolean) {
        paused.value = value
    }

    private companion object {
        const val FRESHNESS_NANOS = 10_000_000_000L
    }
}
