package com.example.cvkegps.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.cvkegps.R
import com.example.cvkegps.location.POSITION_ACCURACY_EMPTY_METERS
import com.example.cvkegps.location.POSITION_ACCURACY_FULL_METERS
import com.example.cvkegps.location.POSITION_ACCURACY_REFERENCE_METERS
import com.example.cvkegps.location.positionAccuracyProgress
import java.text.NumberFormat
import kotlin.math.roundToInt

@Composable
internal fun PositionAccuracy(accuracyMeters: Float?) {
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(description, Modifier.testTag("position_accuracy"), style = MaterialTheme.typography.labelMedium)
        if (progress != null) {
            val animated by animateFloatAsState(progress, tween(650), label = "position accuracy")
            val reference = positionAccuracyProgress(POSITION_ACCURACY_REFERENCE_METERS)!!
            val notchColor = MaterialTheme.colorScheme.onSecondaryContainer
            Box(Modifier.fillMaxWidth().height(12.dp).testTag("accuracy_bar").clearAndSetSemantics { }) {
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = notchColor.copy(alpha = 0.1f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Canvas(Modifier.matchParentSize()) {
                    val x = size.width * if (layoutDirection == LayoutDirection.Rtl) 1f - reference else reference
                    drawLine(notchColor, Offset(x, 1.dp.toPx()), Offset(x, size.height - 1.dp.toPx()),
                        strokeWidth = 2.dp.toPx())
                }
            }
            val empty = format.format(POSITION_ACCURACY_EMPTY_METERS)
            val mark = format.format(POSITION_ACCURACY_REFERENCE_METERS)
            val full = format.format(POSITION_ACCURACY_FULL_METERS)
            val scaleDescription = stringResource(R.string.accuracy_scale_description, empty, mark, full)
            Layout(
                modifier = Modifier.fillMaxWidth().testTag("accuracy_scale")
                    .clearAndSetSemantics { contentDescription = scaleDescription },
                content = {
                    listOf(empty, mark, full).forEach {
                        Text(stringResource(R.string.accuracy_scale_metres, it), style = MaterialTheme.typography.labelSmall)
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
