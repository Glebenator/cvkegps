package com.example.cvkegps.ui

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.example.cvkegps.R
import com.example.cvkegps.location.GpsReading
import com.example.cvkegps.location.GnssSnapshot
import com.example.cvkegps.location.SatelliteSignal
import com.example.cvkegps.location.distinctSatellites
import com.example.cvkegps.ui.theme.CvkeGpsTheme
import java.text.NumberFormat

@Composable
fun SpeedometerScreen(
    state: SpeedometerUiState,
    permissionNeedsSettings: Boolean,
    onAction: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    gnss: GnssSnapshot = GnssSnapshot(),
    trackerControls: @Composable () -> Unit = {},
    onTrips: (() -> Unit)? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val speedFormat = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
            isGroupingUsed = false
        }
    }
    val wholeFormat = remember(locale) {
        NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }
    }
    val missing = stringResource(R.string.no_value)
    val speed = state.reading?.speedMetersPerSecond?.let { speedFormat.format(it * 3.6) } ?: missing
    val altitude = state.reading?.altitudeMeters?.let(wholeFormat::format) ?: missing
    val maximum = state.maximumSpeedMetersPerSecond?.let { speedFormat.format(it * 3.6) } ?: missing
    val satellites = remember(gnss.signals, state.status) {
        gnss.signals?.takeIf {
            state.status == GpsStatus.Live || state.status == GpsStatus.Searching || state.status == GpsStatus.Stale
        }?.distinctSatellites()
    }

    Scaffold(modifier = modifier.fillMaxSize()) { insets ->
        BoxWithConstraints(
            Modifier.padding(insets).fillMaxSize().padding(16.dp).testTag("home_content"),
        ) {
            val wide = maxWidth > maxHeight
            if (wide) {
                Row(
                    modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize().align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(
                        Modifier.weight(1f).fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SpeedDial(speed, state.reading?.speedMetersPerSecond != null, satellites.orEmpty(), Modifier.weight(1f))
                        Metrics(altitude, maximum)
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GpsControls(state, permissionNeedsSettings, wholeFormat, onAction, onDetails, satellites, onTrips, compact = wide)
                    if (state.status != GpsStatus.PermissionRequired && state.status != GpsStatus.Disabled && state.status != GpsStatus.Unavailable) trackerControls()
                    }
                }
            } else {
                Column(
                    Modifier.widthIn(max = 480.dp).fillMaxSize().align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SpeedDial(speed, state.reading?.speedMetersPerSecond != null, satellites.orEmpty(), Modifier.weight(1f))
                    Metrics(altitude, maximum)
                    GpsControls(state, permissionNeedsSettings, wholeFormat, onAction, onDetails, satellites, onTrips, compact = wide)
                    if (state.status != GpsStatus.PermissionRequired && state.status != GpsStatus.Disabled && state.status != GpsStatus.Unavailable) trackerControls()
                }
            }
        }
    }
}

@Composable
private fun SpeedDial(speed: String, available: Boolean, satellites: List<SatelliteSignal>, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val description = if (available) stringResource(R.string.speed_accessibility, speed)
    else stringResource(R.string.speed_unavailable_accessibility)
    BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        SatelliteConstellation(satellites, Modifier.fillMaxSize())
        // The controls and measurements take their natural height. Speed uses the remaining space.
        if (maxHeight < 200.dp) {
            // A short window needs the full width for speed, without a circle or stacked unit.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpeedValue(speed, description, Modifier.weight(1f).alignByBaseline(), minFontSize = 16.sp)
                Text(
                    stringResource(R.string.speed_unit),
                    Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val diameter = minOf(maxWidth, maxHeight, 320.dp)
            val showLabel = diameter >= 240.dp
            Column(
                Modifier.size(diameter)
                    .drawBehind { drawCircle(ring.copy(alpha = 0.45f), style = Stroke(1.dp.toPx())) }
                    .padding(if (showLabel) 24.dp else 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showLabel) {
                    Text(
                        stringResource(R.string.current_speed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    SpeedValue(speed, description, Modifier.fillMaxWidth())
                }
                Text(
                    stringResource(R.string.speed_unit),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpeedValue(
    speed: String,
    description: String,
    modifier: Modifier,
    minFontSize: TextUnit = 24.sp,
) {
    BasicText(
        text = speed,
        modifier = modifier.testTag("current_speed")
            .clearAndSetSemantics { contentDescription = description },
        style = MaterialTheme.typography.displayLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            fontFeatureSettings = "tnum",
            lineHeight = TextUnit.Unspecified,
        ),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = minFontSize, maxFontSize = 104.sp),
    )
}

@Composable
private fun Metrics(altitude: String, maximum: String) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(R.string.altitude, altitude, R.string.altitude_unit, "altitude", Modifier.weight(1f).fillMaxHeight())
        MetricCard(R.string.maximum_speed, maximum, R.string.speed_unit, "maximum_speed", Modifier.weight(1f).fillMaxHeight(), R.string.since_open)
    }
}

@Composable
private fun MetricCard(
    @StringRes title: Int,
    value: String,
    @StringRes unit: Int,
    tag: String,
    modifier: Modifier,
    @StringRes subtitle: Int? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(
                    text = value,
                    modifier = Modifier.weight(1f, fill = false).testTag(tag).alignByBaseline(),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                        lineHeight = TextUnit.Unspecified,
                    ),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 28.sp),
                )
                Text(
                    stringResource(unit),
                    modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (subtitle != null) {
                Text(
                    stringResource(subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun GpsControls(
    state: SpeedometerUiState,
    permissionNeedsSettings: Boolean,
    format: NumberFormat,
    onAction: () -> Unit,
    onDetails: (() -> Unit)? = null,
    satellites: List<SatelliteSignal>? = null,
    onTrips: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    val title = when (state.status) {
        GpsStatus.PermissionRequired -> R.string.gps_permission
        GpsStatus.Disabled -> R.string.gps_disabled
        GpsStatus.Unavailable -> R.string.gps_unavailable
        GpsStatus.Searching -> R.string.gps_searching
        GpsStatus.Live -> R.string.gps_live
        GpsStatus.Stale -> R.string.gps_stale
        GpsStatus.Paused -> R.string.gps_paused
        GpsStatus.Error -> R.string.gps_error
    }
    val body = when (state.status) {
        GpsStatus.PermissionRequired -> R.string.permission_body
        GpsStatus.Disabled -> R.string.disabled_body
        GpsStatus.Unavailable -> R.string.unavailable_body
        GpsStatus.Searching -> R.string.searching_body
        GpsStatus.Live -> R.string.live_body
        GpsStatus.Stale -> R.string.stale_body
        GpsStatus.Paused -> R.string.paused_body
        GpsStatus.Error -> R.string.error_body
    }
    val action = when (state.status) {
        GpsStatus.PermissionRequired -> if (permissionNeedsSettings) R.string.open_settings else R.string.allow_location
        GpsStatus.Disabled -> R.string.enable_location
        GpsStatus.Error -> R.string.retry_gps
        else -> null
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Text(stringResource(title), Modifier.weight(1f).testTag("gps_status"), style = MaterialTheme.typography.titleSmall)
                    if (onTrips != null) androidx.compose.material3.TextButton(onClick = onTrips, modifier = Modifier.testTag("nav_trips")) {
                        Text(stringResource(R.string.tracking_trips))
                    }
                    if (onDetails != null) {
                        IconButton(onClick = onDetails, modifier = Modifier.size(48.dp).testTag("gps_details")) {
                            Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.gps_details))
                        }
                    }
                }
                if (onDetails != null && (!compact || satellites != null)) {
                    Text(
                        satellites?.let {
                            stringResource(R.string.satellite_count, format.format(it.count { satellite -> satellite.usedInFix }), format.format(it.size))
                        } ?: stringResource(R.string.satellite_count_unavailable),
                        Modifier.testTag("satellite_count"),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (onDetails == null || state.status != GpsStatus.Live) {
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
                }
                if (state.status == GpsStatus.Live) {
                    PositionAccuracy(state.reading?.accuracyMeters)
                }
            }
        }
        if (action != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("gps_action"),
            ) {
                Text(stringResource(action), Modifier.padding(vertical = 8.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Large text", fontScale = 2f, showBackground = true)
@Composable
private fun SpeedometerPreview() {
    CvkeGpsTheme(dynamicColor = false) {
        SpeedometerScreen(
            SpeedometerUiState(GpsStatus.Live, GpsReading(8.2f, 451.0, 4f, 0), 12.5f),
            permissionNeedsSettings = false,
            onAction = {},
            onDetails = {},
        )
    }
}
