package com.example.cvkegps.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cvkegps.location.GpsEvent
import com.example.cvkegps.location.GpsReading
import com.example.cvkegps.location.GpsSource
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

enum class GpsStatus {
    PermissionRequired, Disabled, Unavailable, Searching, Live, Stale, Paused, Error,
}

data class SpeedometerUiState(
    val status: GpsStatus = GpsStatus.Searching,
    val reading: GpsReading? = null,
    val maximumSpeedMetersPerSecond: Float? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedometerViewModel(source: GpsSource) : ViewModel() {
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
                        GpsEvent.Error -> GpsStatus.Error
                        is GpsEvent.Fix -> GpsStatus.Live
                    }
                    if (event is GpsEvent.Fix) {
                        val age = SystemClock.elapsedRealtimeNanos() - event.reading.elapsedRealtimeNanos
                        if (age < 0 || age >= FRESHNESS_NANOS) {
                            emit(SpeedometerUiState(GpsStatus.Stale, maximumSpeedMetersPerSecond = maximumSpeed))
                            return@transformLatest
                        }
                        event.reading.speedMetersPerSecond?.let { speed ->
                            maximumSpeed = maxOf(maximumSpeed ?: speed, speed)
                        }
                        emit(SpeedometerUiState(GpsStatus.Live, event.reading, maximumSpeed))
                        // One cancellable deadline per fix; no continuous timer or GPS polling.
                        delay((FRESHNESS_NANOS - age) / 1_000_000)
                        emit(SpeedometerUiState(GpsStatus.Stale, maximumSpeedMetersPerSecond = maximumSpeed))
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

    fun refreshAccess() = refresh.update { it + 1 }

    fun setPaused(value: Boolean) {
        paused.value = value
    }

    private companion object {
        const val FRESHNESS_NANOS = 10_000_000_000L
    }
}
