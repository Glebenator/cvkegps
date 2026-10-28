package com.example.cvkegps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.cvkegps.R
import com.example.cvkegps.ui.theme.CvkeTheme
import com.example.cvkegps.ui.theme.Space
import com.example.cvkegps.location.POSITION_ACCURACY_EMPTY_METERS
import com.example.cvkegps.location.POSITION_ACCURACY_FULL_METERS
import com.example.cvkegps.location.POSITION_ACCURACY_REFERENCE_METERS
import com.example.cvkegps.location.positionAccuracyProgress
import java.text.NumberFormat
import kotlin.math.roundToInt

@Composable
internal fun PositionAccuracy(accuracyMeters: Float?, compact: Boolean = false, label: Boolean = !compact) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    }
    val progress = positionAccuracyProgress(accuracyMeters)
    val description = when {
        progress == null -> stringResource(R.string.accuracy_unknown)
        accuracyMeters != null && accuracyMeters > 0f && accuracyMeters < 0.1f ->
            stringResource(R.string.position_accuracy_below, format.format(0.1f))
        else -> stringResource(R.string.position_accuracy, format.format(accuracyMeters))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        if (label) Text(description, Modifier.testTag("position_accuracy"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (progress != null) {
            val reference = positionAccuracyProgress(POSITION_ACCURACY_REFERENCE_METERS)!!
            val colors = CvkeTheme.colors
            val notchColor = colors.ink
            // A functional gradient: the fill runs from the accent to the gradient end. The track stays flat.
            Canvas(Modifier.fillMaxWidth().height(12.dp).testTag("accuracy_bar").clearAndSetSemantics { contentDescription = description }) {
                val rtl = layoutDirection == LayoutDirection.Rtl
                val bar = 4.dp.toPx()
                val top = (size.height - bar) / 2
                val corner = CornerRadius(bar / 2)
                drawRoundRect(colors.surfaceSunken, Offset(0f, top), Size(size.width, bar), corner)
                // Data changes at once, so the bar does not animate.
                val fill = size.width * progress
                if (fill > 0f) {
                    val start = if (rtl) size.width - fill else 0f
                    val brush = Brush.horizontalGradient(if (rtl) listOf(colors.gradientEnd, colors.accent) else listOf(colors.accent, colors.gradientEnd),
                        startX = start, endX = start + fill)
                    drawRoundRect(brush, Offset(start, top), Size(fill, bar), corner)
                }
                val x = size.width * if (rtl) 1f - reference else reference
                drawLine(notchColor, Offset(x, 1.dp.toPx()), Offset(x, size.height - 1.dp.toPx()),
                    strokeWidth = 2.dp.toPx())
            }
            val empty = format.format(POSITION_ACCURACY_EMPTY_METERS)
            val mark = format.format(POSITION_ACCURACY_REFERENCE_METERS)
            val full = format.format(POSITION_ACCURACY_FULL_METERS)
            val scaleDescription = stringResource(R.string.accuracy_scale_description, empty, mark, full)
            if (!compact) Layout(
                modifier = Modifier.fillMaxWidth().testTag("accuracy_scale")
                    .clearAndSetSemantics { contentDescription = scaleDescription },
                content = {
                    listOf(empty, mark, full).forEach {
                        Text(stringResource(R.string.accuracy_scale_metres, it), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            ) { measurables, constraints ->
                val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                val width = constraints.maxWidth
                layout(width, labels.maxOf { it.height }) {
                    labels[0].placeRelative(0, 0)
                    labels[1].placeRelative((width * reference - labels[1].width / 2f).roundToInt(), 0)
                    labels[2].placeRelative(width - labels[2].width, 0)
                }
            }
        }
    }
}
