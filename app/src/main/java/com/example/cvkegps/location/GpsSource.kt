package com.example.cvkegps.location

import kotlinx.coroutines.flow.Flow

/** Measurements use SI units. A missing measurement is not a zero measurement. */
data class GpsReading(
    val speedMetersPerSecond: Float?,
    val altitudeMeters: Double?,
    val accuracyMeters: Float?,
    val elapsedRealtimeNanos: Long,
)

sealed interface GpsEvent {
    data object PermissionRequired : GpsEvent
    data object Disabled : GpsEvent
    data object Unavailable : GpsEvent
    data object Searching : GpsEvent
    data object Error : GpsEvent
    data class Fix(val reading: GpsReading) : GpsEvent
}

interface GpsSource {
    /** Each collector owns one request. Cancellation must release that request. */
    fun updates(): Flow<GpsEvent>
}
