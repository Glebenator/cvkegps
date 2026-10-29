package com.example.cvkegps.tracking

/** The lowest and highest value of one pixel column of a chart. */
data class ChartColumn(val index: Int, val minimum: Float, val maximum: Float)

/**
 * Chart values reduced to one minimum and one maximum for each pixel column, so a long trip with 10 fixes each second
 * draws as fast as a short one, and a short peak stays visible. Each run stays a separate line, so a gap is not joined.
 */
object ReplayChartData {
    fun columns(runs: List<ReplayRun>, durationNanos: Long, columns: Int): List<List<ChartColumn>> {
        if (columns <= 0 || durationNanos <= 0) return emptyList()
        return runs.mapNotNull { run ->
            val result = mutableListOf<ChartColumn>()
            var index = -1
            var minimum = 0f
            var maximum = 0f
            for (i in run.times.indices) {
                val value = run.values[i]
                if (!value.isFinite()) continue
                val column = (run.times[i].toDouble() / durationNanos * columns).toInt().coerceIn(0, columns - 1)
                if (column != index) {
                    if (index >= 0) result += ChartColumn(index, minimum, maximum)
                    index = column
                    minimum = value
                    maximum = value
                } else {
                    minimum = minOf(minimum, value)
                    maximum = maxOf(maximum, value)
                }
            }
            if (index >= 0) result += ChartColumn(index, minimum, maximum)
            result.takeIf { it.isNotEmpty() }
        }
    }

    /** Replay time ranges inside [0, durationNanos] that no run covers, for example a gap in the sensor record. */
    fun uncovered(runs: List<ReplayRun>, durationNanos: Long): List<LongRange> {
        val covered = runs.filter { it.times.isNotEmpty() }
            .map { (it.times.first() - it.reach).coerceAtLeast(0)..(it.times.last() + it.reach).coerceAtMost(durationNanos) }
            .sortedBy { it.first }
        val result = mutableListOf<LongRange>()
        var from = 0L
        for (range in covered) {
            if (range.first > from) result += from..range.first
            from = maxOf(from, range.last)
        }
        if (from < durationNanos) result += from..durationNanos
        return result
    }
}
