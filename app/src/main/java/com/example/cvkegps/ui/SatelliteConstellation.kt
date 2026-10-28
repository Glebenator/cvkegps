package com.example.cvkegps.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.cvkegps.R
import com.example.cvkegps.location.SatelliteSignal
import com.example.cvkegps.location.SatelliteSystem
import com.example.cvkegps.location.distinctSatellites
import com.example.cvkegps.location.signalStrength
import com.example.cvkegps.location.skyPosition
import com.example.cvkegps.location.wholeDegrees
import java.text.NumberFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val TICK_GAP = 3.dp
private val TICK_LENGTH = 6.dp
private val MARKER_RADIUS = 5.dp

/** The home ring: horizon and overhead as fractions of the drawing radius. */
private const val RING_HORIZON = 0.94f
private const val RING_ZENITH = 0.72f

/**
 * An abstract satellite ring around the speed. Reported direction and elevation set each position, and the ring
 * turns with the phone. A small N marks north. Brighter markers have stronger signals. Null satellites mean no
 * report: only the rings are drawn.
 */
@Composable
internal fun SatelliteConstellation(satellites: List<SatelliteSignal>?, heading: PhoneHeading, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val measurer = rememberTextMeasurer()
    val northStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
    val northText = stringResource(R.string.sky_north)
    val north = remember(measurer, northStyle, northText) { measurer.measure(northText, northStyle) }
    val markers = remember(satellites) { satellites?.distinctSatellites()?.sortedBy { signalStrength(it.cn0DbHz) } }
    val colors = remember(markers, scheme) { markers?.map { satelliteColor(it.system, scheme) } }
    val turn = rememberSkyTurn(heading)
    val description = stringResource(R.string.sky_ring_description)
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val state = when {
        satellites == null -> null
        heading is PhoneHeading.Current -> stringResource(R.string.sky_heading_current, format.format(wholeDegrees(heading.trueRadians)))
        heading == PhoneHeading.Starting -> stringResource(R.string.sky_heading_starting)
        heading == PhoneHeading.WaitingForPosition -> stringResource(R.string.sky_heading_waiting_position)
        else -> stringResource(R.string.sky_north_up)
    }
    Canvas(modifier.semantics {
        contentDescription = description
        if (state != null) stateDescription = state
    }) {
        val line = 1.dp.toPx()
        val labelSize = maxOf(north.size.width, north.size.height).toFloat()
        val horizon = minOf((size.minDimension / 2 - 10.dp.toPx()) * RING_HORIZON, size.minDimension / 2 - labelSize - 6.dp.toPx())
        if (horizon <= 0f) return@Canvas
        drawCircle(scheme.outlineVariant.copy(alpha = 0.35f), horizon, style = Stroke(line))
        drawCircle(scheme.outlineVariant.copy(alpha = 0.18f), horizon * RING_ZENITH / RING_HORIZON, style = Stroke(line))
        if (markers == null || colors == null) return@Canvas
        val turnRadians = Math.toRadians(turn.value.toDouble())
        val points = markers.map { signal ->
            skyPosition(signal.azimuthRadians, signal.elevationRadians, turnRadians, (RING_ZENITH / RING_HORIZON).toDouble())
                ?.let { center + Offset(it.x.toFloat(), it.y.toFloat()) * horizon }
        }
        fun alpha(index: Int) = markerAlpha(markers[index])
        // Short links join satellites from the same system. They have no measurement meaning.
        markers.indices.filter { points[it] != null }.groupBy { markers[it].system }.values.forEach { group ->
            group.sortedBy { markers[it].azimuthRadians }.zipWithNext().forEach { (a, b) ->
                val from = points[a]!!
                val to = points[b]!!
                if ((from - to).getDistance() < horizon * 0.9f) {
                    drawLine(colors[a].copy(alpha = minOf(alpha(a), alpha(b)) * 0.2f), from, to, line)
                }
            }
        }
        markers.forEachIndexed { index, signal ->
            points[index]?.let { drawSignal(signal, it, 3.5.dp.toPx(), colors[index], dark) }
        }
        // A small N turns with the satellites. It marks north and adds no other direction text.
        val toward = Offset(sin(-turnRadians).toFloat(), -cos(-turnRadians).toFloat())
        val position = center + toward * (horizon + 4.dp.toPx() + labelSize / 2)
        drawText(north, scheme.primary.copy(alpha = 0.8f), position - Offset(north.size.width / 2f, north.size.height / 2f))
        // Fade links under the speed digits without reducing their contrast.
        val clear = horizon * 0.68f / RING_HORIZON
        drawCircle(Brush.radialGradient(listOf(scheme.surface, scheme.surface.copy(alpha = 0f)), center, clear), clear)
    }
}

/** Space outside the horizon for the direction ticks and labels. */
@Composable
private fun skyLabelSpace(): Dp {
    val labels = skyDirectionLabels()
    return with(LocalDensity.current) { labels.maxOf { maxOf(it.size.width, it.size.height) }.toDp() } +
        TICK_GAP * 2 + TICK_LENGTH
}

/**
 * Plot reported angles on a flat sky projection with overhead at the centre. Markers, ticks, and direction labels
 * turn together with the phone. Brighter markers have stronger signals.
 */
@Composable
internal fun SatelliteSkyPlot(
    satellites: List<SatelliteSignal>,
    heading: PhoneHeading,
    description: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val labels = skyDirectionLabels()
    val labelSpace = skyLabelSpace()
    // Draw strong signals last so that they stay on top.
    val markers = remember(satellites) { satellites.distinctSatellites().sortedBy { signalStrength(it.cn0DbHz) } }
    val colors = remember(markers, scheme) { markers.map { satelliteColor(it.system, scheme) } }
    val turn = rememberSkyTurn(heading)
    Canvas(modifier.semantics { contentDescription = description }) {
        val line = 1.dp.toPx()
        val gap = TICK_GAP.toPx()
        val tick = TICK_LENGTH.toPx()
        val horizon = size.minDimension / 2 - labelSpace.toPx()
        if (horizon <= 0f) return@Canvas
        val turnRadians = Math.toRadians(turn.value.toDouble())
        fun direction(azimuthDegrees: Int): Offset {
            val angle = Math.toRadians(azimuthDegrees.toDouble()) - turnRadians
            return Offset(sin(angle).toFloat(), -cos(angle).toFloat())
        }

        drawCircle(scheme.primary.copy(alpha = if (dark) 0.1f else 0.06f), horizon)
        val dots = PathEffect.dashPathEffect(floatArrayOf(0f, 5.dp.toPx()))
        listOf(30f, 60f).forEach { elevation ->
            drawCircle(scheme.outline, horizon * (1 - elevation / 90f),
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = dots))
        }
        drawCircle(scheme.outline.copy(alpha = 0.7f), horizon, style = Stroke(line))
        drawCircle(scheme.outline, 2.dp.toPx(), center)
        for (quarter in 0 until 4) {
            val toward = direction(quarter * 90)
            drawLine(scheme.outlineVariant, center, center + toward * horizon, line)
        }

        // A bezel tick every 10 degrees; longer ticks every 30 degrees. North uses the primary colour.
        for (degrees in 0 until 360 step 10) {
            val major = degrees % 30 == 0
            val toward = direction(degrees)
            drawLine(
                when { degrees == 0 -> scheme.primary; major -> scheme.outline; else -> scheme.outlineVariant },
                center + toward * (horizon + gap), center + toward * (horizon + gap + if (major) tick else tick / 2),
                if (major) line * 1.5f else line, cap = StrokeCap.Round,
            )
        }
        val labelRadius = horizon + gap * 2 + tick + (labelSpace.toPx() - gap * 2 - tick) / 2
        labels.forEachIndexed { index, label ->
            val position = center + direction(index * 90) * labelRadius
            drawText(label, if (index == 0) scheme.primary else scheme.onSurfaceVariant,
                position - Offset(label.size.width / 2f, label.size.height / 2f))
        }
        // The fixed index at the top marks the direction of the screen's upper edge.
        if (heading is PhoneHeading.Current) {
            val tip = center.y - horizon - gap / 2
            val base = center.y - horizon - gap - tick - 1.dp.toPx()
            drawPath(Path().apply {
                moveTo(center.x, tip)
                lineTo(center.x - tick * 0.7f, base)
                lineTo(center.x + tick * 0.7f, base)
                close()
            }, scheme.primary)
        }

        markers.forEachIndexed { index, signal ->
            val position = skyPosition(signal.azimuthRadians, signal.elevationRadians, turnRadians) ?: return@forEachIndexed
            drawSignal(signal, center + Offset(position.x.toFloat(), position.y.toFloat()) * horizon,
                MARKER_RADIUS.toPx(), colors[index], dark)
        }
    }
}

/** Turn the short way to the phone heading. The spring stops when the heading stops changing. */
@Composable
private fun rememberSkyTurn(heading: PhoneHeading): Animatable<Float, AnimationVector1D> {
    val turn = remember { Animatable(0f) }
    val aligned = remember { mutableStateOf(false) }
    LaunchedEffect(heading) {
        val target = when (heading) {
            // Keep the last direction until the compass reports.
            PhoneHeading.Starting -> return@LaunchedEffect
            is PhoneHeading.Current -> Math.toDegrees(heading.trueRadians).toFloat()
            PhoneHeading.Unavailable, PhoneHeading.WaitingForPosition -> 0f
        }
        val delta = ((target - turn.value) % 360f + 540f) % 360f - 180f
        if (aligned.value) turn.animateTo(turn.value + delta, spring(stiffness = Spring.StiffnessMedium))
        else { turn.snapTo(target); aligned.value = true }
    }
    return turn
}

private fun markerAlpha(signal: SatelliteSignal) = 0.3f + 0.7f * signalStrength(signal.cn0DbHz)

/** Stronger signals are brighter and have a larger, brighter glow. */
private fun DrawScope.drawSignal(signal: SatelliteSignal, point: Offset, radius: Float, color: Color, dark: Boolean) {
    val strength = signalStrength(signal.cn0DbHz)
    if (strength > 0f) {
        val glow = radius * (1.6f + 1.6f * strength)
        drawCircle(Brush.radialGradient(
            listOf(color.copy(alpha = strength * if (dark) 0.55f else 0.3f), color.copy(alpha = 0f)), point, glow,
        ), glow, point)
    }
    drawSatellite(signal.system, point, radius, color.copy(alpha = markerAlpha(signal)), signal.usedInFix)
}

@Composable
private fun skyDirectionLabels(): List<TextLayoutResult> {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
    val texts = listOf(R.string.sky_north, R.string.sky_east, R.string.sky_south, R.string.sky_west).map { stringResource(it) }
    return remember(measurer, style, texts) { texts.map { measurer.measure(it, style) } }
}

@Composable
internal fun SatelliteMarker(system: SatelliteSystem, used: Boolean) {
    val color = satelliteColor(system, MaterialTheme.colorScheme)
    Canvas(Modifier.size(16.dp)) {
        drawSatellite(system, center, 5.dp.toPx(), color, used)
    }
}

// The design has one accent. GPS uses it; the other systems use neutral steps, and each system also has its own shape.
private fun satelliteColor(system: SatelliteSystem, scheme: ColorScheme): Color = when (system) {
    SatelliteSystem.Gps, SatelliteSystem.Qzss -> scheme.primary
    SatelliteSystem.Glonass, SatelliteSystem.Beidou -> scheme.onSurface
    SatelliteSystem.Galileo, SatelliteSystem.Irnss -> scheme.onSurfaceVariant
    SatelliteSystem.Sbas, SatelliteSystem.Unknown -> scheme.outline
}

private fun DrawScope.drawSatellite(system: SatelliteSystem, point: Offset, radius: Float, color: Color, used: Boolean) {
    val style = if (used) Fill else Stroke(1.5.dp.toPx())
    if (system == SatelliteSystem.Gps || system == SatelliteSystem.Unknown) {
        drawCircle(color, radius, point, style = style)
        if (system == SatelliteSystem.Unknown) drawCircle(color, radius * 0.35f, point, style = Stroke(1.dp.toPx()))
        return
    }
    if (system == SatelliteSystem.Sbas) {
        drawLine(color, point - Offset(radius, 0f), point + Offset(radius, 0f), 1.5.dp.toPx())
        drawLine(color, point - Offset(0f, radius), point + Offset(0f, radius), 1.5.dp.toPx())
        if (used) drawCircle(color, radius * 0.45f, point)
        return
    }
    val sides = when (system) {
        SatelliteSystem.Galileo -> 3
        SatelliteSystem.Qzss -> 5
        SatelliteSystem.Irnss -> 6
        else -> 4
    }
    val rotation = if (system == SatelliteSystem.Beidou) PI / 4 else 0.0
    val path = Path().apply {
        repeat(sides) { index ->
            val angle = index * 2 * PI / sides - PI / 2 + rotation
            val x = point.x + (cos(angle) * radius).toFloat()
            val y = point.y + (sin(angle) * radius).toFloat()
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    drawPath(path, color, style = style)
}
