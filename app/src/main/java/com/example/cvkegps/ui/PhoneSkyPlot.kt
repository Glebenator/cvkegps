package com.example.cvkegps.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cvkegps.R
import com.example.cvkegps.location.SatelliteSignal
import com.example.cvkegps.location.wholeDegrees
import java.text.NumberFormat

/** Collect compass data only while the caller is composed and the activity is resumed. */
@Composable
internal fun collectPhoneHeading(model: CompassViewModel, declinationRadians: Double?): PhoneHeading {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    LaunchedEffect(model, configuration) { model.setDisplayRotation(view.display?.rotation ?: 0) }
    LaunchedEffect(model, declinationRadians) { declinationRadians?.let(model::setDeclination) }
    val heading by model.heading.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    return heading
}

@Composable
internal fun PhoneSkyPlot(satellites: List<SatelliteSignal>, declinationRadians: Double?, model: CompassViewModel?) {
    val heading = model?.let { collectPhoneHeading(it, declinationRadians) } ?: PhoneHeading.Unavailable
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    Text(
        when (heading) {
            is PhoneHeading.Current -> stringResource(R.string.sky_heading_current, format.format(wholeDegrees(heading.trueRadians)))
            PhoneHeading.Starting -> stringResource(R.string.sky_heading_starting)
            PhoneHeading.Unavailable -> stringResource(R.string.sky_heading_unavailable)
            PhoneHeading.WaitingForPosition -> stringResource(R.string.sky_heading_waiting_position)
        },
        Modifier.testTag("sky_heading"), style = MaterialTheme.typography.bodySmall,
    )
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val diameter = (windowHeight - 160.dp).coerceIn(120.dp, 360.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SatelliteSkyPlot(
            satellites, heading, stringResource(R.string.sky_map_description),
            Modifier.widthIn(max = diameter).fillMaxWidth().aspectRatio(1f).testTag("satellite_sky_map"),
        )
    }
}
