package com.example.cvkegps.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayChartDataTest {
    private val second = 1_000_000_000L

    @Test fun eachColumnKeepsItsPeaksAndRunsStaySeparate() {
        // Ten values each second for 10 seconds, with one short peak, then a second run after a gap.
        val times = LongArray(100) { it * second / 10 }
        val values = FloatArray(100) { if (it == 33) 50f else 10f }
        val runs = listOf(ReplayRun(times, values), ReplayRun(longArrayOf(15 * second, 16 * second), floatArrayOf(5f, 6f)))
        val columns = ReplayChartData.columns(runs, 20 * second, 20)
        assertEquals(2, columns.size)
        assertEquals(10, columns[0].size)
        assertEquals(ChartColumn(3, 10f, 50f), columns[0][3])
        assertEquals(listOf(ChartColumn(15, 5f, 5f), ChartColumn(16, 6f, 6f)), columns[1])
    }

    @Test fun timesThatNoRunCoversAreGaps() {
        val runs = listOf(ReplayRun(longArrayOf(2 * second, 5 * second), floatArrayOf(1f, 2f)),
            ReplayRun(longArrayOf(9 * second, 10 * second), floatArrayOf(1f, 2f), second / 2))
        assertEquals(listOf(0L..2 * second, 5 * second..(8 * second + second / 2)), ReplayChartData.uncovered(runs, 10 * second))
    }
}
