package com.example.cvkegps.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.example.cvkegps.location.SatelliteSignal
import com.example.cvkegps.location.SatelliteSystem
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class AnimatedSatellite(
    val signal: SatelliteSignal,
    val position: State<Offset>,
    val opacity: State<Float>,
    val color: Color,
)

/** A decorative constellation around the speed value. It is not a sky map. */
@Composable
internal fun SatelliteConstellation(satellites: List<SatelliteSignal>, modifier: Modifier = Modifier) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val markers = satellites.map { satellite ->
        key(satellite.system, satellite.id) {
            // Keep the centre clear. Azimuth sets direction; elevation sets the outer band.
            // Invalid angles use a stable decorative position, without inventing a sky reading.
            val angle = satellite.azimuthRadians.takeIf { it.isFinite() }
                ?: ((satellite.id * 137.5 + satellite.system.ordinal * 31) * PI / 180)
            val elevation = satellite.elevationRadians.takeIf { it.isFinite() }
                ?.coerceIn(0.0, PI / 2) ?: 0.0
            val radius = 0.94 - elevation / (PI / 2) * 0.22
            val position = animateValueAsState(
                Offset((sin(angle) * radius).toFloat(), (-cos(angle) * radius).toFloat()),
                Offset.VectorConverter,
                animationSpec = if (animate) tween(650) else snap(),
                label = "satellite position",
            )
            val opacity = remember { Animatable(0f) }
            LaunchedEffect(satellite.usedInFix, animate) {
                val target = if (satellite.usedInFix) 0.85f else 0.32f
                if (animate) opacity.animateTo(target, tween(650)) else opacity.snapTo(target)
            }
            AnimatedSatellite(satellite, position, opacity.asState(), satelliteColor(satellite.system))
        }
    }
    Canvas(modifier) {
        val radius = size.minDimension / 2 - 10.dp.toPx()
        if (radius <= 0f) return@Canvas
        drawCircle(outline.copy(alpha = 0.35f), radius * 0.94f, style = Stroke(1.dp.toPx()))
        drawCircle(outline.copy(alpha = 0.18f), radius * 0.72f, style = Stroke(1.dp.toPx()))
        fun point(marker: AnimatedSatellite) = center + marker.position.value * radius
        // Short links join signals from the same system. They have no measurement meaning.
        markers.groupBy { it.signal.system }.values.forEach { group ->
            group.sortedBy { it.signal.azimuthRadians }.zipWithNext().forEach { (a, b) ->
                if ((point(a) - point(b)).getDistance() < radius * 0.9f) {
                    drawLine(a.color.copy(alpha = minOf(a.opacity.value, b.opacity.value) * 0.2f),
                        point(a), point(b), strokeWidth = 1.dp.toPx())
                }
            }
        }
        markers.forEach { marker ->
            val point = point(marker)
            val alpha = marker.opacity.value
            if (marker.signal.usedInFix) {
                drawCircle(marker.color.copy(alpha = alpha * 0.08f), 11.dp.toPx(), point)
            }
            drawSatellite(marker.signal.system, point, 3.5.dp.toPx(),
                marker.color.copy(alpha = alpha), marker.signal.usedInFix)
        }
        // Fade links under the speed digits without reducing their contrast.
        drawCircle(
            Brush.radialGradient(listOf(surface, surface.copy(alpha = 0f)), center, radius * 0.68f),
            radius * 0.68f,
        )
    }
}

@Composable
internal fun SatelliteMarker(system: SatelliteSystem, used: Boolean) {
    val color = satelliteColor(system)
    Canvas(Modifier.size(16.dp)) {
        drawSatellite(system, center, 5.dp.toPx(), color, used)
    }
}

@Composable
private fun satelliteColor(system: SatelliteSystem): Color = when (system) {
    SatelliteSystem.Gps, SatelliteSystem.Qzss -> MaterialTheme.colorScheme.primary
    SatelliteSystem.Glonass, SatelliteSystem.Beidou -> MaterialTheme.colorScheme.tertiary
    SatelliteSystem.Galileo, SatelliteSystem.Irnss -> MaterialTheme.colorScheme.secondary
    SatelliteSystem.Sbas, SatelliteSystem.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun DrawScope.drawSatellite(system: SatelliteSystem, point: Offset, radius: Float, color: Color, used: Boolean) {
    val style = if (used) Fill else Stroke(1.dp.toPx())
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
