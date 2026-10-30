package com.example.cvkegps.ui

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cvkegps.R
import com.example.cvkegps.location.seaLevelAltitudeSupported
import com.example.cvkegps.settings.AltitudeReference
import com.example.cvkegps.settings.AltitudeUnit
import com.example.cvkegps.settings.AppColours
import com.example.cvkegps.settings.AppSettings
import com.example.cvkegps.settings.DistanceUnit
import com.example.cvkegps.settings.MapLayer
import com.example.cvkegps.settings.MapStart
import com.example.cvkegps.settings.RecordingAccuracy
import com.example.cvkegps.settings.RouteColour
import com.example.cvkegps.settings.ScreenOn
import com.example.cvkegps.settings.SpeedUnit
import com.example.cvkegps.ui.theme.CvkeTheme
import com.example.cvkegps.ui.theme.SavedNote
import com.example.cvkegps.ui.theme.SegmentedChoice
import com.example.cvkegps.ui.theme.Space

private enum class Section { Units, Altitude, Screen, Colours, Maps, Recording }

/** One long page with a heading and a card for each section. Each change saves at once and shows "Saved" by its heading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    val values by settings.values.collectAsStateWithLifecycle()
    val units = values.units(LocalConfiguration.current.locales[0].country)
    val altitude = if (seaLevelAltitudeSupported) values.altitudeReference else AltitudeReference.Ellipsoid
    val changes = remember { mutableStateMapOf<Section, Int>() }
    fun saved(section: Section, change: () -> Unit) {
        change()
        changes[section] = (changes[section] ?: 0) + 1
    }
    val content: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(Space.s4).testTag("settings_content"),
            verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            SettingsSection(R.string.settings_units, changes[Section.Units] ?: 0) {
                Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    UnitChoice(R.string.settings_units_speed, SpeedUnit.entries, units.speed, { it.label }, { it.spoken }, "speed",
                        { saved(Section.Units) { settings.setSpeedUnit(it) } })
                    UnitChoice(R.string.settings_units_distance, DistanceUnit.entries, units.distance, { it.label }, { it.spoken }, "distance",
                        { saved(Section.Units) { settings.setDistanceUnit(it) } })
                    UnitChoice(R.string.settings_units_altitude, AltitudeUnit.entries, units.altitude, { it.label }, { it.spoken }, "altitude",
                        { saved(Section.Units) { settings.setAltitudeUnit(it) } })
                    Text(stringResource(R.string.settings_units_note), style = MaterialTheme.typography.bodySmall,
                        color = CvkeTheme.colors.inkMuted)
                }
            }
            SettingsSection(R.string.settings_altitude, changes[Section.Altitude] ?: 0, Modifier.selectableGroup()) {
                // Before Android 14 the sea-level choice shows why it is not available.
                ChoiceRow(R.string.altitude_sea_level, if (seaLevelAltitudeSupported) R.string.settings_altitude_sea_level_body
                    else R.string.settings_altitude_sea_level_unavailable, altitude == AltitudeReference.SeaLevel, "altitude_sea_level",
                    enabled = seaLevelAltitudeSupported) { saved(Section.Altitude) { settings.setAltitudeReference(AltitudeReference.SeaLevel) } }
                ChoiceRow(R.string.altitude_ellipsoid, R.string.settings_altitude_ellipsoid_body, altitude == AltitudeReference.Ellipsoid,
                    "altitude_ellipsoid") { saved(Section.Altitude) { settings.setAltitudeReference(AltitudeReference.Ellipsoid) } }
            }
            SettingsSection(R.string.settings_screen, changes[Section.Screen] ?: 0) {
                Column(Modifier.selectableGroup()) {
                    Label(R.string.settings_screen_on)
                    ScreenOn.entries.forEach { choice ->
                        ChoiceRow(choice.title, choice.body, values.screenOn == choice, "screen_on_${choice.key}") {
                            saved(Section.Screen) { settings.setScreenOn(choice) }
                        }
                    }
                }
                // Where and how apply only while the app keeps the screen on.
                val on = values.screenOn != ScreenOn.Off
                Label(R.string.settings_screen_where)
                SwitchRow(R.string.settings_screen_speed, null, values.screenOnSpeed, "setting_screen_speed", on) {
                    saved(Section.Screen) { settings.setScreenOnSpeed(it) }
                }
                SwitchRow(R.string.settings_screen_trip_map, null, values.screenOnTripMap, "setting_screen_trip_map", on) {
                    saved(Section.Screen) { settings.setScreenOnTripMap(it) }
                }
                SwitchRow(R.string.settings_dim, R.string.settings_dim_body, values.dimScreen, "setting_dim", on) {
                    saved(Section.Screen) { settings.setDimScreen(it) }
                }
            }
            SettingsSection(R.string.settings_colours, changes[Section.Colours] ?: 0, Modifier.selectableGroup()) {
                // Before Android 12 the wallpaper choices show why they are not available.
                val wallpaper = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                val colours = if (wallpaper) values.colours else AppColours.App
                AppColours.entries.forEach { choice ->
                    val available = wallpaper || choice == AppColours.App
                    ChoiceRow(choice.title, if (available) choice.body else R.string.settings_colours_wallpaper_unavailable, colours == choice,
                        "colours_${choice.key}", enabled = available) { saved(Section.Colours) { settings.setColours(choice) } }
                }
            }
            SettingsSection(R.string.settings_maps, changes[Section.Maps] ?: 0) {
                fun maps(change: () -> Unit) = saved(Section.Maps, change)
                Column(Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Text(stringResource(R.string.settings_map_type), style = MaterialTheme.typography.titleSmall)
                    SegmentedChoice(MapLayer.entries, values.mapLayer, { stringResource(it.title) },
                        { maps { settings.setMapLayer(it) } }, tag = { "map_type_${it.key}" })
                }
                SwitchRow(R.string.settings_satellite_labels, R.string.settings_satellite_labels_body, values.mapSatelliteLabels,
                    "setting_satellite_labels") { maps { settings.setMapSatelliteLabels(it) } }
                SwitchRow(R.string.settings_3d_buildings, null, values.map3dBuildings, "setting_3d_buildings") {
                    maps { settings.setMap3dBuildings(it) }
                }
                Column(Modifier.padding(horizontal = Space.s4, vertical = Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Text(stringResource(R.string.settings_route_colour), style = MaterialTheme.typography.titleSmall)
                    SegmentedChoice(RouteColour.entries, values.routeColour, { stringResource(it.title) },
                        { maps { settings.setRouteColour(it) } }, tag = { "route_colour_${it.key}" })
                    Text(stringResource(R.string.settings_route_colour_note), style = MaterialTheme.typography.bodySmall,
                        color = CvkeTheme.colors.inkMuted)
                }
                // Keeping the route blue changes something only while the app uses wallpaper colours.
                val wallpaperAccent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && values.colours == AppColours.Wallpaper
                SwitchRow(R.string.settings_route_blue, R.string.settings_route_blue_body, values.routeAppBlue, "setting_route_blue",
                    wallpaperAccent) { maps { settings.setRouteAppBlue(it) } }
                Label(R.string.settings_maps_following)
                Column(Modifier.padding(horizontal = Space.s4, vertical = Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Text(stringResource(R.string.settings_map_start), style = MaterialTheme.typography.titleSmall)
                    SegmentedChoice(MapStart.entries, values.mapStart, { stringResource(it.title) },
                        { maps { settings.setMapStart(it) } }, tag = { "map_start_${it.key}" })
                }
                SwitchRow(R.string.settings_map_tilt, R.string.settings_map_tilt_body, values.mapTilt, "setting_map_tilt") {
                    maps { settings.setMapTilt(it) }
                }
                SwitchRow(R.string.settings_zoom_speed, R.string.settings_zoom_speed_body, values.mapZoomWithSpeed, "setting_zoom_speed") {
                    maps { settings.setMapZoomWithSpeed(it) }
                }
                SwitchRow(R.string.settings_accuracy_circle, R.string.settings_accuracy_circle_body, values.mapAccuracyCircle,
                    "setting_accuracy_circle") { maps { settings.setMapAccuracyCircle(it) } }
                SwitchRow(R.string.settings_traffic, R.string.settings_traffic_body, values.mapTraffic, "setting_traffic") {
                    maps { settings.setMapTraffic(it) }
                }
            }
            SettingsSection(R.string.settings_recording, changes[Section.Recording] ?: 0) {
                Column(Modifier.selectableGroup()) {
                    Label(R.string.settings_recording_accuracy)
                    RecordingAccuracy.entries.forEach { choice ->
                        ChoiceRow(choice.title, choice.body, values.recordingAccuracy == choice, "recording_${choice.key}") {
                            saved(Section.Recording) { settings.setRecordingAccuracy(choice) }
                        }
                    }
                }
                SwitchRow(R.string.settings_network_fallback, R.string.settings_network_fallback_body, values.networkFallback,
                    "setting_network_fallback") { saved(Section.Recording) { settings.setNetworkFallback(it) } }
                Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Text(stringResource(R.string.settings_recording_sensors), style = MaterialTheme.typography.bodySmall,
                        color = CvkeTheme.colors.inkMuted)
                    Text(stringResource(R.string.settings_recording_note), style = MaterialTheme.typography.bodySmall,
                        color = CvkeTheme.colors.inkMuted)
                }
            }
            SettingsSection(R.string.settings_about, 0) {
                val context = LocalContext.current
                val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
                ListItem(headlineContent = { Text(stringResource(R.string.settings_version), style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text(version) }, colors = transparent())
                ListItem(headlineContent = { Text(stringResource(R.string.privacy_note)) }, colors = transparent())
            }
        }
    }
    if (isCompactHeight()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(horizontal = Space.s2), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp)) {
                PaneHeader(stringResource(R.string.settings), onBack, backTag = "settings_back")
                content(Modifier.fillMaxWidth())
            }
        }
    } else Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
            }
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), horizontalAlignment = Alignment.CenterHorizontally) {
            content(Modifier.widthIn(max = 640.dp).fillMaxWidth())
        }
    }
}

/** A section heading with its "Saved" note, then a card with the rows. */
@Composable
private fun SettingsSection(@StringRes title: Int, changes: Int, modifier: Modifier = Modifier, rows: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Space.s2), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(stringResource(title), Modifier.weight(1f, fill = false).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        SavedNote(changes)
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(modifier.padding(vertical = Space.s1), content = rows)
    }
}

@Composable
private fun <T> UnitChoice(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    spoken: (T) -> Int,
    tag: String,
    onSelect: (T) -> Unit,
) where T : Enum<T>, T : com.example.cvkegps.settings.StoredChoice {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        SegmentedChoice(options, selected, { stringResource(label(it)) }, onSelect,
            spoken = { stringResource(spoken(it)) }, tag = { "${tag}_unit_${it.key}" })
    }
}

/** A small label above a group of rows in a card. */
@Composable
private fun Label(@StringRes text: Int) {
    Text(stringResource(text), Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s3),
        style = MaterialTheme.typography.labelMedium, color = CvkeTheme.colors.inkMuted)
}

/** One choice of a group. The whole row is the touch target. */
@Composable
private fun ChoiceRow(@StringRes title: Int, @StringRes body: Int, selected: Boolean, tag: String, enabled: Boolean = true, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title), style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(stringResource(body)) },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        colors = transparent(enabled),
        modifier = Modifier.fillMaxWidth().selectable(selected, enabled, Role.RadioButton, onClick = onSelect).testTag(tag),
    )
}

/** An on/off setting. The whole row is one switch, so the touch target is the full row. */
@Composable
private fun SwitchRow(
    @StringRes title: Int,
    @StringRes body: Int?,
    checked: Boolean,
    tag: String,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title), style = MaterialTheme.typography.titleSmall) },
        supportingContent = body?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = transparent(enabled),
        modifier = Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Switch, onValueChange = onChange).testTag(tag),
    )
}

/** Rows sit on the card. A disabled row uses `ink-muted`, which keeps 4.5:1 contrast. */
@Composable
private fun transparent(enabled: Boolean = true) = CvkeTheme.colors.let {
    val text = if (enabled) it.ink else it.inkMuted
    ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = text, supportingColor = it.inkMuted)
}

@get:StringRes private val ScreenOn.title get() = when (this) {
    ScreenOn.Off -> R.string.settings_screen_off
    ScreenOn.DuringTrip -> R.string.settings_screen_trip
    ScreenOn.Always -> R.string.settings_screen_always
}

@get:StringRes private val ScreenOn.body get() = when (this) {
    ScreenOn.Off -> R.string.settings_screen_off_body
    ScreenOn.DuringTrip -> R.string.settings_screen_trip_body
    ScreenOn.Always -> R.string.settings_screen_always_body
}

@get:StringRes private val RecordingAccuracy.title get() = when (this) {
    RecordingAccuracy.Standard -> R.string.settings_recording_standard
    RecordingAccuracy.High -> R.string.settings_recording_high
}

@get:StringRes private val RecordingAccuracy.body get() = when (this) {
    RecordingAccuracy.Standard -> R.string.settings_recording_standard_body
    RecordingAccuracy.High -> R.string.settings_recording_high_body
}

@get:StringRes private val AppColours.title get() = when (this) {
    AppColours.App -> R.string.settings_colours_app
    AppColours.Backgrounds -> R.string.settings_colours_backgrounds
    AppColours.Wallpaper -> R.string.settings_colours_wallpaper
}

@get:StringRes private val AppColours.body get() = when (this) {
    AppColours.App -> R.string.settings_colours_app_body
    AppColours.Backgrounds -> R.string.settings_colours_backgrounds_body
    AppColours.Wallpaper -> R.string.settings_colours_wallpaper_body
}

@get:StringRes private val MapStart.title get() = when (this) {
    MapStart.North -> R.string.settings_map_start_north
    MapStart.Course -> R.string.settings_map_start_course
}

@get:StringRes private val RouteColour.title get() = when (this) {
    RouteColour.Single -> R.string.settings_route_single
    RouteColour.Speed -> R.string.settings_route_speed
}

@get:StringRes private val MapLayer.title get() = when (this) {
    MapLayer.Default -> R.string.map_layer_default
    MapLayer.Satellite -> R.string.map_layer_satellite
    MapLayer.Terrain -> R.string.map_layer_terrain
}
