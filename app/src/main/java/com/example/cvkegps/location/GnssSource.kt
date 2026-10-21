package com.example.cvkegps.location

import kotlinx.coroutines.flow.Flow

enum class SatelliteSystem { Gps, Sbas, Glonass, Qzss, Beidou, Galileo, Irnss, Unknown }
enum class ReceiverEvent { Unknown, Started, Stopped }
enum class GnssObservation { Inactive, Waiting, Unavailable }

data class SatelliteSignal(
    val system: SatelliteSystem,
    val id: Int,
    val cn0DbHz: Float,
    val elevationRadians: Double,
    val azimuthRadians: Double,
    val carrierFrequencyHz: Float?,
    val usedInFix: Boolean,
)

/** Null signals mean no report. An empty list means Android reported no signals. */
data class GnssSnapshot(
    val observation: GnssObservation = GnssObservation.Inactive,
    val receiverEvent: ReceiverEvent = ReceiverEvent.Unknown,
    val firstFixSeconds: Double? = null,
    val signals: List<SatelliteSignal>? = null,
    val reportedAtNanos: Long? = null,
)

interface GnssSource {
    /** Observe status only. The caller must own a separate location request. */
    fun updates(): Flow<GnssSnapshot>
}
