package com.example.cvkegps.ui.theme

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButtonColors
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.cvkegps.R

/** Secondary button: `surface` fill, 1 dp `border-control` outline, and `ink` text. */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = CvkeTheme.colors
    OutlinedButton(onClick, modifier, enabled,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.surface, contentColor = colors.ink),
        border = BorderStroke(1.dp, if (enabled) colors.borderControl else colors.border),
        contentPadding = contentPadding, content = content)
}

/** An 8 dp status dot. Always put text next to it, so colour is not the only cue. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(Space.s2).background(color, CircleShape))
}

/** A status as a dot and text in `ink`. */
@Composable
fun StatusText(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        StatusDot(color)
        Text(text, style = MaterialTheme.typography.labelMedium, color = CvkeTheme.colors.ink)
    }
}

/** The 32 dp `accent-subtle` tile at the start of a list row. */
@Composable
fun IconTile(@DrawableRes icon: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(Size.tile).background(CvkeTheme.colors.accentSubtle, RoundedCornerShape(Radius.md)),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(Size.iconMd), tint = CvkeTheme.colors.accent)
    }
}

/** A persistent problem: a `danger-subtle` banner with an icon, the message in `ink`, and an optional action below it. */
@Composable
fun ProblemBanner(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Surface(modifier.fillMaxWidth(), color = CvkeTheme.colors.dangerSubtle, contentColor = CvkeTheme.colors.ink,
        shape = RoundedCornerShape(Radius.md)) {
        androidx.compose.foundation.layout.Column(Modifier.padding(Space.s3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Icon(painterResource(R.drawable.ic_alert), contentDescription = null, Modifier.size(Size.iconMd),
                    tint = CvkeTheme.colors.danger)
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            if (action != null) Box(Modifier.padding(start = Space.s6)) { action() }
        }
    }
}

/** Map buttons float over the map: `surface` with the overlay shadow, so they read on every map type. */
val mapButtonModifier: Modifier = Modifier.size(Size.touch).shadow(6.dp, CircleShape)

@Composable
fun mapButtonColors(): IconButtonColors = CvkeTheme.colors.let {
    IconButtonDefaults.filledTonalIconButtonColors(containerColor = it.surface, contentColor = it.ink)
}

@Composable
fun mapToggleColors(): IconToggleButtonColors = CvkeTheme.colors.let {
    IconButtonDefaults.filledTonalIconToggleButtonColors(containerColor = it.surface, contentColor = it.ink,
        checkedContainerColor = it.accentSubtle, checkedContentColor = it.accent)
}

/** The slice's distance from the disc, in 1/96 of the art width. At rest it is a quarter of the disc radius. */
private const val SLICE_REST = 5.5f

/**
 * The one brand loop: the slice drifts from 5 to 11 units from the disc and back, 2.4 s each way.
 * It stops when the art leaves the screen or the app is not visible, because no frames are drawn then.
 * With animations removed in the system settings, the slice stays at rest.
 */
@Composable
private fun sliceDrift(animated: Boolean): State<Float> {
    val context = LocalContext.current
    val reduced = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    if (!animated || reduced) return remember { mutableFloatStateOf(SLICE_REST) }
    return rememberInfiniteTransition(label = "slice").animateFloat(5f, 11f,
        infiniteRepeatable(tween(2_400, easing = EaseInOut), RepeatMode.Reverse), label = "slice drift")
}

/**
 * Brand art for empty states: a disc with a 70° slice cut out, its slice beside it, a half-disc, and two dots.
 * Flat fills only. It is decoration, so screen readers skip it. Only one art on a screen can be [animated].
 */
@Composable
fun SliceArt(modifier: Modifier = Modifier, animated: Boolean = false) {
    val colors = CvkeTheme.colors
    val drift = sliceDrift(animated)
    Canvas(modifier.size(96.dp, 64.dp)) {
        val unit = size.width / 96f
        val radius = 22 * unit
        val disc = Offset(26 * unit, 32 * unit)
        // The opening faces right. The slice floats beside it. Only the draw phase reads the drift, so the loop does not recompose.
        drawArc(colors.accent, 35f, 290f, useCenter = true, topLeft = disc - Offset(radius, radius), size = GeometrySize(radius * 2, radius * 2))
        val slice = disc + Offset(drift.value * unit, 0f)
        drawArc(colors.brandApricot, -35f, 70f, useCenter = true, topLeft = slice - Offset(radius, radius), size = GeometrySize(radius * 2, radius * 2))
        val half = 14 * unit
        val base = Offset(78 * unit, 58 * unit)
        drawArc(colors.brandApricotSubtle, 180f, 180f, useCenter = true, topLeft = base - Offset(half, half), size = GeometrySize(half * 2, half * 2))
        drawCircle(colors.brandApricot, 4 * unit, Offset(78 * unit, 52 * unit))
        drawCircle(colors.ink, 4 * unit, Offset(84 * unit, 12 * unit))
    }
}

/**
 * A single choice of a few short options, such as units: a `surface-sunken` pill track with segments at least 48 dp high.
 * The selected segment is `accent-subtle` with `accent` text. Screen readers hear each segment as a radio button.
 * [spoken] gives a full name where the visible label is an abbreviation, for example "kilometres per hour" for km/h.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    spoken: @Composable (T) -> String? = { null },
    tag: (T) -> String = { "" },
) {
    val colors = CvkeTheme.colors
    Row(modifier.fillMaxWidth().clip(CircleShape).background(colors.surfaceSunken).padding(Space.s1).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
        options.forEach { option ->
            val chosen = option == selected
            val name = spoken(option)
            Box(Modifier.weight(1f).heightIn(min = Size.touch).clip(CircleShape)
                .background(if (chosen) colors.accentSubtle else Color.Transparent)
                .selectable(chosen, role = Role.RadioButton) { onSelect(option) }
                .then(if (name != null) Modifier.semantics { contentDescription = name } else Modifier)
                .testTag(tag(option)),
                contentAlignment = Alignment.Center) {
                Text(label(option), Modifier.padding(horizontal = Space.s2, vertical = Space.s1),
                    style = MaterialTheme.typography.labelLarge, color = if (chosen) colors.accent else colors.ink,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * The inline "Saved" confirmation: a `success` check and the word, for 2 s after each change. [changes] counts the changes.
 * It fades in and out without movement, and screen readers announce it.
 */
@Composable
fun SavedNote(changes: Int, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(changes) {
        if (changes == 0) return@LaunchedEffect
        shown = true
        delay(2_000)
        shown = false
    }
    AnimatedVisibility(shown, modifier, enter = fadeIn(tween(Motion.ENTER_MILLIS, easing = Motion.standard)),
        exit = fadeOut(tween(Motion.EXIT_MILLIS, easing = Motion.standard))) {
        Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(Size.iconSm), tint = CvkeTheme.colors.success)
            Text(stringResource(R.string.settings_saved), style = MaterialTheme.typography.labelMedium, color = CvkeTheme.colors.inkMuted)
        }
    }
}
