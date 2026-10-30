package com.example.cvkegps.tracking

import android.content.res.Resources
import android.location.Location
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.cvkegps.R
import com.example.cvkegps.location.AndroidSeaLevel
import com.example.cvkegps.location.FixSource
import com.example.cvkegps.location.TrackSample
import com.example.cvkegps.location.seaLevelAltitudeSupported
import com.example.cvkegps.settings.AltitudeReference
import com.example.cvkegps.ui.DisplayFormat
import com.example.cvkegps.ui.LocalDisplayChoices
import com.example.cvkegps.ui.rememberDisplayFormat
import com.example.cvkegps.ui.theme.CvkeTheme
import com.example.cvkegps.ui.theme.Size
import com.example.cvkegps.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Replay speeds. The trip plays this many times faster than it was recorded. */
private val RATES = intArrayOf(1, 4, 16, 64)

/** The playhead moves at most 10 times each second, so the map and the profile do not redraw for each frame. */
private const val TICK_MILLIS = 100L

/** From this text scale, details show one chart at a time. Android offers up to 2. */
internal const val LARGE_TEXT_SCALE = 1.5f

/**
 * The replay mode of a saved trip. The saved trip screen keeps its map and changes its pane, so the map does not load again.
 *
 * The pane has two stops. At a glance: the values at the playhead, a speed profile that is also the scrubber, play controls,
 * and moment chips. Details: the map becomes a strip that follows the cursor, the moment chips stay, and three linked charts
 * show speed, height, and acceleration. A landscape phone and the largest text sizes show one chart with a selector.
 * The replay is calculated once, off the main thread, when the mode starts.
 */
internal class ReplayMode(
    val load: StoredRead<TripReplay?>,
    val player: ReplayPlayer?,
    val details: Boolean,
    val setDetails: (Boolean) -> Unit,
    val chart: ReplayChartKind,
    val setChart: (ReplayChartKind) -> Unit,
)

/**
 * The replay state of trip [id]. The playhead, the play state, the speed, and the stop stay after a rotation and while the
 * mode is off. Leaving the mode pauses the replay. Null while the mode is off.
 */
@Composable
internal fun rememberReplayMode(actions: TrackingActions, id: String, active: Boolean): ReplayMode? {
    var playhead by rememberSaveable(id) { mutableLongStateOf(0L) }
    var playing by rememberSaveable(id) { mutableStateOf(false) }
    var rate by rememberSaveable(id) { mutableIntStateOf(0) }
    var details by rememberSaveable(id) { mutableStateOf(false) }
    var chart by rememberSaveable(id) { mutableStateOf(ReplayChartKind.Speed) }
    // The real time of the newest playhead step. The map continues from it until the next step.
    var stepNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) { if (!active) { playing = false; details = false } }
    if (!active) return null
    val load by remember(id) { actions.vm.replay(id) }.collectAsStateWithLifecycle(StoredRead.Loading)
    val replay = load.ready
    if (replay != null) PlayClock(replay, playing, RATES[rate], { playhead }) { time, ended ->
        playhead = time
        stepNanos = SystemClock.elapsedRealtimeNanos()
        if (ended) playing = false
    }
    val offset = rememberSeaLevelOffset(replay, LocalDisplayChoices.current.altitude)
    val player = replay?.let {
        val move = { time: Long -> playhead = time.coerceIn(0, it.durationNanos); stepNanos = SystemClock.elapsedRealtimeNanos() }
        ReplayPlayer(it, playhead, playing, RATES[rate], offset.first, offset.second, stepNanos,
            seek = move,
            pauseAt = { time -> playing = false; move(time) },
            toggle = {
                if (!playing && playhead >= it.durationNanos) move(0)
                stepNanos = SystemClock.elapsedRealtimeNanos()
                playing = !playing
            },
            nextRate = { rate = (rate + 1) % RATES.size })
    }
    return ReplayMode(load, player, details, { details = it }, chart, { chart = it })
}

/** The replay and its playback state. The screen owns the state, so it stays after a rotation. */
internal class ReplayPlayer(
    val replay: TripReplay,
    val playhead: Long,
    val playing: Boolean,
    val rate: Int,
    /** Subtract it from a replay height to get a height above [heightReference]. */
    val heightOffset: Double,
    val heightReference: AltitudeReference,
    /** The real time, in elapsed nanoseconds, when the playhead last moved. */
    val stepNanos: Long,
    val seek: (Long) -> Unit,
    val pauseAt: (Long) -> Unit,
    val toggle: () -> Unit,
    val nextRate: () -> Unit,
) {
    val values = replay.valuesAt(playhead)
}

/**
 * Moves the playhead while the replay plays and the screen is in front. Nothing runs while it is paused or hidden.
 * The real elapsed time sets the step, so a late tick does not slow the replay.
 */
@Composable
private fun PlayClock(replay: TripReplay, playing: Boolean, rate: Int, playhead: () -> Long, onTick: (Long, Boolean) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(playhead)
    val tick by rememberUpdatedState(onTick)
    LaunchedEffect(replay, playing, rate, lifecycle) {
        if (!playing) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = SystemClock.elapsedRealtimeNanos()
            while (true) {
                delay(TICK_MILLIS)
                val now = SystemClock.elapsedRealtimeNanos()
                val next = (current() + (now - last) * rate).coerceAtMost(replay.durationNanos)
                last = now
                val ended = next >= replay.durationNanos
                tick(next, ended)
                if (ended) break
            }
        }
    }
}

/**
 * The replay time for the map. While the replay plays, the map moves on each display frame between two playhead steps,
 * so the position marker and the camera move evenly. The values, the profile, and the charts still change 10 times
 * each second. Nothing runs while the replay is paused or the screen is hidden.
 */
@Composable
private fun rememberMapTime(player: ReplayPlayer): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val time by produceState(player.playhead, player.playhead, player.playing, player.rate, player.stepNanos, lifecycle) {
        value = player.playhead
        if (!player.playing) return@produceState
        // At most two steps ahead, so a late step does not let the map run far ahead of the values.
        val limit = minOf(player.replay.durationNanos, player.playhead + TICK_MILLIS * 2_000_000L * player.rate)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) withFrameNanos {
                value = (player.playhead + (SystemClock.elapsedRealtimeNanos() - player.stepNanos) * player.rate).coerceIn(player.playhead, limit)
            }
        }
    }
    return time
}

/**
 * The saved trip map. In replay mode the same map also shows the numbered moments, the position at the playhead, and the
 * played part of the route, and it follows the playhead.
 */
@Composable
internal fun TripMap(
    savedLines: List<List<TrackSample>>?, mode: ReplayMode?, modifier: Modifier, insets: WindowInsets, onBack: (() -> Unit)?,
) {
    val player = mode?.player
    val replay = player?.replay
    val format = rememberDisplayFormat()
    val names = momentNames()
    val moments = remember(replay, format, player?.heightOffset) {
        replay?.moments.orEmpty().mapNotNull { moment ->
            val latitude = moment.latitudeRadians ?: return@mapNotNull null
            val longitude = moment.longitudeRadians ?: return@mapNotNull null
            MapMoment(moment.number, latitude, longitude, names.map(moment.number, moment.kind, momentValue(moment, player, format)))
        }
    }
    val time = player?.let { rememberMapTime(it) }
    val values = if (player == null || time == null) null else if (time == player.playhead) player.values else player.replay.valuesAt(time)
    // The position on the route line, with the direction of travel turned evenly between fixes.
    // In a GPS gap there is no position, so the map shows no marker.
    val position = values?.fix?.let { fix ->
        val latitude = values.latitudeRadians ?: return@let null
        val longitude = values.longitudeRadians ?: return@let null
        LivePosition(fix.copy(latitudeRadians = latitude, longitudeRadians = longitude, bearingRadians = values.bearingRadians), fresh = true)
    }
    // The route after the playhead is muted. The map changes a line only when the playhead passes one of its points.
    val played = if (replay == null || time == null) null else replay.lines.indices.map { replay.playedPoints(it, time) }
    // One map for both modes. While the replay is calculated, the map keeps the saved route.
    MapSurface(replay?.lines ?: savedLines.orEmpty(), modifier, routeReady = replay != null || savedLines != null, insets = insets,
        onBack = onBack, position = position, replay = mode != null, moments = moments, played = played)
}

/** The replay pane: the loading state, then the glance stop or the details. */
@Composable
internal fun ColumnScope.ReplayPane(actions: TrackingActions, mode: ReplayMode, single: Boolean, chartHeight: Dp) {
    val load = mode.load
    val replay = load.ready
    when {
        load is StoredRead.Failed -> ReadError(actions.vm::retryReads)
        load is StoredRead.Loading -> Text(stringResource(R.string.replay_loading), Modifier.testTag("replay_loading"))
        replay == null || replay.fixes.isEmpty() -> Text(stringResource(R.string.replay_none))
    }
    val player = mode.player ?: return
    if (mode.details) ReplayDetails(player, mode.chart.takeIf { single }, mode.setChart, chartHeight) else ReplayPanel(player)
}

/**
 * The handle of the panel under the map. A drag up shows the charts, a drag down shows the map again.
 * The button does the same, so a drag is not the only way.
 */
@Composable
internal fun SheetHandle(details: Boolean, onChange: (Boolean) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val handleLabel = stringResource(if (details) R.string.replay_show_map else R.string.replay_show_charts)
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.s2), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).heightIn(min = Size.touch).testTag("replay_handle")
            .pointerInput(Unit) {
                var travel = 0f
                detectVerticalDragGestures(onDragStart = { travel = 0f }, onDragEnd = {
                    if (travel < -24.dp.toPx()) change(true) else if (travel > 24.dp.toPx()) change(false)
                }) { _, amount -> travel += amount }
            }
            .semantics { contentDescription = handleLabel; onClick(label = handleLabel) { change(!details); true } },
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).background(CvkeTheme.colors.inkMuted.copy(alpha = 0.6f), CircleShape))
        }
        StopButton(details) { change(!details) }
    }
}

/** Changes between the glance stop and the charts. */
@Composable
internal fun StopButton(details: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = Size.touch).testTag("replay_stop")) {
        ButtonContent(if (details) R.drawable.ic_route else R.drawable.ic_chart, if (details) R.string.replay_map else R.string.replay_charts)
    }
}

/**
 * Details: the values at the cursor, the controls, the moment chips, and the linked charts. With [chart], one chart shows
 * with a selector. A moment chip then also selects the chart of that moment.
 */
@Composable
private fun ReplayDetails(player: ReplayPlayer, chart: ReplayChartKind?, onChart: (ReplayChartKind) -> Unit, chartHeight: Dp) {
    val format = rememberDisplayFormat()
    val replay = player.replay
    ReplayValues(player, format)
    ReplayControls(player)
    MomentChips(player, format) { moment -> if (chart != null) onChart(moment.kind.chart) }
    ReplayCharts(replay, player.playhead, format, player.heightOffset,
        if (player.heightReference == AltitudeReference.SeaLevel) R.string.trip_altitude else R.string.trip_gps_height,
        timelineState(player, format), player.seek, player.pauseAt, chart, onChart, chartHeight)
    if (!replay.accelerationFromSensors && replay.acceleration.isNotEmpty()) {
        Text(stringResource(R.string.replay_acceleration_gps), style = MaterialTheme.typography.bodySmall, color = CvkeTheme.colors.inkMuted)
    }
}

/** The time and the speed at the playhead, for a screen reader. */
@Composable
private fun timelineState(player: ReplayPlayer, format: DisplayFormat): String =
    stringResource(R.string.replay_timeline_state, format.duration(player.playhead), format.duration(player.replay.durationNanos),
        player.values.speedMetersPerSecond?.let(format::spokenSpeed) ?: stringResource(
            if (player.values.fix == null) R.string.replay_no_gps else R.string.replay_approximate))

/** The values at the playhead, the speed profile, the controls, and the moment chips. */
@Composable
private fun ColumnScope.ReplayPanel(player: ReplayPlayer) {
    val format = rememberDisplayFormat()
    val replay = player.replay
    ReplayValues(player, format)
    SpeedProfile(player, format)
    ReplayControls(player)
    MomentChips(player, format)
    if (!replay.accelerationFromSensors && replay.acceleration.isNotEmpty()) {
        Text(stringResource(R.string.replay_acceleration_gps), style = MaterialTheme.typography.bodySmall, color = CvkeTheme.colors.inkMuted)
    }
}

@Composable
private fun ReplayValues(player: ReplayPlayer, format: DisplayFormat) {
    val values = player.values
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Metric(R.string.replay_time_label, stringResource(R.string.replay_time, format.clock(player.playhead), format.clock(player.replay.durationNanos)),
            tag = "replay_time")
        // A network position or a gap has no speed. The text says why.
        Metric(R.string.replay_speed_label, when {
            values.fix == null -> stringResource(R.string.replay_no_gps)
            values.fix.source == FixSource.Network -> stringResource(R.string.replay_approximate)
            else -> values.speedMetersPerSecond?.let(format::speed)
        }, tag = "replay_speed")
        if (player.replay.heightSource != HeightSource.None) {
            Metric(if (player.heightReference == AltitudeReference.SeaLevel) R.string.trip_altitude else R.string.trip_gps_height,
                values.heightMeters?.let { format.altitude(it - player.heightOffset) }, tag = "replay_height")
        }
        if (player.replay.acceleration.isNotEmpty()) {
            Metric(R.string.replay_acceleration_label, values.accelerationMetersPerSecondSquared?.let { format.acceleration(it.toDouble()) },
                tag = "replay_acceleration")
        }
    }
}

/**
 * The difference between the GPS height and sea level near the start of the trip, and the reference that applies.
 * The geoid changes slowly, so one value serves a trip. Before Android 14 the screen shows the GPS height.
 */
@Composable
private fun rememberSeaLevelOffset(replay: TripReplay?, reference: AltitudeReference): Pair<Double, AltitudeReference> {
    val context = LocalContext.current
    val wanted = reference == AltitudeReference.SeaLevel && seaLevelAltitudeSupported
    val offset by produceState<Double?>(null, replay, wanted) {
        if (replay == null || !wanted || !seaLevelAltitudeSupported) return@produceState
        val sample = replay.fixes.firstOrNull { it.sample.altitudeMeters != null && it.sample.usablePosition }?.sample ?: return@produceState
        val height = sample.altitudeMeters ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val location = Location("replay").apply {
                latitude = Math.toDegrees(sample.latitudeRadians)
                longitude = Math.toDegrees(sample.longitudeRadians)
                altitude = height
                sample.verticalAccuracyMeters?.let { verticalAccuracyMeters = it }
            }
            AndroidSeaLevel(context).altitude(location)?.let { height - it }
        }
    }
    return offset?.let { it to AltitudeReference.SeaLevel } ?: (0.0 to AltitudeReference.Ellipsoid)
}

/**
 * The trip's speed over time. It is also the scrubber: a tap or a horizontal drag moves the playhead.
 * A hatched band is a GPS gap. A dashed warning line marks approximate positions. The numbers are the moments.
 * For a screen reader it is one slider with Next and Previous moment actions.
 */
@Composable
private fun SpeedProfile(player: ReplayPlayer, format: DisplayFormat) {
    val replay = player.replay
    val colors = CvkeTheme.colors
    val measurer = rememberTextMeasurer()
    val duration = replay.durationNanos.coerceAtLeast(1)
    val maximum = remember(replay) { replay.speed.maxOfOrNull { run -> run.values.max() }?.coerceAtLeast(1f) ?: 1f }
    val seek by rememberUpdatedState(player.seek)
    val state = timelineState(player, format)
    val nextLabel = stringResource(R.string.replay_next)
    val previousLabel = stringResource(R.string.replay_previous)
    val description = stringResource(R.string.replay_timeline)
    val numberStyle = TextStyle(color = colors.accentInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    // The moment numbers grow with the text size, so the space above the profile grows with them.
    val numberSize = remember(measurer, numberStyle) { measurer.measure("8", numberStyle).size }
    val density = LocalDensity.current
    val radiusDp = with(density) { maxOf(8.dp.toPx(), numberSize.height / 2f + 2.dp.toPx()).toDp() }
    Canvas(Modifier.fillMaxWidth().height(52.dp + radiusDp * 2).testTag("replay_profile")
        .pointerInput(duration) {
            detectTapGestures { seek((it.x / size.width * duration).toLong()) }
        }
        .pointerInput(duration) {
            detectHorizontalDragGestures { change, _ -> seek((change.position.x / size.width * duration).toLong()) }
        }
        .semantics {
            contentDescription = description
            stateDescription = state
            progressBarRangeInfo = ProgressBarRangeInfo(player.playhead / 1e9f, 0f..duration / 1e9f)
            setProgress { seconds -> seek((seconds * 1e9).toLong()); true }
            customActions = listOf(
                CustomAccessibilityAction(nextLabel) { replay.nextMoment(player.playhead)?.let { player.pauseAt(it.timeNanos) } != null },
                CustomAccessibilityAction(previousLabel) { replay.previousMoment(player.playhead)?.let { player.pauseAt(it.timeNanos) } != null },
            )
        }) {
        val radius = radiusDp.toPx()
        val top = radius * 2 + 4.dp.toPx()
        val graph = size.height - top
        fun x(time: Long) = time.toFloat() / duration * size.width
        drawRoundRect(colors.surfaceSunken, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, graph),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        // GPS gaps: diagonal hatching, so the gap does not depend on colour.
        val hatch = 6.dp.toPx()
        for (gap in replay.gpsGaps) {
            val left = x(gap.first)
            val right = x(gap.last)
            clipRect(left, top, right, size.height) {
                var line = left - graph
                while (line < right) {
                    drawLine(colors.inkMuted.copy(alpha = 0.45f), Offset(line, size.height), Offset(line + graph, top), 1.dp.toPx())
                    line += hatch
                }
            }
        }
        // The speed, one path for each run. Gaps stay open.
        for (run in replay.speed) {
            if (run.times.isEmpty()) continue
            val path = Path()
            run.times.forEachIndexed { i, time ->
                val point = Offset(x(time), size.height - run.values[i] / maximum * graph * 0.9f)
                if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            val area = Path().apply {
                addPath(path)
                lineTo(x(run.times.last()), size.height)
                lineTo(x(run.times.first()), size.height)
                close()
            }
            drawPath(area, colors.accent.copy(alpha = 0.18f))
            drawPath(path, colors.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        // Approximate positions: a dashed warning line at the base.
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        for (range in replay.approximate) {
            drawLine(colors.warning, Offset(x(range.first), size.height - 2.dp.toPx()), Offset(x(range.last), size.height - 2.dp.toPx()),
                3.dp.toPx(), pathEffect = dash)
        }
        // A pause between segments is not replayed. A thin line marks where the next segment starts.
        for (start in replay.segmentStarts) drawLine(colors.ink.copy(alpha = 0.5f), Offset(x(start), top), Offset(x(start), size.height), 1.dp.toPx())
        // Numbered moments above the profile, with the same numbers as the map and the chips.
        for (moment in replay.moments) {
            val centre = Offset(x(moment.timeNanos).coerceIn(radius, size.width - radius), radius)
            drawCircle(colors.accent, radius, centre)
            val text = measurer.measure(moment.number.toString(), numberStyle)
            drawText(text, topLeft = Offset(centre.x - text.size.width / 2f, centre.y - text.size.height / 2f))
            drawLine(colors.accent, Offset(x(moment.timeNanos), top), Offset(x(moment.timeNanos), size.height), 1.dp.toPx())
        }
        // The playhead.
        val head = x(player.playhead)
        drawLine(colors.ink, Offset(head, top - 2.dp.toPx()), Offset(head, size.height), 2.dp.toPx())
        drawCircle(colors.ink, 5.dp.toPx(), Offset(head, top))
    }
}

@Composable
private fun ReplayControls(player: ReplayPlayer) {
    val replay = player.replay
    val format = rememberDisplayFormat()
    val rateText = stringResource(R.string.replay_rate, format.whole(player.rate))
    val rateSpoken = stringResource(R.string.replay_rate_spoken, format.whole(player.rate))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        val previous = replay.previousMoment(player.playhead)
        IconButton(onClick = { previous?.let { player.pauseAt(it.timeNanos) } }, enabled = previous != null,
            modifier = Modifier.testTag("replay_previous")) {
            Icon(painterResource(R.drawable.ic_skip_back), contentDescription = stringResource(R.string.replay_previous))
        }
        // Play and pause is the one primary button.
        Button(onClick = player.toggle, modifier = Modifier.weight(1f).heightIn(min = Size.touch).testTag("replay_play")) {
            ButtonContent(if (player.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (player.playing) R.string.replay_pause else R.string.replay_play)
        }
        val next = replay.nextMoment(player.playhead)
        IconButton(onClick = { next?.let { player.pauseAt(it.timeNanos) } }, enabled = next != null, modifier = Modifier.testTag("replay_next")) {
            Icon(painterResource(R.drawable.ic_skip_forward), contentDescription = stringResource(R.string.replay_next))
        }
        OutlinedButton(onClick = player.nextRate, modifier = Modifier.heightIn(min = Size.touch).testTag("replay_rate")
            .semantics { contentDescription = rateSpoken }) {
            Text(rateText)
        }
    }
}

/** One chip for each moment, with its number, name, and value. A chip moves the playhead there and pauses. */
@Composable
private fun MomentChips(player: ReplayPlayer, format: DisplayFormat, onPick: (ReplayMoment) -> Unit = {}) {
    val names = momentNames()
    val moments = player.replay.moments
    if (moments.isEmpty()) {
        Text(stringResource(R.string.replay_moments_none), style = MaterialTheme.typography.bodySmall, color = CvkeTheme.colors.inkMuted)
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("replay_moments"),
        horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        for (moment in moments) {
            val selected = player.playhead == moment.timeNanos
            FilterChip(selected = selected, onClick = { player.pauseAt(moment.timeNanos); onPick(moment) },
                label = { Text(names.chip(moment.kind, momentValue(moment, player, format))) },
                leadingIcon = {
                    // The number in an accent circle, as on the map and the profile.
                    Surface(shape = CircleShape, color = CvkeTheme.colors.accent) {
                        // The circle grows with the text size.
                        Box(Modifier.sizeIn(minWidth = 20.dp, minHeight = 20.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                            Text(format.whole(moment.number), color = CvkeTheme.colors.accentInk, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent),
                modifier = Modifier.heightIn(min = Size.touch).testTag("moment_${moment.kind.name}"))
        }
    }
}

/** The moment texts in the language of the configuration. */
private class MomentNames(private val resources: Resources) {
    /** The chip shows the number in its circle, so the text has the name and the value only. */
    fun chip(kind: MomentKind, value: String) = resources.getString(R.string.moment_chip, resources.getString(kind.title), value)
    fun map(number: Int, kind: MomentKind, value: String) = resources.getString(R.string.moment_map, number, resources.getString(kind.title), value)
}

@Composable
private fun momentNames(): MomentNames {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { MomentNames(resources) }
}

/** The chart that shows a moment. */
private val MomentKind.chart get() = when (this) {
    MomentKind.Acceleration, MomentKind.Braking -> ReplayChartKind.Acceleration
    MomentKind.FastestSpeed -> ReplayChartKind.Speed
    MomentKind.HighestPoint -> ReplayChartKind.Height
}

@get:StringRes private val MomentKind.title get() = when (this) {
    MomentKind.Acceleration -> R.string.moment_acceleration
    MomentKind.Braking -> R.string.moment_braking
    MomentKind.FastestSpeed -> R.string.moment_fastest
    MomentKind.HighestPoint -> R.string.moment_highest
}

/** The moment value in display units. Braking shows as a positive deceleration. */
private fun momentValue(moment: ReplayMoment, player: ReplayPlayer?, format: DisplayFormat): String = when (moment.kind) {
    MomentKind.FastestSpeed -> format.speed(moment.value.toFloat())
    MomentKind.HighestPoint -> format.altitude(moment.value - (player?.heightOffset ?: 0.0))
    MomentKind.Acceleration, MomentKind.Braking -> format.acceleration(kotlin.math.abs(moment.value))
}
