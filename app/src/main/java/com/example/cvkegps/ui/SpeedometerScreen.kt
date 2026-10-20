package com.example.cvkegps.ui

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cvkegps.R
import com.example.cvkegps.location.GpsReading
import com.example.cvkegps.ui.theme.CvkeGpsTheme
import java.text.NumberFormat

@Composable
fun SpeedometerScreen(
    state: SpeedometerUiState,
    permissionNeedsSettings: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
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

    Scaffold(modifier = modifier.fillMaxSize()) { insets ->
        BoxWithConstraints(
            Modifier.padding(insets).fillMaxSize().padding(horizontal = 24.dp),
        ) {
            val wide = maxWidth >= 600.dp && LocalDensity.current.fontScale <= 1.3f
            val landscapeDialSize = (maxHeight - 128.dp).coerceIn(224.dp, 260.dp)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(vertical = if (wide) 12.dp else 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 24.dp),
            ) {
                if (wide) {
                    Row(
                        modifier = Modifier.widthIn(max = 840.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ScreenHeader()
                            SpeedDial(
                                speed,
                                state.reading?.speedMetersPerSecond != null,
                                Modifier.widthIn(max = landscapeDialSize),
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Metrics(altitude, maximum)
                            GpsControls(state, permissionNeedsSettings, wholeFormat, onAction)
                            PrivacyNote()
                        }
                    }
                } else {
                    ScreenHeader()
                    SpeedDial(speed, state.reading?.speedMetersPerSecond != null)
                    Column(
                        Modifier.widthIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Metrics(altitude, maximum)
                        GpsControls(state, permissionNeedsSettings, wholeFormat, onAction)
                    }
                    PrivacyNote()
                }
            }
        }
    }
}

@Composable
private fun ScreenHeader() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.screen_title), style = MaterialTheme.typography.headlineLarge)
    }
}

@Composable
private fun PrivacyNote() {
    Text(
        stringResource(R.string.privacy_note),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SpeedDial(speed: String, available: Boolean, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val description = if (available) stringResource(R.string.speed_accessibility, speed)
    else stringResource(R.string.speed_unavailable_accessibility)
    Box(
        modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)
            .drawBehind { drawCircle(ring, style = Stroke(2.dp.toPx())) }
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.current_speed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            BasicText(
                text = speed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 130.dp)
                    .testTag("current_speed")
                    .clearAndSetSemantics { contentDescription = description },
                style = MaterialTheme.typography.displayLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    fontFeatureSettings = "tnum",
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 36.sp, maxFontSize = 104.sp),
            )
            Text(
                stringResource(R.string.speed_unit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Metrics(altitude: String, maximum: String) {
    if (LocalDensity.current.fontScale > 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(R.string.altitude, altitude, R.string.altitude_unit, "altitude", Modifier.fillMaxWidth())
            MetricCard(R.string.maximum_speed, maximum, R.string.speed_unit, "maximum_speed", Modifier.fillMaxWidth(), R.string.since_open)
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(R.string.altitude, altitude, R.string.altitude_unit, "altitude", Modifier.weight(1f))
            MetricCard(R.string.maximum_speed, maximum, R.string.speed_unit, "maximum_speed", Modifier.weight(1f), R.string.since_open)
        }
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    value,
                    modifier = Modifier.testTag(tag).alignByBaseline(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(unit),
                    modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                subtitle?.let { stringResource(it) } ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GpsControls(
    state: SpeedometerUiState,
    permissionNeedsSettings: Boolean,
    format: NumberFormat,
    onAction: () -> Unit,
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
        GpsStatus.Paused -> R.string.resume_gps
        GpsStatus.Error -> R.string.retry_gps
        else -> R.string.pause_gps
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Text(stringResource(title), Modifier.testTag("gps_status"), style = MaterialTheme.typography.titleSmall)
                }
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
                if (state.status == GpsStatus.Live) {
                    Text(
                        state.reading?.accuracyMeters?.let {
                            stringResource(R.string.accuracy_value, format.format(it))
                        } ?: stringResource(R.string.accuracy_unknown),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        if (state.status != GpsStatus.Unavailable) {
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
        )
    }
}
