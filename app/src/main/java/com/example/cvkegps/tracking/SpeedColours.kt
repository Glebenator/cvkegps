package com.example.cvkegps.tracking

import com.example.cvkegps.location.TrackSample
import kotlin.math.roundToInt

/** The speeds at the slow and the fast end of the route colours. Slower and faster speeds get the end colours. */
data class SpeedScale(val slowMetersPerSecond: Float, val fastMetersPerSecond: Float)

/**
 * A run of [segments] route segments between fixes. The colour goes from the [from] position on the colour scale to the [to] position,
 * from 0 (slow) to 1 (fast). A run with null positions has no usable speed.
 */
data class SpeedRun(val segments: Int, val from: Float?, val to: Float?)

/**
 * Colours a saved route by speed. The colours use the range of this trip, so a bike ride and a drive both use all colours.
 * A legend shows the speeds at the two ends. See ADR 0005.
 */
object SpeedColours {
    /** Speeds are averaged over this time, so one noisy fix does not change the colour. */
    const val SMOOTHING_NANOS = 5_000_000_000L

    /** Slower speeds are stops. They do not set the slow end of the scale. */
    const val MOVING_METERS_PER_SECOND = 0.5f

    /** The smallest range of the scale, so a trip at an even speed does not show noise as large colour changes. */
    const val MIN_RANGE_METERS_PER_SECOND = 2f

    /** Colour positions are rounded to this many steps, so runs at an even speed can share one span. */
    const val STEPS = 32

    /** For each fix, the mean usable speed of the fixes within half of [SMOOTHING_NANOS]. Null when no fix in that time has one. */
    fun smoothedSpeeds(line: List<TrackSample>): List<Float?> {
        val half = SMOOTHING_NANOS / 2
        var start = 0
        var end = 0
        var sum = 0.0
        var count = 0
        return line.map { fix ->
            while (end < line.size && line[end].elapsedNanos <= fix.elapsedNanos + half) {
                line[end].usableSpeed?.let { sum += it; count++ }
                end++
            }
            while (line[start].elapsedNanos < fix.elapsedNanos - half) {
                line[start].usableSpeed?.let { sum -= it; count-- }
                start++
            }
            if (count > 0) (sum / count).toFloat().coerceAtLeast(0f) else null
        }
    }

    /** The 5th and 95th percentiles of the moving speeds in all lines, so a few extreme fixes do not set the ends. */
    fun scale(lines: List<List<Float?>>): SpeedScale? {
        val moving = lines.flatten().filterNotNull().filter { it >= MOVING_METERS_PER_SECOND }.sorted()
        if (moving.size < 2) return null
        val slow = moving[((moving.size - 1) * 0.05).roundToInt()]
        val fast = moving[((moving.size - 1) * 0.95).roundToInt()]
        if (fast - slow >= MIN_RANGE_METERS_PER_SECOND) return SpeedScale(slow, fast)
        // A trip at an even speed gets the middle colours, not the slow end.
        val low = ((slow + fast) / 2 - MIN_RANGE_METERS_PER_SECOND / 2).coerceAtLeast(0f)
        return SpeedScale(low, low + MIN_RANGE_METERS_PER_SECOND)
    }

    /** The position of [speed] on the scale, from 0 to 1, rounded to [STEPS]. */
    fun position(speed: Float?, scale: SpeedScale): Float? = speed?.let {
        val fraction = ((it - scale.slowMetersPerSecond) / (scale.fastMetersPerSecond - scale.slowMetersPerSecond)).coerceIn(0f, 1f)
        (fraction * STEPS).roundToInt() / STEPS.toFloat()
    }

    /**
     * The runs for a line with these colour positions. Next segments with the same colour join into one run.
     * A segment with a missing speed at either end has no colour.
     */
    fun runs(positions: List<Float?>): List<SpeedRun> {
        val runs = mutableListOf<SpeedRun>()
        for (i in 1 until positions.size) {
            val from = positions[i - 1]
            val to = positions[i]
            val segment = if (from == null || to == null) SpeedRun(1, null, null) else SpeedRun(1, from, to)
            val last = runs.lastOrNull()
            if (last != null && last.from == last.to && segment.from == segment.to && last.to == segment.from) {
                runs[runs.lastIndex] = last.copy(segments = last.segments + 1)
            } else runs += segment
        }
        return runs
    }
}
