package com.example.cvkegps.location

import kotlinx.coroutines.flow.Flow

/** Measurements use SI units. A missing measurement is not a zero measurement. */
data class GpsReading(
    val speedMetersPerSecond: Float?,
    val altitudeMeters: Double?,
    val accuracyMeters: Float?,
    val elapsedRealtimeNanos: Long,
    val speedAccuracyMetersPerSecond: Float? = null,
    val verticalAccuracyMeters: Float? = null,
    val bearingRadians: Double? = null,
    val bearingAccuracyRadians: Double? = null,
    val isMock: Boolean = false,
    val speedIssue: SpeedIssue? = null,
    val magneticDeclinationRadians: Double? = null,
    /** Height above mean sea level. [altitudeMeters] is the GPS height above the WGS 84 ellipsoid. */
    val seaLevelAltitudeMeters: Double? = null,
)

enum class SpeedIssue { Missing, PositionAccuracy, SpeedAccuracy, Invalid }

sealed interface GpsEvent {
    data object PermissionRequired : GpsEvent
    data object Disabled : GpsEvent
    data object Unavailable : GpsEvent
    data object Searching : GpsEvent
    data object Paused : GpsEvent
    data object Error : GpsEvent
    data class Fix(val reading: GpsReading, val receivedCount: Long = 1) : GpsEvent
}

interface GpsSource {
    /** Collectors observe location events. Cancellation must release their demand for updates. */
    fun updates(): Flow<GpsEvent>
}
