package com.example.cvkegps.tracking

import com.example.cvkegps.location.TrackSample
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class TripMode { Car, Transit, Plane, Bike, Ski, Unknown, CarOrTransit, BikeOrSki }
enum class TripStatus { Recording, Paused, Interrupted, Finished }
enum class BreakReason { Start, Resume, ManualPause, Stop, SignalLoss, AccessLost, ProcessLost, StorageError }

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
            val track = SegmentTrack()
            var line = mutableListOf<TrackSample>()
            for (sample in segment.filter { it.valid() }.distinctBy { it.elapsedNanos }.sortedBy { it.elapsedNanos }) {
                sample.usableSpeed?.let { maximum = maxOf(maximum ?: it, it) }
                val step = track.add(sample)
                if (step.gapNanos > 0) {
                    gaps++
                    missingNanos += step.gapNanos
                }
                if (step.poorGap) gaps++
                if (step.gapNanos > 0 || !step.joined) {
                    if (line.isNotEmpty()) lines += line
                    line = mutableListOf()
                }
                if (step.joined) line += sample
            }
            if (line.isNotEmpty()) lines += line
            total += track.distanceMeters
        }
        return RouteSummary(total, maximum, gaps, missingNanos, lines)
    }
}

/**
 * One recording segment, sample by sample in time order. Trip summaries and the recorder use the same rules,
 * so the distance on a trip card agrees with the trip details.
 */
class SegmentTrack {
    /** One sample's effect: the signal gap before it, the start of a poor-position gap, and whether it joins the line. */
    data class Step(val gapNanos: Long, val poorGap: Boolean, val joined: Boolean)

    var distanceMeters = 0.0
        private set
    var lastElapsedNanos: Long? = null
        private set
    private var previous: TrackSample? = null
    private var poorFix = false

    fun add(sample: TrackSample): Step {
        val gapNanos = lastElapsedNanos?.let { sample.elapsedNanos - it }?.takeIf { it >= RouteMath.SIGNAL_GAP_NANOS } ?: 0
        lastElapsedNanos = sample.elapsedNanos
        if (gapNanos > 0) previous = null
        if (!sample.usablePosition) {
            val poorGap = !poorFix
            poorFix = true
            previous = null
            return Step(gapNanos, poorGap, joined = false)
        }
        poorFix = false
        previous?.let { last ->
            // Do not accumulate position noise while reported speed is zero.
            if (sample.usableSpeed != 0f || last.usableSpeed != 0f) distanceMeters += RouteMath.distance(last, sample)
        }
        previous = sample
        return Step(gapNanos, poorGap = false, joined = true)
    }
}


/** Distance divided by recording time. Recording time includes stops, because the app does not measure moving time. */
fun averageSpeed(distanceMeters: Double?, durationNanos: Long): Float? =
    distanceMeters?.takeIf { durationNanos >= 1_000_000_000L }?.let { (it / (durationNanos / 1e9)).toFloat() }
