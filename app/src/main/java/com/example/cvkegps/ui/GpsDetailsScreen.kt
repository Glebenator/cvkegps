package com.example.cvkegps.ui

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.cvkegps.R
import androidx.compose.material3.ElevatedCard
import com.example.cvkegps.ui.theme.Space
import com.example.cvkegps.location.GnssObservation
import com.example.cvkegps.location.GnssSnapshot
import com.example.cvkegps.location.ReceiverEvent
import com.example.cvkegps.location.SatelliteSignal
import com.example.cvkegps.location.SatelliteSystem
import com.example.cvkegps.location.distinctSatellites
import com.example.cvkegps.location.skyPosition
import com.example.cvkegps.location.SpeedIssue
import java.text.NumberFormat
import kotlinx.coroutines.delay

@Composable
fun GpsDetailsRoute(
    state: SpeedometerUiState,
    gnss: GnssSnapshot,
    permissionNeedsSettings: Boolean,
    onAction: () -> Unit,
    onBack: () -> Unit,
    compassViewModel: CompassViewModel? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Refresh ages only while this screen is visible. This timer does not request GPS data.
    val now = produceState(SystemClock.elapsedRealtimeNanos(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = SystemClock.elapsedRealtimeNanos()
                delay(1_000)
            }
        }
    }
    // Only the two age values read the time, so the timer does not recompose the whole screen.
    GpsDetailsScreen(state, gnss, { maxOf(now.value, SystemClock.elapsedRealtimeNanos()) }, permissionNeedsSettings, onAction, onBack, compassViewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpsDetailsScreen(
    state: SpeedometerUiState,
    gnss: GnssSnapshot,
    now: () -> Long,
    permissionNeedsSettings: Boolean,
    onAction: () -> Unit,
    onBack: () -> Unit,
    compassViewModel: CompassViewModel?,
) {
    val locale = LocalConfiguration.current.locales[0]
    val decimal = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    val whole = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val missing = stringResource(R.string.details_not_available)
    fun ageSeconds(nanos: Long?) = nanos?.let { (now() - it).takeIf { age -> age >= 0 }?.div(1e9) }
    // These change only when a value becomes 10 seconds old, so the one-second timer does not recompose the screen.
    val readingFresh by remember(state.lastFixElapsedNanos) { derivedStateOf { ageSeconds(state.lastFixElapsedNanos)?.let { it < 10.0 } == true } }
    val signalsFresh by remember(gnss.reportedAtNanos) { derivedStateOf { ageSeconds(gnss.reportedAtNanos)?.let { it < 10.0 } == true } }
    val reading = state.reading.takeIf { readingFresh }
    val signals = gnss.signals.takeIf { signalsFresh }
    val reportState = when {
        gnss.observation == GnssObservation.Inactive -> R.string.details_not_monitoring
        gnss.observation == GnssObservation.Unavailable -> R.string.details_callback_unavailable
        signals != null -> R.string.details_report_current
        gnss.reportedAtNanos != null -> R.string.details_report_old
        else -> R.string.details_report_waiting
    }
    val speedReason = when {
        state.status == GpsStatus.Stale -> R.string.details_speed_old
        reading == null -> R.string.details_speed_no_reading
        reading.speedIssue == SpeedIssue.PositionAccuracy -> R.string.details_speed_position
        reading.speedIssue == SpeedIssue.SpeedAccuracy -> R.string.details_speed_uncertain
        reading.speedIssue == SpeedIssue.Missing -> R.string.details_speed_missing
        reading.speedIssue == SpeedIssue.Invalid -> R.string.details_speed_invalid
        else -> R.string.details_speed_accepted
    }

    // The map, its count, and the empty state. Portrait puts it in a card. Landscape keeps it beside the list.
    val skyMap: @Composable (Dp?) -> Unit = { maxDiameter ->
        if (signals != null) {
            PhoneSkyPlot(signals, reading?.magneticDeclinationRadians, compassViewModel, maxDiameter)
            val satellites = signals.distinctSatellites()
            val plotted = satellites.count { skyPosition(it.azimuthRadians, it.elevationRadians) != null }
            Text(stringResource(R.string.sky_map_count, whole.format(plotted), whole.format(satellites.size)),
                Modifier.testTag("sky_map_count"), style = MaterialTheme.typography.bodySmall)
            if (signals.isEmpty()) Text(stringResource(R.string.details_no_signals))
        } else {
            Text(stringResource(reportState), Modifier.testTag("sky_map_unavailable"))
        }
    }
    val compact = isCompactHeight()
    val list: LazyListScope.() -> Unit = {
        item {
            GpsControls(state, permissionNeedsSettings, whole, onAction)
        }
        item {
            DetailCard(R.string.sky_map_title) {
                if (!compact) skyMap(null)
                Text(stringResource(R.string.sky_map_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            DetailCard(R.string.details_request) {
                DetailValue(R.string.details_provider, stringResource(R.string.details_provider_value))
                DetailValue(R.string.details_interval, stringResource(R.string.details_seconds, decimal.format(1)))
                DetailValue(R.string.details_readings, whole.format(state.receivedFixes), "details_reading_count")
                DetailValue(R.string.details_fix_age, ageSeconds(state.lastFixElapsedNanos)?.let { stringResource(R.string.details_seconds, decimal.format(it)) } ?: missing, "details_fix_age")
                Text(stringResource(R.string.details_request_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            DetailCard(R.string.details_quality) {
                DetailValue(R.string.details_speed_result, stringResource(speedReason), "details_speed_reason")
                DetailValue(R.string.details_horizontal_accuracy, reading?.accuracyMeters?.let { stringResource(R.string.details_metres, decimal.format(it)) } ?: missing)
                DetailValue(R.string.details_speed_accuracy, reading?.speedAccuracyMetersPerSecond?.let { stringResource(R.string.details_mps, decimal.format(it)) } ?: missing)
                DetailValue(R.string.details_vertical_accuracy, reading?.verticalAccuracyMeters?.let { stringResource(R.string.details_metres, decimal.format(it)) } ?: missing)
                DetailValue(R.string.details_bearing, reading?.bearingRadians?.let { stringResource(R.string.details_degrees, decimal.format(Math.toDegrees(it))) } ?: missing)
                DetailValue(R.string.details_bearing_accuracy, reading?.bearingAccuracyRadians?.let { stringResource(R.string.details_degrees, decimal.format(Math.toDegrees(it))) } ?: missing)
                DetailValue(R.string.details_test_reading, reading?.let { stringResource(if (it.isMock) R.string.details_yes else R.string.details_no) } ?: missing, "details_mock")
                Text(stringResource(R.string.details_accuracy_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            DetailCard(R.string.details_receiver) {
                DetailValue(R.string.details_last_event, stringResource(when (gnss.receiverEvent) {
                    ReceiverEvent.Unknown -> R.string.details_not_available
                    ReceiverEvent.Started -> R.string.details_receiver_started
                    ReceiverEvent.Stopped -> R.string.details_receiver_stopped
                }))
                DetailValue(R.string.details_first_fix, gnss.firstFixSeconds?.let { stringResource(R.string.details_seconds, decimal.format(it)) } ?: missing)
                DetailValue(R.string.details_satellite_report, stringResource(reportState), "details_satellite_report")
                DetailValue(R.string.details_report_age, ageSeconds(gnss.reportedAtNanos)?.let { stringResource(R.string.details_seconds, decimal.format(it)) } ?: missing)
                DetailValue(R.string.details_satellites, signals?.distinctBy { it.system to it.id }?.size?.let(whole::format) ?: missing)
                DetailValue(R.string.details_used_satellites, signals?.filter { it.usedInFix }?.distinctBy { it.system to it.id }?.size?.let(whole::format) ?: missing)
                Text(stringResource(R.string.details_receiver_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text(stringResource(R.string.details_signals), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.details_signal_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.constellation_note), style = MaterialTheme.typography.bodySmall)
            if (signals.isNullOrEmpty()) {
                Text(stringResource(if (signals?.isEmpty() == true) R.string.details_no_signals else reportState), Modifier.padding(top = Space.s3))
            }
        }
        if (compact) {
            // Two signal cards in each row, so the pane does not hold one long column.
            items(signals.orEmpty().chunked(2), contentType = { "signal_pair" }) { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                    pair.forEach { signal -> Box(Modifier.weight(1f)) { SignalCard(signal, decimal, whole, missing) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else items(signals.orEmpty(), contentType = { "signal" }) { signal -> SignalCard(signal, decimal, whole, missing) }
        item { Text(stringResource(R.string.privacy_note), style = MaterialTheme.typography.bodySmall) }
    }

    if (compact) {
        // Landscape on a phone: the sky map stays in place and fits the height. The cards scroll beside it.
        val safe = WindowInsets.safeDrawing
        Row(Modifier.fillMaxSize().windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal))) {
            BoxWithConstraints(Modifier.weight(0.45f).fillMaxHeight().windowInsetsPadding(safe.only(WindowInsetsSides.Vertical))
                .padding(horizontal = Space.s4, vertical = Space.s2)) {
                // The map gets the height that the header and its two short texts leave. Large text can wrap each text to two lines.
                val fontScale = LocalDensity.current.fontScale
                val textHeight = (16 * fontScale * (if (fontScale > 1.3f) 2 else 1)).dp
                val mapSize = (maxHeight - 48.dp - textHeight * 2 - 32.dp).coerceIn(120.dp, 360.dp)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    PaneHeader(stringResource(R.string.gps_details), onBack, backTag = "details_back")
                    skyMap(mapSize)
                }
            }
            LazyColumn(
                modifier = Modifier.weight(0.55f).fillMaxHeight().windowInsetsPadding(safe.only(WindowInsetsSides.Vertical)).testTag("gps_details_list"),
                contentPadding = PaddingValues(Space.s4),
                verticalArrangement = Arrangement.spacedBy(Space.s4),
                content = list,
            )
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gps_details)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("details_back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).testTag("gps_details_list"),
            contentPadding = PaddingValues(Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
            content = list,
        )
    }
}

@Composable
private fun DetailCard(@StringRes title: Int, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Text(stringResource(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DetailValue(@StringRes label: Int, value: String, tag: String = "") {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.testTag(tag), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SignalCard(signal: SatelliteSignal, decimal: NumberFormat, whole: NumberFormat, missing: String) {
    val system = stringResource(when (signal.system) {
        SatelliteSystem.Gps -> R.string.constellation_gps
        SatelliteSystem.Sbas -> R.string.constellation_sbas
        SatelliteSystem.Glonass -> R.string.constellation_glonass
        SatelliteSystem.Qzss -> R.string.constellation_qzss
        SatelliteSystem.Beidou -> R.string.constellation_beidou
        SatelliteSystem.Galileo -> R.string.constellation_galileo
        SatelliteSystem.Irnss -> R.string.constellation_irnss
        SatelliteSystem.Unknown -> R.string.constellation_unknown
    })
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                SatelliteMarker(signal.system, signal.usedInFix)
                Text(stringResource(R.string.details_satellite_id, system, whole.format(signal.id)), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(if (signal.usedInFix) R.string.details_used else R.string.details_not_used))
            DetailValue(R.string.details_cn0, signal.cn0DbHz.takeIf { it.isFinite() && it >= 0 }?.let { stringResource(R.string.details_dbhz, decimal.format(it)) } ?: missing)
            DetailValue(R.string.details_elevation, signal.elevationRadians.takeIf { it.isFinite() }?.let { stringResource(R.string.details_degrees, decimal.format(Math.toDegrees(it))) } ?: missing)
            DetailValue(R.string.details_azimuth, signal.azimuthRadians.takeIf { it.isFinite() }?.let { stringResource(R.string.details_degrees, decimal.format(Math.toDegrees(it))) } ?: missing)
            DetailValue(R.string.details_frequency, signal.carrierFrequencyHz?.takeIf { it.isFinite() && it > 0 }?.let { stringResource(R.string.details_mhz, decimal.format(it / 1e6)) } ?: missing)
        }
    }
}
