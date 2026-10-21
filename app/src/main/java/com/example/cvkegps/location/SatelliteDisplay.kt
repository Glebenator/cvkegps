package com.example.cvkegps.location

import kotlin.math.ln

/** One marker per satellite, even when the receiver reports several frequency bands. */
fun List<SatelliteSignal>.distinctSatellites(): List<SatelliteSignal> =
    groupBy { it.system to it.id }.values.map { bands ->
        val strongest = bands.maxBy { it.cn0DbHz.takeIf(Float::isFinite) ?: -1f }
        strongest.copy(usedInFix = bands.any { it.usedInFix })
    }.sortedWith(compareBy({ it.system.ordinal }, { it.id }))

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
