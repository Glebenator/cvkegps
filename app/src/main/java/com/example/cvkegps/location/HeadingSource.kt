package com.example.cvkegps.location

import kotlinx.coroutines.flow.Flow

/** Direction of the screen's upper edge, measured clockwise from magnetic north. */
data class HeadingReading(val magneticAzimuthRadians: Double, val elapsedRealtimeNanos: Long)

interface HeadingSource {
    /** Rotation is the number of quarter turns from the display's natural orientation. */
    fun updates(displayRotation: Int): Flow<HeadingReading?>
}
