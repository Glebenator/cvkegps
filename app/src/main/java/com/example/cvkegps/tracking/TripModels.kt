package com.example.cvkegps.tracking

import com.example.cvkegps.location.TrackSample
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

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

/** A map camera that shows a whole route: the centre in radians and a Web Mercator zoom level. */
data class RouteFrame(val latitudeRadians: Double, val longitudeRadians: Double, val zoom: Double)

object RouteMath {
    const val SIGNAL_GAP_NANOS = 10_000_000_000L

    /** A position is current for [SIGNAL_GAP_NANOS] after its own fix time. A later fix of another sample does not extend it. */
    fun positionCurrent(fixNanos: Long, nowNanos: Long): Boolean = nowNanos - fixNanos in 0 until SIGNAL_GAP_NANOS

    fun distance(a: TrackSample, b: TrackSample): Double {
        val lat = sin((b.latitudeRadians - a.latitudeRadians) / 2)
        val lon = sin((b.longitudeRadians - a.longitudeRadians) / 2)
        val h = lat * lat + cos(a.latitudeRadians) * cos(b.latitudeRadians) * lon * lon
        return 2 * 6_371_008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * The camera that shows all [points] in a visible map area of [widthDp] by [heightDp]. At zoom 0 the world is 256 dp wide.
     * A route across the date line uses the shorter way round. A short route stops at a street scale, and one point uses a town scale.
     */
    fun frame(points: List<TrackSample>, widthDp: Double, heightDp: Double): RouteFrame? {
        if (points.isEmpty()) return null
        val south = mercator(points.minOf { it.latitudeRadians })
        val north = mercator(points.maxOf { it.latitudeRadians })
        // On the longitude circle, the largest gap between two neighbouring points is the part that the route does not cover.
        val longitudes = points.map { it.longitudeRadians }.sorted()
        var gap = longitudes.first() + 2 * PI - longitudes.last()
        var west = longitudes.first()
        for (index in 1 until longitudes.size) {
            val step = longitudes[index] - longitudes[index - 1]
            if (step > gap) { gap = step; west = longitudes[index] }
        }
        val width = 2 * PI - gap
        val east = west + width
        val longitude = (west + east) / 2
        val latitude = 2 * atan(exp((south + north) / 2)) - PI / 2
        val centre = longitude - 2 * PI * floor((longitude + PI) / (2 * PI))
        if (width <= 0.0 && north <= south) return RouteFrame(latitude, centre, POINT_ZOOM)
        val zoom = min(zoomFor(width, widthDp), zoomFor(north - south, heightDp))
        return RouteFrame(latitude, centre, zoom.coerceIn(MIN_ZOOM, STREET_ZOOM))
    }

    private const val STREET_ZOOM = 17.0
    private const val POINT_ZOOM = 14.0
    private const val MIN_ZOOM = 3.0

    /** Web Mercator y. The map ends at about 85 degrees north and south. */
    private fun mercator(latitude: Double): Double = ln(tan(PI / 4 + latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) / 2))
    private const val MAX_LATITUDE = 1.4844222297453324

    private fun zoomFor(span: Double, dp: Double): Double = if (span <= 0.0) Double.POSITIVE_INFINITY else log2(dp * 2 * PI / (256 * span))

    /** Each supplied segment is a separate recording interval. Never connect two segments. */
    fun summarize(segments: List<List<TrackSample>>): RouteSummary {
        val route = RouteBuilder()
        for (segment in segments) {
            route.newSegment()
            for (sample in segment.filter { it.valid() }.distinctBy { it.elapsedNanos }.sortedBy { it.elapsedNanos }) route.add(sample)
        }
        return route.summary()
    }
}

/**
 * Builds a [RouteSummary] sample by sample. [RouteMath.summarize] uses it, so a live route and a saved route use the same rules.
 * Samples must be valid and in time order within each segment.
 */
class RouteBuilder {
    private var closedDistance = 0.0
    private var maximum: Float? = null
    private var gaps = 0
    private var missingNanos = 0L
    private val closedLines = mutableListOf<List<TrackSample>>()
    private var track: SegmentTrack? = null
    private var line = mutableListOf<TrackSample>()

    /** Time of the newest sample in the open segment. Null before its first sample. */
    val lastElapsedNanos: Long? get() = track?.lastElapsedNanos

    /** Starts a new recording interval. Its first sample starts a new line. */
    fun newSegment() {
        track?.let { closedDistance += it.distanceMeters }
        closeLine()
        track = SegmentTrack()
    }

    fun add(sample: TrackSample) {
        val open = track ?: SegmentTrack().also { track = it }
        sample.usableSpeed?.let { maximum = maxOf(maximum ?: it, it) }
        val step = open.add(sample)
        if (step.gapNanos > 0) {
            gaps++
            missingNanos += step.gapNanos
        }
        if (step.poorGap) gaps++
        if (step.gapNanos > 0 || !step.joined || step.sourceChanged) closeLine()
        if (step.joined) line += sample
    }

    /** A copy. Later samples do not change a summary that a screen already shows. */
    fun summary() = RouteSummary(closedDistance + (track?.distanceMeters ?: 0.0), maximum, gaps, missingNanos,
        if (line.isEmpty()) closedLines.toList() else closedLines + listOf(line.toList()))

    private fun closeLine() {
        if (line.isNotEmpty()) closedLines += line
        line = mutableListOf()
    }
}

/** The route of the recording trip, added point by point while a screen shows it. */
class LiveRoute(val tripId: String) {
    private val route = RouteBuilder()
    private val segments = mutableSetOf<String>()
    private var segmentId: String? = null

    /**
     * Adds one saved point. A repeated point changes nothing. Returns false for a point that is older than the route,
     * or that belongs to an earlier segment: the caller must then load the route again.
     */
    fun add(point: TripPoint): Boolean {
        if (point.segmentId != segmentId) {
            if (!segments.add(point.segmentId)) return false
            segmentId = point.segmentId
            route.newSegment()
        }
        val last = route.lastElapsedNanos
        if (last != null && point.sample.elapsedNanos <= last) return point.sample.elapsedNanos == last
        if (point.sample.valid()) route.add(point.sample)
        return true
    }

    fun summary(): RouteSummary = route.summary()
}

/**
 * One recording segment, sample by sample in time order. Trip summaries and the recorder use the same rules,
 * so the distance on a trip card agrees with the trip details.
 * A line contains fixes of one [com.example.cvkegps.location.FixSource]. The step between two sources adds no distance.
 */
class SegmentTrack {
    /**
     * One sample's effect: the signal gap before it, the start of a poor-position gap, whether it joins the line,
     * and whether it starts a line of another source.
     */
    data class Step(val gapNanos: Long, val poorGap: Boolean, val joined: Boolean, val sourceChanged: Boolean = false)

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
        val sourceChanged = previous?.let { it.source != sample.source } == true
        previous?.takeIf { !sourceChanged }?.let { last ->
            // Do not accumulate position noise while reported speed is zero.
            if (sample.usableSpeed != 0f || last.usableSpeed != 0f) distanceMeters += RouteMath.distance(last, sample)
        }
        previous = sample
        return Step(gapNanos, poorGap = false, joined = true, sourceChanged = sourceChanged)
    }
}


/** Distance divided by recording time. Recording time includes stops, because the app does not measure moving time. */
fun averageSpeed(distanceMeters: Double?, durationNanos: Long): Float? =
    distanceMeters?.takeIf { durationNanos >= 1_000_000_000L }?.let { (it / (durationNanos / 1e9)).toFloat() }
