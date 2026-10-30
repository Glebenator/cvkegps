package com.example.cvkegps.tracking

import com.example.cvkegps.location.FixSource
import com.example.cvkegps.location.TrackSample
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The notable moments that a replay finds. */
enum class MomentKind { Acceleration, Braking, FastestSpeed, HighestPoint }

/** Where the replay height comes from. [Barometer] is the air pressure, set to the GPS height level. */
enum class HeightSource { Barometer, Gps, None }

/**
 * Values in time order on the replay clock. A run has no gap, so a chart or profile can join its values.
 * [reach] is how far before the first value and after the last value the end values still apply.
 * A 1 second mean applies for half a second on each side of its centre time.
 */
class ReplayRun(val times: LongArray, val values: FloatArray, val reach: Long = 0) {
    /** The value at [time], interpolated between two values. Null outside the run and its reach. */
    fun at(time: Long): Float? {
        if (times.isEmpty() || time < times.first() - reach || time > times.last() + reach) return null
        if (time <= times.first()) return values.first()
        if (time >= times.last()) return values.last()
        val index = times.binarySearch(time).let { if (it >= 0) return values[it] else -it - 1 }
        val before = index - 1
        val part = (time - times[before]).toDouble() / (times[index] - times[before])
        return (values[before] + (values[index] - values[before]) * part).toFloat()
    }
}

/** A notable moment. [number] is its order on the replay clock, from 1. A moment in a GPS gap has no position. */
data class ReplayMoment(
    val number: Int,
    val kind: MomentKind,
    val timeNanos: Long,
    /** m/s for speed, metres for height, m/s² for acceleration and braking (braking is negative). */
    val value: Double,
    val latitudeRadians: Double?,
    val longitudeRadians: Double?,
)

/** One stored fix on the replay clock. */
class ReplayFix(val timeNanos: Long, val segment: Int, val sample: TrackSample)

/** The values at one replay time. Null means unavailable. */
data class ReplayValues(
    /** The newest fix at or before the time, while it is current. Null in a GPS gap. */
    val fix: TrackSample?,
    /** The position on the route line at the time. Null in a gap. */
    val latitudeRadians: Double?,
    val longitudeRadians: Double?,
    /** The direction of travel at the time, turned evenly from one fix to the next. Null without a position or a bearing. */
    val bearingRadians: Double?,
    val speedMetersPerSecond: Float?,
    val heightMeters: Float?,
    val accelerationMetersPerSecondSquared: Float?,
)

/** One recording segment and its data, as the database stores them. */
class ReplaySegment(
    val startedElapsedNanos: Long,
    val endedElapsedNanos: Long?,
    val samples: List<TrackSample>,
    val sensors: List<SensorChunk>,
)

/**
 * A saved trip, ready to replay. The replay clock joins the segments without the pauses between them.
 * Each value comes from stored measurements. See GLOSSARY.md.
 */
class TripReplay(
    val durationNanos: Long,
    /** Replay times where a segment after the first starts. A pause or an interruption was there. */
    val segmentStarts: List<Long>,
    val fixes: List<ReplayFix>,
    val lines: List<List<TrackSample>>,
    /** The replay time of each point of [lines], in the same order. */
    val lineTimes: List<LongArray>,
    val speed: List<ReplayRun>,
    val height: List<ReplayRun>,
    val heightSource: HeightSource,
    val acceleration: List<ReplayRun>,
    /** True when the acceleration comes from the phone sensors. False when it comes from the GPS speed. */
    val accelerationFromSensors: Boolean,
    /** Replay time ranges without a current GPS fix. */
    val gpsGaps: List<LongRange>,
    /** Replay time ranges with network positions only. */
    val approximate: List<LongRange>,
    val moments: List<ReplayMoment>,
) {
    /** The values at [time]. The newest fix shows only for [RouteMath.SIGNAL_GAP_NANOS], as on the live screen. */
    fun valuesAt(time: Long): ReplayValues {
        val index = lastFixAt(time)
        val fix = fixes.getOrNull(index)?.takeIf { time - it.timeNanos < RouteMath.SIGNAL_GAP_NANOS }
        val position = fix?.takeIf { it.sample.usablePosition }?.let { interpolate(index, time) }
        return ReplayValues(
            fix?.sample, position?.latitude, position?.longitude, position?.bearing,
            fix?.sample?.usableSpeed,
            height.firstNotNullOfOrNull { it.at(time) },
            acceleration.firstNotNullOfOrNull { it.at(time) },
        )
    }

    private fun lastFixAt(time: Long): Int {
        var low = 0
        var high = fixes.lastIndex
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (fixes[middle].timeNanos <= time) { found = middle; low = middle + 1 } else high = middle - 1
        }
        return found
    }

    private class Position(val latitude: Double, val longitude: Double, val bearing: Double?)

    /**
     * A position between two fixes of the same line. A gap, a poor fix, or a change of source is not crossed.
     * The bearing turns the shorter way from the first fix's bearing to the next one's.
     */
    private fun interpolate(index: Int, time: Long): Position {
        val a = fixes[index]
        val b = fixes.getOrNull(index + 1)
        val first = a.sample.bearingRadians?.takeIf { it.isFinite() }
        if (b == null || b.segment != a.segment || !b.sample.usablePosition || b.sample.source != a.sample.source ||
            b.timeNanos - a.timeNanos >= RouteMath.SIGNAL_GAP_NANOS || b.timeNanos == a.timeNanos) {
            return Position(a.sample.latitudeRadians, a.sample.longitudeRadians, first)
        }
        val part = (time - a.timeNanos).toDouble() / (b.timeNanos - a.timeNanos)
        var longitude = a.sample.longitudeRadians + shorter(b.sample.longitudeRadians - a.sample.longitudeRadians) * part
        if (longitude > Math.PI) longitude -= 2 * Math.PI else if (longitude < -Math.PI) longitude += 2 * Math.PI
        val next = b.sample.bearingRadians?.takeIf { it.isFinite() }
        val bearing = if (first == null || next == null) first else {
            val turned = first + shorter(next - first) * part
            if (turned < 0) turned + 2 * Math.PI else if (turned >= 2 * Math.PI) turned - 2 * Math.PI else turned
        }
        return Position(a.sample.latitudeRadians + (b.sample.latitudeRadians - a.sample.latitudeRadians) * part, longitude, bearing)
    }

    /** An angle step the shorter way round, from -π to π. It also crosses the date line the shorter way. */
    private fun shorter(step: Double): Double = when {
        step > Math.PI -> step - 2 * Math.PI
        step < -Math.PI -> step + 2 * Math.PI
        else -> step
    }

    /** How many points of route line [line] are at or before [time]. The map draws them as the played part. */
    fun playedPoints(line: Int, time: Long): Int {
        val times = lineTimes.getOrNull(line) ?: return 0
        var low = 0
        var high = times.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (times[middle] <= time) low = middle + 1 else high = middle
        }
        return low
    }

    /** The moment at or after [time] plus one nanosecond, or null. */
    fun nextMoment(time: Long): ReplayMoment? = moments.firstOrNull { it.timeNanos > time }
    fun previousMoment(time: Long): ReplayMoment? = moments.lastOrNull { it.timeNanos < time }
}

object ReplayAnalysis {
    /** Accelerations smaller than this are not moments. A car that starts gently reaches about 1 m/s². */
    const val MIN_MOMENT_ACCELERATION = 0.5
    /** The height must change by at least this much before the highest point is a moment. */
    const val MIN_HEIGHT_RANGE_METERS = 1.0
    private const val BIN_NANOS = 1_000_000_000L
    private const val YAW_WINDOW_NANOS = 30_000_000_000L
    private const val SENSOR_GAP_NANOS = 2_000_000_000L
    private const val GRAVITY = 9.80665
    private const val GOOD_VERTICAL_ACCURACY_METERS = 10f
    /** Minimum GPS change of velocity in a window, so the alignment angle is reliable. */
    private const val MIN_ALIGNMENT_SIGNAL = 3.0

    /** [gpsHeightOnly] is for Plane trips: cabin pressure is not the outside pressure. */
    fun analyze(segments: List<ReplaySegment>, gpsHeightOnly: Boolean = false): TripReplay {
        val fixes = mutableListOf<ReplayFix>()
        val segmentStarts = mutableListOf<Long>()
        val speed = mutableListOf<ReplayRun>()
        val gpsHeight = mutableListOf<ReplayRun>()
        val pressureHeight = mutableListOf<Pair<ReplayRun, Int>>()
        val sensorAcceleration = mutableListOf<ReplayRun>()
        val gpsAcceleration = mutableListOf<ReplayRun>()
        val gaps = mutableListOf<LongRange>()
        val approximate = mutableListOf<LongRange>()
        var offset = 0L
        // The route lines keep the sample objects, so each line point finds its replay time.
        val sampleTimes = java.util.IdentityHashMap<TrackSample, Long>()
        segments.forEachIndexed { index, segment ->
            val samples = segment.samples.filter { it.valid() }.distinctBy { it.elapsedNanos }.sortedBy { it.elapsedNanos }
            val start = segment.startedElapsedNanos
            val lastData = listOfNotNull(samples.lastOrNull()?.elapsedNanos, segment.sensors.maxOfOrNull { it.elapsedNanos.last() }).maxOrNull()
            val end = maxOf(segment.endedElapsedNanos ?: lastData ?: start, start)
            val clock = { elapsed: Long -> offset + (elapsed - start) }
            if (index > 0) segmentStarts += offset
            samples.forEach { sampleTimes[it] = clock(it.elapsedNanos).coerceIn(offset, offset + end - start) }
            val inSegment = samples.filter { it.elapsedNanos in start..end }
            inSegment.forEach { fixes += ReplayFix(clock(it.elapsedNanos), index, it) }
            speed += runs(inSegment.filter { it.source == FixSource.Gps }, clock) { it.usableSpeed }
            gpsHeight += runs(inSegment.filter { it.source == FixSource.Gps }, clock) { s -> s.altitudeMeters?.takeIf { s.usablePosition }?.toFloat() }
            gaps += gpsGaps(inSegment, start, end, clock)
            approximate += networkRanges(inSegment, end, clock)
            val chunks = segment.sensors.filter { it.elapsedNanos.last() >= start && it.elapsedNanos.first() <= end }
            if (!gpsHeightOnly) pressureHeight += pressureRuns(chunks, start, end, clock).map { it to index }
            sensorAcceleration += sensorAlongTrack(chunks, inSegment, start, end, clock)
            gpsAcceleration += gpsAlongTrack(inSegment, start, clock)
            offset += end - start
        }
        // The barometer gives the shape. The GPS height gives the level: the median difference over fixes with a good vertical accuracy.
        val level = barometerLevel(pressureHeight.map { it.first }, fixes)
        val (height, heightSource) = when {
            level != null -> pressureHeight.map { (run, _) ->
                ReplayRun(run.times, FloatArray(run.values.size) { run.values[it] + level }, run.reach)
            } to HeightSource.Barometer
            gpsHeight.isNotEmpty() -> gpsHeight to HeightSource.Gps
            else -> emptyList<ReplayRun>() to HeightSource.None
        }
        val fromSensors = sensorAcceleration.isNotEmpty()
        val acceleration = if (fromSensors) sensorAcceleration else gpsAcceleration
        val lines = RouteMath.summarize(segments.map { it.samples }).lines
        val lineTimes = lines.map { line ->
            var last = 0L
            LongArray(line.size) { i -> (sampleTimes[line[i]] ?: last).also { last = it } }
        }
        val replay = TripReplay(offset, segmentStarts, fixes, lines, lineTimes, speed, height, heightSource, acceleration, fromSensors,
            gaps, approximate, emptyList())
        return TripReplay(offset, segmentStarts, fixes, lines, lineTimes, speed, height, heightSource, acceleration, fromSensors,
            gaps, approximate, moments(replay))
    }

    /** Splits [samples] into runs of consecutive values. A null value or a gap of 10 seconds ends a run. */
    private fun runs(samples: List<TrackSample>, clock: (Long) -> Long, value: (TrackSample) -> Float?): List<ReplayRun> {
        val result = mutableListOf<ReplayRun>()
        val times = mutableListOf<Long>()
        val values = mutableListOf<Float>()
        fun close() {
            if (times.isNotEmpty()) result += ReplayRun(times.toLongArray(), values.toFloatArray())
            times.clear(); values.clear()
        }
        for (sample in samples) {
            val v = value(sample)
            val time = clock(sample.elapsedNanos)
            if (v == null || !v.isFinite() || (times.isNotEmpty() && time - times.last() >= RouteMath.SIGNAL_GAP_NANOS)) close()
            if (v != null && v.isFinite()) { times += time; values += v }
        }
        close()
        return result
    }

    /** Times without a current, usable GPS fix: from 10 seconds after a fix until the next one. */
    private fun gpsGaps(samples: List<TrackSample>, start: Long, end: Long, clock: (Long) -> Long): List<LongRange> {
        val gps = samples.filter { it.source == FixSource.Gps && it.usablePosition }.map { it.elapsedNanos }
        val result = mutableListOf<LongRange>()
        var current = start
        for (time in gps + end) {
            val lostAt = current + RouteMath.SIGNAL_GAP_NANOS
            if (time > lostAt) result += clock(lostAt)..clock(time)
            current = time
        }
        return result
    }

    /** Times when a network position is the current position: from each network position until the next fix, for at most 10 seconds. */
    private fun networkRanges(samples: List<TrackSample>, end: Long, clock: (Long) -> Long): List<LongRange> {
        val result = mutableListOf<LongRange>()
        samples.forEachIndexed { index, sample ->
            if (sample.source != FixSource.Network || !sample.usablePosition) return@forEachIndexed
            val until = minOf(sample.elapsedNanos + RouteMath.SIGNAL_GAP_NANOS, samples.getOrNull(index + 1)?.elapsedNanos ?: end, end)
            val range = clock(sample.elapsedNanos)..clock(until)
            val last = result.lastOrNull()
            if (last != null && last.last >= range.first) result[result.lastIndex] = last.first..range.last else result += range
        }
        return result
    }

    /** Height from air pressure with the standard atmosphere, in 1 second means, smoothed over 3 seconds. */
    private fun pressureRuns(chunks: List<SensorChunk>, start: Long, end: Long, clock: (Long) -> Long): List<ReplayRun> {
        val readings = chunks.filter { it.kind == SensorKind.Pressure }.flatMap { chunk ->
            (0 until chunk.count).map { chunk.elapsedNanos[it] to chunk.values[it] }
        }.filter { (time, value) -> time in start..end && value.isFinite() && value > 0f }.sortedBy { it.first }
        val bins = bin(readings.map { (time, hpa) -> time to doubleArrayOf(pressureHeight(hpa.toDouble())) }, start)
        return binRuns(bins, start, clock) { values -> values[0] }.map(::smooth)
    }

    /** Height in metres for a pressure in hPa, in the standard atmosphere. Only differences are reliable. */
    fun pressureHeight(hectopascals: Double): Double = 44_330.0 * (1 - (hectopascals / 1_013.25).pow(1 / 5.255))

    private fun smooth(run: ReplayRun): ReplayRun = ReplayRun(run.times, FloatArray(run.values.size) { index ->
        val range = (index - 1).coerceAtLeast(0)..(index + 1).coerceAtMost(run.values.lastIndex)
        range.map { run.values[it] }.average().toFloat()
    }, run.reach)

    private fun barometerLevel(runs: List<ReplayRun>, fixes: List<ReplayFix>): Float? {
        if (runs.isEmpty()) return null
        val differences = fixes.mapNotNull { fix ->
            val sample = fix.sample
            val gps = sample.altitudeMeters?.takeIf {
                sample.source == FixSource.Gps && sample.usablePosition &&
                    (sample.verticalAccuracyMeters ?: Float.MAX_VALUE) <= GOOD_VERTICAL_ACCURACY_METERS
            } ?: return@mapNotNull null
            runs.firstNotNullOfOrNull { it.at(fix.timeNanos) }?.let { gps - it }
        }.sorted()
        if (differences.isEmpty()) return null
        return differences[differences.size / 2].toFloat()
    }

    /**
     * Acceleration along the direction of travel, from the accelerometer. The game rotation vector turns each reading into
     * a frame with a vertical axis but no north. In each 30 second window, the angle that best fits the GPS change of
     * velocity turns that frame to east and north. The GPS direction of travel then gives the part along the track.
     */
    private fun sensorAlongTrack(
        chunks: List<SensorChunk>, samples: List<TrackSample>, start: Long, end: Long, clock: (Long) -> Long,
    ): List<ReplayRun> {
        val rotation = readings(chunks, SensorKind.Rotation, start, end)
        val acceleration = readings(chunks, SensorKind.Acceleration, start, end)
        if (rotation.isEmpty() || acceleration.isEmpty()) return emptyList()
        // Horizontal acceleration in the game frame, gravity removed.
        val horizontal = mutableListOf<Pair<Long, DoubleArray>>()
        var r = 0
        for ((time, a) in acceleration) {
            while (r + 1 < rotation.size && rotation[r + 1].first <= time) r++
            val nearest = listOfNotNull(rotation.getOrNull(r), rotation.getOrNull(r + 1)).minBy { kotlin.math.abs(it.first - time) }
            if (kotlin.math.abs(nearest.first - time) > 100_000_000L) continue
            val world = rotate(nearest.second, a)
            horizontal += time to doubleArrayOf(world[0], world[1])
        }
        val sensorBins = bin(horizontal, start)
        val gpsBins = gpsAccelerationBins(samples, start)
        val angles = alignmentAngles(sensorBins, gpsBins, end - start) ?: return emptyList()
        val moving = samples.filter { it.source == FixSource.Gps && (it.usableSpeed ?: 0f) >= 1f && it.bearingRadians != null }
        val movingTimes = moving.map { it.elapsedNanos }.toLongArray()
        val along = sensorBins.mapNotNull { (bin, s) ->
            val direction = travelDirection(moving, movingTimes, start + bin * BIN_NANOS + BIN_NANOS / 2) ?: return@mapNotNull null
            val angle = angles[(bin * BIN_NANOS / YAW_WINDOW_NANOS).toInt().coerceIn(0, angles.lastIndex)]
            val east = s[0] * cos(angle) - s[1] * sin(angle)
            val north = s[0] * sin(angle) + s[1] * cos(angle)
            bin to doubleArrayOf(east * direction[0] + north * direction[1])
        }.toMap()
        return binRuns(along, start, clock) { it[0] }
    }

    private fun readings(chunks: List<SensorChunk>, kind: SensorKind, start: Long, end: Long): List<Pair<Long, FloatArray>> =
        chunks.filter { it.kind == kind }.flatMap { chunk ->
            (0 until chunk.count).map { i -> chunk.elapsedNanos[i] to chunk.values.copyOfRange(i * kind.values, (i + 1) * kind.values) }
        }.filter { (time, values) -> time in start..end && values.take(3).all { it.isFinite() } }.sortedBy { it.first }

    /** Turns [vector] from the phone's axes into the game frame with the unit quaternion (x, y, z, w). Removes gravity. */
    internal fun rotate(quaternion: FloatArray, vector: FloatArray): DoubleArray {
        val x = quaternion[0].toDouble()
        val y = quaternion[1].toDouble()
        val z = quaternion[2].toDouble()
        val w = quaternion.getOrNull(3)?.takeIf { it.isFinite() }?.toDouble() ?: sqrt((1 - x * x - y * y - z * z).coerceAtLeast(0.0))
        val (vx, vy, vz) = Triple(vector[0].toDouble(), vector[1].toDouble(), vector[2].toDouble())
        // The rotation matrix of a unit quaternion, as Android's getRotationMatrixFromVector.
        return doubleArrayOf(
            (1 - 2 * (y * y + z * z)) * vx + 2 * (x * y - z * w) * vy + 2 * (x * z + y * w) * vz,
            2 * (x * y + z * w) * vx + (1 - 2 * (x * x + z * z)) * vy + 2 * (y * z - x * w) * vz,
            2 * (x * z - y * w) * vx + 2 * (y * z + x * w) * vy + (1 - 2 * (x * x + y * y)) * vz - GRAVITY,
        )
    }

    /** GPS change of velocity, east and north, in 1 second bins. */
    private fun gpsAccelerationBins(samples: List<TrackSample>, start: Long): Map<Long, DoubleArray> {
        val velocities = samples.filter { it.source == FixSource.Gps }.mapNotNull { sample ->
            val speed = sample.usableSpeed ?: return@mapNotNull null
            val bearing = sample.bearingRadians
            val velocity = when {
                speed < 0.5f -> doubleArrayOf(0.0, 0.0)
                bearing != null -> doubleArrayOf(speed * sin(bearing), speed * cos(bearing))
                else -> return@mapNotNull null
            }
            sample.elapsedNanos to velocity
        }
        val steps = velocities.zipWithNext().mapNotNull { (a, b) ->
            val seconds = (b.first - a.first) / 1e9
            if (seconds <= 0 || seconds > 3) return@mapNotNull null
            (a.first + b.first) / 2 to doubleArrayOf((b.second[0] - a.second[0]) / seconds, (b.second[1] - a.second[1]) / seconds)
        }
        return bin(steps, start)
    }

    /**
     * The angle for each 30 second window that turns the game frame to east and north. A window with too little
     * GPS change of velocity uses the nearest window that has enough. Null when no window has enough.
     */
    private fun alignmentAngles(sensor: Map<Long, DoubleArray>, gps: Map<Long, DoubleArray>, durationNanos: Long): DoubleArray? {
        val windows = (durationNanos / YAW_WINDOW_NANOS + 1).toInt()
        val angles = arrayOfNulls<Double>(windows)
        for (window in 0 until windows) {
            var cross = 0.0
            var dot = 0.0
            var signal = 0.0
            for ((bin, g) in gps) {
                if ((bin * BIN_NANOS / YAW_WINDOW_NANOS).toInt() != window) continue
                val s = sensor[bin] ?: continue
                cross += s[0] * g[1] - s[1] * g[0]
                dot += s[0] * g[0] + s[1] * g[1]
                signal += hypot(g[0], g[1])
            }
            if (signal >= MIN_ALIGNMENT_SIGNAL) angles[window] = atan2(cross, dot)
        }
        val known = angles.indices.filter { angles[it] != null }
        if (known.isEmpty()) return null
        return DoubleArray(windows) { window -> angles[window] ?: angles[known.minBy { kotlin.math.abs(it - window) }]!! }
    }

    /** The unit direction of travel (east, north) from the nearest [moving] GPS fix within 5 seconds. [times] are their fix times. */
    private fun travelDirection(moving: List<TrackSample>, times: LongArray, time: Long): DoubleArray? {
        if (times.isEmpty()) return null
        val insert = times.binarySearch(time).let { if (it >= 0) it else -it - 1 }
        val nearest = listOf(insert - 1, insert).filter { it in times.indices }.minBy { kotlin.math.abs(times[it] - time) }
        if (kotlin.math.abs(times[nearest] - time) > 5_000_000_000L) return null
        return moving[nearest].bearingRadians!!.let { doubleArrayOf(sin(it), cos(it)) }
    }

    /** Acceleration from the change of GPS speed, for trips without the motion sensors. */
    private fun gpsAlongTrack(samples: List<TrackSample>, start: Long, clock: (Long) -> Long): List<ReplayRun> {
        val speeds = samples.filter { it.source == FixSource.Gps }.mapNotNull { s -> s.usableSpeed?.let { s.elapsedNanos to it } }
        val steps = speeds.zipWithNext().mapNotNull { (a, b) ->
            val seconds = (b.first - a.first) / 1e9
            if (seconds <= 0 || seconds > 3) null else (a.first + b.first) / 2 to doubleArrayOf((b.second - a.second) / seconds)
        }
        return binRuns(bin(steps, start), start, clock) { it[0] }
    }

    /** Means in 1 second bins after [start]. The key is the bin number. */
    private fun bin(values: List<Pair<Long, DoubleArray>>, start: Long): Map<Long, DoubleArray> {
        val sums = sortedMapOf<Long, Pair<DoubleArray, Int>>()
        for ((time, value) in values) {
            val key = (time - start).floorDiv(BIN_NANOS)
            val (sum, count) = sums[key] ?: (DoubleArray(value.size) to 0)
            for (i in value.indices) sum[i] += value[i]
            sums[key] = sum to count + 1
        }
        return sums.mapValuesTo(linkedMapOf()) { (_, entry) -> DoubleArray(entry.first.size) { entry.first[it] / entry.second } }
    }

    /** Runs of bins at their centre times. [origin] is the fix time of bin 0. A missing bin for more than 2 seconds ends a run. */
    private fun binRuns(bins: Map<Long, DoubleArray>, origin: Long, clock: (Long) -> Long, value: (DoubleArray) -> Double): List<ReplayRun> {
        if (bins.isEmpty()) return emptyList()
        val result = mutableListOf<ReplayRun>()
        val times = mutableListOf<Long>()
        val values = mutableListOf<Float>()
        var previous: Long? = null
        for ((bin, v) in bins.toSortedMap()) {
            if (previous != null && (bin - previous) * BIN_NANOS > SENSOR_GAP_NANOS) {
                result += ReplayRun(times.toLongArray(), values.toFloatArray(), BIN_NANOS / 2); times.clear(); values.clear()
            }
            times += clock(origin + bin * BIN_NANOS + BIN_NANOS / 2)
            values += value(v).toFloat()
            previous = bin
        }
        result += ReplayRun(times.toLongArray(), values.toFloatArray(), BIN_NANOS / 2)
        return result
    }

    private fun moments(replay: TripReplay): List<ReplayMoment> {
        fun extreme(runs: List<ReplayRun>, highest: Boolean): Pair<Long, Float>? = runs.flatMap { run ->
            run.times.indices.map { run.times[it] to run.values[it] }
        }.let { if (highest) it.maxByOrNull { p -> p.second } else it.minByOrNull { p -> p.second } }
        val found = mutableListOf<Pair<MomentKind, Pair<Long, Float>>>()
        extreme(replay.acceleration, true)?.takeIf { it.second >= MIN_MOMENT_ACCELERATION }?.let { found += MomentKind.Acceleration to it }
        extreme(replay.acceleration, false)?.takeIf { it.second <= -MIN_MOMENT_ACCELERATION }?.let { found += MomentKind.Braking to it }
        extreme(replay.speed, true)?.takeIf { it.second > 0f }?.let { found += MomentKind.FastestSpeed to it }
        val highest = extreme(replay.height, true)
        val lowest = extreme(replay.height, false)
        if (highest != null && lowest != null && highest.second - lowest.second >= MIN_HEIGHT_RANGE_METERS) {
            found += MomentKind.HighestPoint to highest
        }
        return found.sortedBy { it.second.first }.mapIndexed { index, (kind, at) ->
            val values = replay.valuesAt(at.first)
            ReplayMoment(index + 1, kind, at.first, at.second.toDouble(), values.latitudeRadians, values.longitudeRadians)
        }
    }
}
