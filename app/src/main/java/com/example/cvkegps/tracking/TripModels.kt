package com.example.cvkegps.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class TripMode { Car, Transit, Plane, Bike, Ski }
enum class TripStatus { Recording, Paused, Interrupted, Finished }
enum class BreakReason { Start, Resume, ManualPause, Stop, SignalLoss, AccessLost, ProcessLost, StorageError }

/** Linear measurements use SI units. Angular measurements use radians. */
data class TrackSample(
    val latitudeRadians: Double,
    val longitudeRadians: Double,
    val timeMillis: Long,
    val elapsedNanos: Long,
    val receivedElapsedNanos: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val altitudeMeters: Double?,
    val verticalAccuracyMeters: Float?,
    val bearingRadians: Double?,
    val bearingAccuracyRadians: Double?,
    val isMock: Boolean,
) {
    fun valid(): Boolean = latitudeRadians.isFinite() && longitudeRadians.isFinite() &&
        latitudeRadians in -Math.PI / 2..Math.PI / 2 && longitudeRadians in -Math.PI..Math.PI &&
        timeMillis > 0 && elapsedNanos > 0 && elapsedNanos <= receivedElapsedNanos

    val usablePosition: Boolean get() = accuracyMeters?.let { it.isFinite() && it in 0f..50f } == true
    val usableSpeed: Float? get() = speedMetersPerSecond?.takeIf { speed ->
        usablePosition && speed.isFinite() && speed >= 0 &&
            (speedAccuracyMetersPerSecond == null || speedAccuracyMetersPerSecond in 0f..3f)
    }
}

data class RouteSummary(
    val distanceMeters: Double = 0.0,
    val maximumSpeedMetersPerSecond: Float? = null,
    val gapCount: Int = 0,
    val gapNanos: Long = 0,
    val lines: List<List<TrackSample>> = emptyList(),
)

object RouteMath {
    const val SIGNAL_GAP_NANOS = 10_000_000_000L

    fun distance(a: TrackSample, b: TrackSample): Double {
        val lat = sin((b.latitudeRadians - a.latitudeRadians) / 2)
        val lon = sin((b.longitudeRadians - a.longitudeRadians) / 2)
        val h = lat * lat + cos(a.latitudeRadians) * cos(b.latitudeRadians) * lon * lon
        return 2 * 6_371_008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Each supplied segment is a separate recording interval. Never connect two segments. */
    fun summarize(segments: List<List<TrackSample>>): RouteSummary {
        var total = 0.0
        var maximum: Float? = null
        var gaps = 0
        var missingNanos = 0L
        val lines = mutableListOf<List<TrackSample>>()
        for (segment in segments) {
            var line = mutableListOf<TrackSample>()
            var previous: TrackSample? = null
            var previousTime: Long? = null
            var poorFix = false
            for (sample in segment.filter { it.valid() }.distinctBy { it.elapsedNanos }.sortedBy { it.elapsedNanos }) {
                sample.usableSpeed?.let { maximum = maxOf(maximum ?: it, it) }
                val dt = previousTime?.let { sample.elapsedNanos - it }
                if (dt != null && dt >= SIGNAL_GAP_NANOS) {
                    gaps++
                    missingNanos += dt
                    if (line.isNotEmpty()) lines += line
                    line = mutableListOf()
                    previous = null
                }
                previousTime = sample.elapsedNanos
                if (!sample.usablePosition) {
                    if (!poorFix) gaps++
                    poorFix = true
                    if (line.isNotEmpty()) lines += line
                    line = mutableListOf()
                    previous = null
                    continue
                }
                poorFix = false
                previous?.let { last ->
                    // Do not accumulate position noise while reported speed is zero.
                    if (sample.usableSpeed != 0f || last.usableSpeed != 0f) total += distance(last, sample)
                }
                line += sample
                previous = sample
            }
            if (line.isNotEmpty()) lines += line
        }
        return RouteSummary(total, maximum, gaps, missingNanos, lines)
    }
}
