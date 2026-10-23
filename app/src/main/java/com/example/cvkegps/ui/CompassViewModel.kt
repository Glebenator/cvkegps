package com.example.cvkegps.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cvkegps.location.HeadingSource
import com.example.cvkegps.location.angularDistance
import com.example.cvkegps.location.trueHeading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/** Direction of the screen's upper edge for the satellite displays. */
sealed interface PhoneHeading {
    /** The compass listener has not reported yet. */
    data object Starting : PhoneHeading
    data object Unavailable : PhoneHeading
    /** Compass data is current, but no GPS position has supplied the true-north correction. */
    data object WaitingForPosition : PhoneHeading
    /** Clockwise from true north. */
    data class Current(val trueRadians: Double) : PhoneHeading
}

@OptIn(ExperimentalCoroutinesApi::class)
class CompassViewModel(source: HeadingSource) : ViewModel() {
    private val rotation = MutableStateFlow(0)
    private val declination = MutableStateFlow<Double?>(null)

    private val magnetic = rotation.flatMapLatest { displayRotation ->
        var reported = false
        source.updates(displayRotation).transformLatest { value ->
            val age = value?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
            if (value == null || !value.magneticAzimuthRadians.isFinite() ||
                age == null || age < 0 || age >= MAX_AGE_NANOS
            ) {
                // A new listener has no sample yet. Report a fault only if no sample arrives soon.
                if (!reported) delay(STARTUP_MILLIS)
                reported = true
                emit(null)
            } else {
                reported = true
                emit(value.magneticAzimuthRadians)
                delay((MAX_AGE_NANOS - age + 999_999) / 1_000_000)
                emit(null)
            }
        }
    }

    val heading: StateFlow<PhoneHeading> = combine(magnetic, declination) { magnetic, declination ->
        when {
            magnetic == null -> PhoneHeading.Unavailable
            declination == null -> PhoneHeading.WaitingForPosition
            else -> trueHeading(magnetic, declination)?.let(PhoneHeading::Current) ?: PhoneHeading.Unavailable
        }
    }.distinctUntilChanged { old, new ->
        // Changes smaller than one degree do not redraw the satellite displays.
        old == new || old is PhoneHeading.Current && new is PhoneHeading.Current &&
            angularDistance(old.trueRadians, new.trueRadians) < STEP_RADIANS
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), PhoneHeading.Starting)

    fun setDisplayRotation(value: Int) { rotation.value = value }

    /** Keep the last correction while GPS searches. It changes slowly with position and is not stored. */
    fun setDeclination(radians: Double) {
        if (radians.isFinite()) declination.value = radians
    }

    private companion object {
        const val MAX_AGE_NANOS = 2_000_000_000L
        const val STARTUP_MILLIS = 1_000L
        val STEP_RADIANS = Math.toRadians(1.0)
    }
}
