package com.example.cvkegps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.example.cvkegps.R

/**
 * True when the window is less than 480 dp high, which is a phone in landscape.
 * Screens then have no top app bar, and maps share the width with a side pane.
 */
@Composable
fun isCompactHeight(): Boolean =
    !currentWindowAdaptiveInfoV2().windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)

/** True when the window is at least 600 dp wide, for example an open foldable or a tablet. Maps then share the width with a side pane. */
@Composable
fun isWideWindow(): Boolean =
    currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

/** A 48 dp back button on a filled surface, so it stays readable over a map. */
@Composable
fun FloatingBackButton(onBack: () -> Unit, modifier: Modifier = Modifier, tag: String = "top_back") {
    FilledTonalIconButton(onClick = onBack, modifier = modifier.size(48.dp).testTag(tag)) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
    }
}

/** The compact-height replacement for a top app bar. It sits inside the content, so it uses no extra height. */
@Composable
fun PaneHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backTag: String = "top_back",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.testTag(backTag)) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
        }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/**
 * The wide trip layout: a map and a side pane. On a phone in landscape ([edgeToEdge]) the map runs under the system bars,
 * so its controls receive the insets. Below a top app bar, such as on an open foldable, the caller handles the insets.
 * The pane scrolls, and [bottom] stays at the bottom edge for the main buttons.
 */
@Composable
fun MapWithPane(
    map: @Composable (Modifier, WindowInsets) -> Unit,
    edgeToEdge: Boolean = true,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    pane: @Composable ColumnScope.() -> Unit,
) {
    val safe = if (edgeToEdge) WindowInsets.safeDrawing else WindowInsets(0)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneWidth = (maxWidth * 0.38f).coerceIn(300.dp, 420.dp)
        Row(Modifier.fillMaxSize()) {
            map(Modifier.weight(1f).fillMaxHeight(), safe.only(WindowInsetsSides.Top + WindowInsetsSides.Start + WindowInsetsSides.Bottom))
            Surface(Modifier.width(paneWidth).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(safe.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("trip_pane"),
                        verticalArrangement = Arrangement.spacedBy(12.dp), content = pane)
                    // A divider marks the end of the scrolling values, so a value cut at that edge reads as scrollable.
                    if (bottom != null) {
                        HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = bottom)
                    }
                }
            }
        }
    }
}
