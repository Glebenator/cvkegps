package com.example.cvkegps.location

import kotlin.math.ln

/** One marker per satellite, even when the receiver reports several frequency bands. */
fun List<SatelliteSignal>.distinctSatellites(): List<SatelliteSignal> =
    groupBy { it.system to it.id }.values.map { bands ->
        val strongest = bands.maxBy { it.cn0DbHz.takeIf(Float::isFinite) ?: -1f }
        strongest.copy(usedInFix = bands.any { it.usedInFix })
    }.sortedWith(compareBy({ it.system.ordinal }, { it.id }))

const val SIGNAL_WEAK_DBHZ = 15f
const val SIGNAL_STRONG_DBHZ = 45f

/** A linear display scale for C/N0: 0 at 15 dB-Hz or less, 1 at 45 dB-Hz or more. Missing values are 0. */
fun signalStrength(cn0DbHz: Float): Float =
    if (!cn0DbHz.isFinite()) 0f
    else ((cn0DbHz - SIGNAL_WEAK_DBHZ) / (SIGNAL_STRONG_DBHZ - SIGNAL_WEAK_DBHZ)).coerceIn(0f, 1f)

const val POSITION_ACCURACY_EMPTY_METERS = 50f
const val POSITION_ACCURACY_REFERENCE_METERS = 5f
const val POSITION_ACCURACY_FULL_METERS = 1f

/** A logarithmic display scale from 50 m to 1 m. These are not receiver limits. */
fun positionAccuracyProgress(accuracyMeters: Float?): Float? = accuracyMeters
    ?.takeIf { it.isFinite() && it >= 0f }
    ?.let {
        val bounded = it.coerceIn(POSITION_ACCURACY_FULL_METERS, POSITION_ACCURACY_EMPTY_METERS)
        ln(POSITION_ACCURACY_EMPTY_METERS / bounded) /
            ln(POSITION_ACCURACY_EMPTY_METERS / POSITION_ACCURACY_FULL_METERS)
    }
