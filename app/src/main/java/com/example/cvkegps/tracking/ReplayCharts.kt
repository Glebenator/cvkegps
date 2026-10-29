package com.example.cvkegps.tracking

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cvkegps.R
import com.example.cvkegps.ui.DisplayFormat
import com.example.cvkegps.ui.theme.CvkeTheme
import com.example.cvkegps.ui.theme.Size
import com.example.cvkegps.ui.theme.Space
import kotlin.math.abs

/** The charts of the replay details. Each has its own values and moments, and the same cursor. */
enum class ReplayChartKind { Speed, Height, Acceleration }

/** One chart's values and how to show them. [label] formats a value in display units. */
private class ChartSpec(
    val kind: ReplayChartKind,
    val runs: List<ReplayRun>,
    /** Hatched times: GPS gaps for speed, gaps in the record for height and acceleration. */
    val gaps: List<LongRange>,
    val approximate: List<LongRange>,
    val moments: List<ReplayMoment>,
    val low: Float,
    val high: Float,
    val zeroLine: Boolean,
    val label: (Float) -> String,
)

/**
 * Speed, height, and along-track acceleration with one shared cursor. A tap or a horizontal drag on any chart moves the
 * cursor. A vertical drag scrolls the page. Each chart has a summary for a screen reader, and the first chart is the slider.
 *
 * With [selected], a selector shows one chart at a time. A narrow pane and the largest text sizes use it.
 */
@Composable
internal fun ReplayCharts(
    replay: TripReplay,
    playhead: Long,
    format: DisplayFormat,
    heightOffset: Double,
    heightTitle: Int,
    state: String,
    seek: (Long) -> Unit,
    pauseAt: (Long) -> Unit,
    selected: ReplayChartKind? = null,
    onSelect: (ReplayChartKind) -> Unit = {},
    chartHeight: Dp = 96.dp,
) {
    val specs = remember(replay, format, heightOffset) { chartSpecs(replay, format, heightOffset) }
    val titles = mapOf(
        ReplayChartKind.Speed to stringResource(R.string.replay_speed_label),
        ReplayChartKind.Height to stringResource(heightTitle),
        ReplayChartKind.Acceleration to stringResource(R.string.replay_acceleration_label),
    )
    val title = { kind: ReplayChartKind -> titles.getValue(kind) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        val shown = if (selected == null) specs else listOf(specs.firstOrNull { it.kind == selected } ?: specs.first())
        if (selected != null && specs.size > 1) {
            // The chart names wrap to a second row at large text sizes.
            FlowRow(Modifier.fillMaxWidth().selectableGroup().testTag("chart_selector"),
                horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                for (spec in specs) {
                    val picked = spec === shown.single()
                    FilterChip(selected = picked, onClick = { onSelect(spec.kind) }, label = { Text(title(spec.kind)) },
                        modifier = Modifier.heightIn(min = Size.touch).semantics { role = Role.RadioButton }
                            .testTag("chart_pick_${spec.kind.name}"))
                }
            }
        }
        shown.forEachIndexed { index, spec ->
            if (selected == null) Text(title(spec.kind), style = MaterialTheme.typography.labelLarge)
            Chart(spec, replay, playhead, summary(spec, replay, format, heightOffset), state, seek, pauseAt, slider = index == 0, chartHeight)
        }
    }
}

private fun chartSpecs(replay: TripReplay, format: DisplayFormat, heightOffset: Double): List<ChartSpec> {
    val result = mutableListOf<ChartSpec>()
    val speedHigh = replay.speed.maxOfOrNull { it.values.max() }?.coerceAtLeast(1f) ?: 1f
    result += ChartSpec(ReplayChartKind.Speed, replay.speed, replay.gpsGaps, replay.approximate,
        replay.moments.filter { it.kind == MomentKind.FastestSpeed }, 0f, speedHigh * 1.1f, false) { format.speed(it) }
    if (replay.height.isNotEmpty()) {
        val low = replay.height.minOf { it.values.min() }
        val high = replay.height.maxOf { it.values.max() }
        // A flat trip still shows a band of at least 10 m, so noise does not fill the chart.
        val middle = (low + high) / 2
        val half = maxOf((high - low) / 2 * 1.1f, 5f)
        result += ChartSpec(ReplayChartKind.Height, replay.height, ReplayChartData.uncovered(replay.height, replay.durationNanos), emptyList(),
            replay.moments.filter { it.kind == MomentKind.HighestPoint }, middle - half, middle + half, false) {
            format.altitude(it - heightOffset)
        }
    }
    if (replay.acceleration.isNotEmpty()) {
        val extreme = maxOf(replay.acceleration.maxOf { run -> run.values.maxOf { abs(it) } } * 1.1f, 1f)
        result += ChartSpec(ReplayChartKind.Acceleration, replay.acceleration,
            ReplayChartData.uncovered(replay.acceleration, replay.durationNanos), emptyList(),
            replay.moments.filter { it.kind == MomentKind.Acceleration || it.kind == MomentKind.Braking }, -extreme, extreme, true) {
            format.acceleration(it.toDouble())
        }
    }
    return result
}

@Composable
private fun summary(spec: ChartSpec, replay: TripReplay, format: DisplayFormat, heightOffset: Double): String {
    val values = spec.runs.flatMap { run -> run.times.indices.map { run.times[it] to run.values[it] } }
    if (values.isEmpty()) return stringResource(R.string.chart_none)
    val low = values.minOf { it.second }
    val high = values.maxBy { it.second }
    return when (spec.kind) {
        ReplayChartKind.Speed -> stringResource(R.string.chart_speed_summary, format.speed(low), format.speed(high.second),
            format.duration(high.first))
        ReplayChartKind.Height -> stringResource(R.string.chart_height_summary, format.altitude(low - heightOffset),
            format.altitude(high.second - heightOffset), format.duration(high.first))
        ReplayChartKind.Acceleration -> stringResource(R.string.chart_acceleration_summary,
            format.acceleration(abs(minOf(low, 0f)).toDouble()), format.acceleration(maxOf(high.second, 0f).toDouble()))
    }
}

@Composable
private fun Chart(
    spec: ChartSpec, replay: TripReplay, playhead: Long, summary: String, state: String, seek: (Long) -> Unit, pauseAt: (Long) -> Unit,
    slider: Boolean, chartHeight: Dp,
) {
    val colors = CvkeTheme.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val duration = replay.durationNanos.coerceAtLeast(1)
    val onSeek by rememberUpdatedState(seek)
    val onPause by rememberUpdatedState(pauseAt)
    val labelStyle = TextStyle(color = colors.inkMuted, fontSize = 11.sp)
    val numberStyle = TextStyle(color = colors.accentInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val numberSize = remember(measurer, numberStyle) { measurer.measure("8", numberStyle).size }
    val radius = with(density) { maxOf(8.dp.toPx(), numberSize.height / 2f + 2.dp.toPx()) }
    val nextLabel = stringResource(R.string.replay_next)
    val previousLabel = stringResource(R.string.replay_previous)
    // The reduction to pixel columns runs again only for new values or a new width, not when the playhead moves.
    var widthPx by remember { mutableIntStateOf(0) }
    val columns = remember(spec, widthPx, duration) { ReplayChartData.columns(spec.runs, duration, widthPx) }
    Column {
        Spacer(Modifier.height(with(density) { (radius * 2).toDp() }))
        Box(Modifier.fillMaxWidth().height(chartHeight).testTag("chart_${spec.kind.name}").onSizeChanged { widthPx = it.width }
            .pointerInput(duration, spec) {
                detectTapGestures { tap ->
                    // A tap near a moment number goes to that moment and pauses. Other taps move the cursor.
                    val near = spec.moments.minByOrNull { abs(it.timeNanos.toFloat() / duration * size.width - tap.x) }
                        ?.takeIf { abs(it.timeNanos.toFloat() / duration * size.width - tap.x) <= radius * 1.5f }
                    if (near != null) onPause(near.timeNanos) else onSeek((tap.x / size.width * duration).toLong())
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / size.width * duration).toLong()) }
            }
            .semantics {
                contentDescription = summary
                if (slider) {
                    stateDescription = state
                    progressBarRangeInfo = ProgressBarRangeInfo(playhead / 1e9f, 0f..duration / 1e9f)
                    setProgress { seconds -> onSeek((seconds * 1e9).toLong()); true }
                    customActions = listOf(
                        CustomAccessibilityAction(nextLabel) { replay.nextMoment(playhead)?.let { onPause(it.timeNanos) } != null },
                        CustomAccessibilityAction(previousLabel) { replay.previousMoment(playhead)?.let { onPause(it.timeNanos) } != null },
                    )
                }
            }
            .drawWithCache {
                val width = size.width
                val height = size.height
                fun x(time: Long) = time.toFloat() / duration * width
                fun y(value: Float) = height - (value - spec.low) / (spec.high - spec.low) * height
                val lines = columns.map { run ->
                    val middle = Path()
                    val band = Path()
                    run.forEachIndexed { i, column ->
                        val cx = column.index + 0.5f
                        val my = y((column.minimum + column.maximum) / 2)
                        if (i == 0) middle.moveTo(cx, my) else middle.lineTo(cx, my)
                        if (i == 0) band.moveTo(cx, y(column.maximum)) else band.lineTo(cx, y(column.maximum))
                    }
                    for (column in run.asReversed()) band.lineTo(column.index + 0.5f, y(column.minimum))
                    band.close()
                    middle to band
                }
                val low = measurer.measure(spec.label(spec.low.coerceAtLeast(if (spec.kind == ReplayChartKind.Speed) 0f else spec.low)), labelStyle)
                val high = measurer.measure(spec.label(spec.high), labelStyle)
                val numbers = spec.moments.map { it to measurer.measure(it.number.toString(), numberStyle) }
                val hatch = 6.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                onDrawBehind {
                    drawRoundRect(colors.surfaceSunken, cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                    for (gap in spec.gaps) {
                        val left = x(gap.first)
                        val right = x(gap.last)
                        clipRect(left, 0f, right, height) {
                            var line = left - height
                            while (line < right) {
                                drawLine(colors.inkMuted.copy(alpha = 0.45f), Offset(line, height), Offset(line + height, 0f), 1.dp.toPx())
                                line += hatch
                            }
                        }
                    }
                    if (spec.zeroLine) drawLine(colors.inkMuted, Offset(0f, y(0f)), Offset(width, y(0f)), 1.dp.toPx())
                    for ((middle, band) in lines) {
                        drawPath(band, colors.accent.copy(alpha = 0.25f))
                        drawPath(middle, colors.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    }
                    for (range in spec.approximate) {
                        drawLine(colors.warning, Offset(x(range.first), height - 2.dp.toPx()), Offset(x(range.last), height - 2.dp.toPx()),
                            3.dp.toPx(), pathEffect = dash)
                    }
                    for (start in replay.segmentStarts) drawLine(colors.ink.copy(alpha = 0.5f), Offset(x(start), 0f), Offset(x(start), height), 1.dp.toPx())
                    drawText(high, topLeft = Offset(4.dp.toPx(), 2.dp.toPx()))
                    drawText(low, topLeft = Offset(4.dp.toPx(), height - low.size.height - 2.dp.toPx()))
                    // Moment numbers above the chart, as on the profile and the map.
                    for ((moment, text) in numbers) {
                        val centre = Offset(x(moment.timeNanos).coerceIn(radius, width - radius), -radius - 2.dp.toPx())
                        drawCircle(colors.accent, radius, centre)
                        drawText(text, topLeft = Offset(centre.x - text.size.width / 2f, centre.y - text.size.height / 2f))
                        drawLine(colors.accent, Offset(x(moment.timeNanos), 0f), Offset(x(moment.timeNanos), height), 1.dp.toPx())
                    }
                    // The shared cursor.
                    val head = x(playhead)
                    drawLine(colors.ink, Offset(head, 0f), Offset(head, height), 2.dp.toPx())
                    val value = spec.runs.firstNotNullOfOrNull { it.at(playhead) }
                    if (value != null) drawCircle(colors.ink, 4.dp.toPx(), Offset(head, y(value)))
                }
            })
        Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
    }
}
