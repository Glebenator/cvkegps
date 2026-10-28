package com.example.cvkegps.tracking

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.cvkegps.R
import com.example.cvkegps.ui.theme.CvkeTheme
import com.example.cvkegps.ui.theme.mapButtonColors
import com.example.cvkegps.ui.theme.mapButtonModifier
import com.example.cvkegps.ui.theme.mapToggleColors
import com.example.cvkegps.ui.theme.Space
import com.example.cvkegps.location.TrackSample
import com.example.cvkegps.settings.MapLayer
import com.example.cvkegps.ui.FloatingBackButton
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The shortest time between two redraws of a live route line. The position marker still moves with each fix. */
private const val ROUTE_REDRAW_MILLIS = 5_000L

/** The camera tilt while the map follows with the direction of travel up. It shows more of the road ahead. */
private const val COURSE_TILT = 45f

/** The space for one column or row of 48 dp map buttons and their margins. */
private val CONTROL_SPACE = 64.dp

/** The space around a fitted route, so the start and end markers are not at the edge. */
private val ROUTE_MARGIN = 48.dp

/** How the live map follows the newest position. A map gesture turns following off. */
private enum class FollowMode { Off, North, Course }

/** The newest plotted position of a recording trip. [fresh] is false when that position is old. */
data class LivePosition(val sample: TrackSample, val fresh: Boolean)

@Composable
internal fun MapSurface(
    lines: List<List<TrackSample>>,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    routeReady: Boolean = true,
    insets: WindowInsets = WindowInsets(0),
    onBack: (() -> Unit)? = null,
    position: LivePosition? = null,
) {
    var retry by rememberSaveable { mutableIntStateOf(0) }
    key(retry) { MapCanvas(lines, modifier, live, routeReady, insets, onBack, position, onRetry = { retry++ }) }
}

@Composable
private fun MapCanvas(
    lines: List<List<TrackSample>>,
    modifier: Modifier,
    live: Boolean,
    routeReady: Boolean,
    insets: WindowInsets,
    onBack: (() -> Unit)?,
    position: LivePosition?,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val settings = (context.applicationContext as TrackingApplication).settings
    val choice by settings.values.collectAsStateWithLifecycle()
    val description = stringResource(R.string.map_accessibility)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // The route uses the one accent, so it matches the app in both themes.
    val routeColor = CvkeTheme.colors.accent
    // A missing or old Google Play services result. Null while the check runs or when the services are ready.
    val servicesProblem = rememberPlayServices()?.takeIf { it != ConnectionResult.SUCCESS }
    val scope = rememberCoroutineScope()
    val camera = rememberCameraPositionState()
    var loaded by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    // The map usually loads during the screen transition. Show the loading note only when it is slow.
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1_500)
        slow = true
        delay(13_500)
        if (!loaded) unavailable = true
    }
    // True after the camera first shows the route or the position. It is saved, so a recreated screen keeps the user's view.
    var placed by rememberSaveable { mutableStateOf(false) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var follow by rememberSaveable { mutableStateOf(if (live) FollowMode.North else FollowMode.Off) }
    // Zoom follows speed until the zoom buttons change it.
    var userZoomed by rememberSaveable { mutableStateOf(false) }
    // A map gesture stops following. The compass turns the map to north, so following continues with north up.
    LaunchedEffect(camera) {
        snapshotFlow { camera.isMoving to camera.cameraMoveStartedReason }.collect { (moving, reason) ->
            if (moving && reason == CameraMoveStartedReason.GESTURE) follow = FollowMode.Off
            else if (moving && reason == CameraMoveStartedReason.API_ANIMATION && follow == FollowMode.Course) follow = FollowMode.North
        }
    }

    // The marker state is also read aloud, because its colour alone does not tell a screen reader user that it is old.
    val positionState = position?.let { stringResource(if (it.fresh) R.string.map_position_current else R.string.map_position_old) }
    BoxWithConstraints(modifier.testTag("route_map").semantics { if (positionState != null) stateDescription = positionState }) {
        // The map can run under the system bars. Its controls, the compass, and the Google logo stay inside the safe area,
        // and the camera centres the route in the part of the map that the buttons do not cover.
        val safe = insets.asPaddingValues()
        val direction = LocalLayoutDirection.current
        // A short map, such as in a small window, shows the buttons in a row.
        val shortMap = maxHeight - safe.calculateTopPadding() - safe.calculateBottomPadding() < 240.dp
        val padding = PaddingValues(
            start = safe.calculateStartPadding(direction),
            top = safe.calculateTopPadding() + if (shortMap || onBack != null) CONTROL_SPACE else 0.dp,
            end = safe.calculateEndPadding(direction) + if (shortMap) 0.dp else CONTROL_SPACE,
            bottom = safe.calculateBottomPadding(),
        )
        val fitWidth = (maxWidth - padding.calculateStartPadding(direction) - padding.calculateEndPadding(direction) - ROUTE_MARGIN * 2)
            .coerceAtLeast(ROUTE_MARGIN).value.toDouble()
        val fitHeight = (maxHeight - padding.calculateTopPadding() - padding.calculateBottomPadding() - ROUTE_MARGIN * 2)
            .coerceAtLeast(ROUTE_MARGIN).value.toDouble()
        val fitArea by rememberUpdatedState(fitWidth to fitHeight)

        // The route line. A live route redraws at most every ROUTE_REDRAW_MILLIS, so a long trip does not rebuild the line for each fix.
        var route by remember { mutableStateOf<List<List<LatLng>>>(emptyList()) }
        val latestLines by rememberUpdatedState(lines)
        LaunchedEffect(fitRequest) {
            snapshotFlow { latestLines }.conflate().collect { lines ->
                route = withContext(Dispatchers.Default) { lines.filter { it.size > 1 }.map { line -> line.map { it.latLng() } } }
                // Review fits once. Route updates must not reset a user pan. A live map that follows the position places the camera itself.
                if (!placed && !(live && follow != FollowMode.Off)) {
                    val (width, height) = fitArea
                    val frame = withContext(Dispatchers.Default) { RouteMath.frame(lines.flatten(), width, height) }
                    if (frame != null) {
                        camera.move(CameraUpdateFactory.newCameraPosition(CameraPosition(
                            LatLng(Math.toDegrees(frame.latitudeRadians), Math.toDegrees(frame.longitudeRadians)), frame.zoom.toFloat(), 0f, 0f)))
                        placed = true
                    }
                }
                if (live) delay(ROUTE_REDRAW_MILLIS)
            }
        }
        // A live map that follows moves to each new position. The route line can update later.
        LaunchedEffect(position?.sample, follow) {
            val last = position?.sample ?: return@LaunchedEffect
            if (!live || follow == FollowMode.Off) return@LaunchedEffect
            val current = camera.position
            val zoom = when {
                !placed -> speedZoom(last) ?: 15f
                userZoomed -> current.zoom.coerceIn(3f, 18f)
                else -> speedZoom(last) ?: current.zoom.coerceIn(3f, 18f)
            }
            val course = follow == FollowMode.Course
            val bearing = if (course) courseRadians(last)?.let { Math.toDegrees(it).toFloat() } ?: current.bearing else 0f
            val update = CameraUpdateFactory.newCameraPosition(CameraPosition(last.latLng(), zoom, if (course) COURSE_TILT else 0f, bearing))
            // One short camera move for each new fix. There is no continuous animation.
            if (placed) camera.animate(update, 800) else camera.move(update)
            placed = true
        }

        if (servicesProblem == null) GoogleMap(
            modifier = Modifier.matchParentSize(),
            cameraPositionState = camera,
            contentDescription = description,
            properties = MapProperties(
                mapType = when (choice.mapLayer) {
                    MapLayer.Default -> MapType.NORMAL
                    MapLayer.Satellite -> MapType.HYBRID
                    MapLayer.Terrain -> MapType.TERRAIN
                },
                // Traffic is current, so it shows only on the map of the current trip.
                isTrafficEnabled = live && choice.mapTraffic,
                isBuildingEnabled = true,
            ),
            // The app's 48 dp buttons replace the small SDK zoom controls. The marker toolbar opens a saved start or end in Google Maps.
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                indoorLevelPickerEnabled = false,
                mapToolbarEnabled = !live,
            ),
            onMapLoaded = { loaded = true; unavailable = false },
            contentPadding = padding,
            mapColorScheme = if (dark) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
        ) {
            RouteOverlay(route, lines, live, position, routeColor)
        }
        // The controls stay inside the safe area.
        Box(Modifier.matchParentSize().windowInsetsPadding(insets)) {
            if (onBack != null) FloatingBackButton(onBack, Modifier.align(Alignment.TopStart).padding(Space.s2))
            val controls: @Composable () -> Unit = {
                val hasRoute = lines.any { it.isNotEmpty() }
                MapTypeButton(choice.mapLayer, choice.mapTraffic.takeIf { live }, settings::setMapLayer, settings::setMapTraffic)
                if (live) {
                    val label = stringResource(if (follow == FollowMode.Course) R.string.map_follow_course else R.string.map_follow)
                    val state = stringResource(when (follow) {
                        FollowMode.Off -> R.string.map_follow_off
                        FollowMode.North -> R.string.map_follow_north
                        FollowMode.Course -> R.string.map_follow_direction
                    })
                    // The first selection follows with north up. The next selection turns the map to the direction of travel.
                    FilledTonalIconToggleButton(checked = follow != FollowMode.Off, enabled = hasRoute, colors = mapToggleColors(),
                        onCheckedChange = {
                            follow = if (follow == FollowMode.North) FollowMode.Course else FollowMode.North
                            userZoomed = false
                        },
                        modifier = mapButtonModifier.testTag("map_follow").semantics { stateDescription = state }) {
                        Icon(painterResource(if (follow == FollowMode.Course) R.drawable.ic_navigation else R.drawable.ic_my_location),
                            contentDescription = label)
                    }
                } else FilledTonalIconButton(onClick = { placed = false; fitRequest++ }, enabled = hasRoute, colors = mapButtonColors(),
                    modifier = mapButtonModifier.testTag("map_fit")) {
                    Icon(painterResource(R.drawable.ic_fit_route), contentDescription = stringResource(R.string.map_fit))
                }
                FilledTonalIconButton(onClick = { userZoomed = true; scope.launch { camera.animate(CameraUpdateFactory.zoomIn()) } },
                    colors = mapButtonColors(), modifier = mapButtonModifier.testTag("map_zoom_in")) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.map_zoom_in))
                }
                FilledTonalIconButton(onClick = { userZoomed = true; scope.launch { camera.animate(CameraUpdateFactory.zoomOut()) } },
                    colors = mapButtonColors(), modifier = mapButtonModifier.testTag("map_zoom_out")) {
                    Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.map_zoom_out))
                }
            }
            if (servicesProblem == null) {
                if (!shortMap) Column(Modifier.align(Alignment.TopEnd).padding(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) { controls() }
                else Row(Modifier.align(Alignment.TopEnd).padding(Space.s2), horizontalArrangement = Arrangement.spacedBy(Space.s2)) { controls() }
            }
            // A route that is still loading is not reported as empty. The map loads at the same time.
            val noPoints = routeReady && lines.all { it.isEmpty() }
            if (servicesProblem != null || noPoints || (!loaded && slow) || unavailable) Surface(
                // The note stays above the Google logo, which must stay visible.
                Modifier.align(Alignment.BottomCenter).padding(start = Space.s2, end = Space.s2, bottom = 56.dp),
                color = CvkeTheme.colors.surface, shape = MaterialTheme.shapes.small, shadowElevation = 6.dp) {
                Column(Modifier.padding(Space.s2)) {
                    Text(stringResource(when {
                        servicesProblem != null -> R.string.map_services_missing
                        noPoints -> R.string.map_no_points
                        unavailable -> R.string.map_unavailable
                        else -> R.string.map_loading
                    }), style = MaterialTheme.typography.bodySmall)
                    if (servicesProblem != null) PlayServicesAction(servicesProblem)
                    else if (unavailable) TextButton(onClick = onRetry, modifier = Modifier.testTag("map_retry")) {
                        Text(stringResource(R.string.map_retry))
                    }
                }
            }
        }
    }
}

/** The route line, the start and end markers, and the live position. Only the map content can make marker images. */
@Composable
private fun RouteOverlay(route: List<List<LatLng>>, lines: List<List<TrackSample>>, live: Boolean, position: LivePosition?, routeColor: Color) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val icons = remember(density, routeColor) { MapIcons(density, routeColor.toArgb()) }
    val lineWidth = with(density) { 4.dp.toPx() }
    for (line in route) Polyline(line, color = routeColor, width = lineWidth, jointType = JointType.ROUND,
        startCap = RoundCap(), endCap = RoundCap(), zIndex = 1f)
    val first = lines.firstOrNull { it.isNotEmpty() }?.first()
    // While recording, the position marker shows the newest point instead of an end marker.
    val last = lines.lastOrNull { it.isNotEmpty() }?.last()?.takeIf { !live }
    for ((sample, title, icon) in listOf(Triple(first, R.string.map_start_marker, icons.start), Triple(last, R.string.map_end_marker, icons.end))) {
        if (sample == null) continue
        key(title) {
            val name = stringResource(title)
            val time = DateUtils.formatDateTime(context, sample.timeMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
            // A live map shows the time without a camera move, so following continues.
            Marker(rememberUpdatedMarkerState(sample.latLng()), contentDescription = stringResource(R.string.map_marker_description, name, time),
                anchor = Offset(0.5f, 0.5f),
                icon = icon, title = name, snippet = time, zIndex = 2f,
                onClick = { marker -> if (live) { marker.showInfoWindow(); true } else false })
        }
    }
    // The live marker: an accuracy circle, a grey dot for an old fix, and a direction arrow only while moving with a current fix.
    if (position != null) {
        val at = position.sample.latLng()
        val accuracy = position.sample.accuracyMeters?.takeIf { position.fresh && position.sample.usablePosition && it > 0f }
        if (accuracy != null) Circle(at, fillColor = routeColor.copy(alpha = 0.18f), radius = accuracy.toDouble(),
            strokeWidth = 0f, zIndex = 2f)
        val course = position.sample.takeIf { position.fresh }?.let(::courseRadians)
        // A flat marker turns with the map, so the arrow keeps the true direction when the map is rotated.
        if (course != null) Marker(rememberUpdatedMarkerState(at), contentDescription = null, anchor = Offset(0.5f, 0.5f), flat = true,
            icon = icons.heading, rotation = Math.toDegrees(course).toFloat(), zIndex = 3f, onClick = { true })
        Marker(rememberUpdatedMarkerState(at),
            contentDescription = stringResource(if (position.fresh) R.string.map_position_current else R.string.map_position_old),
            anchor = Offset(0.5f, 0.5f), icon = if (position.fresh) icons.position else icons.oldPosition, zIndex = 4f, onClick = { true })
    }
}

/** Selects the map type. On the current trip map it also turns live traffic on or off. */
@Composable
private fun MapTypeButton(layer: MapLayer, traffic: Boolean?, onLayer: (MapLayer) -> Unit, onTraffic: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(onClick = { open = true }, colors = mapButtonColors(), modifier = mapButtonModifier.testTag("map_layers")) {
            Icon(painterResource(R.drawable.ic_layers), contentDescription = stringResource(R.string.map_type))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((option, label) in listOf(MapLayer.Default to R.string.map_layer_default, MapLayer.Satellite to R.string.map_layer_satellite,
                MapLayer.Terrain to R.string.map_layer_terrain)) {
                val selected = option == layer
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onLayer(option); open = false },
                    modifier = Modifier.testTag("map_layer_${option.key}").semantics { role = Role.RadioButton; this.selected = selected },
                    leadingIcon = { RadioButton(selected = selected, onClick = null) })
            }
            if (traffic != null) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.map_traffic)) }, onClick = { onTraffic(!traffic); open = false },
                    modifier = Modifier.testTag("map_traffic").semantics { role = Role.Checkbox; toggleableState = ToggleableState(traffic) },
                    leadingIcon = { Checkbox(checked = traffic, onCheckedChange = null) })
            }
        }
    }
}

/** Opens the Google Play services dialog that installs, updates, or turns on the services that the map needs. */
@Composable
private fun PlayServicesAction(code: Int) {
    val activity = LocalActivity.current ?: return
    val availability = GoogleApiAvailability.getInstance()
    if (!availability.isUserResolvableError(code)) return
    TextButton(onClick = { availability.showErrorDialogFragment(activity, code, 0) }, modifier = Modifier.testTag("map_services")) {
        Text(stringResource(when (code) {
            ConnectionResult.SERVICE_MISSING -> R.string.map_services_install
            ConnectionResult.SERVICE_DISABLED -> R.string.map_services_enable
            else -> R.string.map_services_update
        }))
    }
}

/** The Google Play services result for the map, or null before the first check. It checks again when the screen resumes. */
@Composable
private fun rememberPlayServices(): Int? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<Int?>(null, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value = withContext(Dispatchers.Default) { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) }
        }
    }.value
}

private fun TrackSample.latLng() = LatLng(Math.toDegrees(latitudeRadians), Math.toDegrees(longitudeRadians))

/** Direction of travel, only from a usable speed of at least 1 m/s. A slow or stopped fix has no reliable direction. */
private fun courseRadians(sample: TrackSample): Double? =
    sample.bearingRadians?.takeIf { it.isFinite() && (sample.usableSpeed ?: 0f) >= 1f }

/** A street scale at low speed and a wider view at road and plane speeds. */
private fun speedZoom(sample: TrackSample): Float? = sample.usableSpeed?.let { speed ->
    when {
        speed < 3f -> 17f
        speed < 10f -> 16f
        speed < 25f -> 15f
        speed < 60f -> 14f
        else -> 11f
    }
}

/** Marker images. Make them only in the map content, after the Maps SDK is ready. */
private class MapIcons(density: Density, route: Int) {
    private val scale = density.density
    val start = dot(7f, 2f, 0xFF237A3B.toInt())
    val end = dot(7f, 2f, 0xFFB62929.toInt())
    val position = dot(8f, 3f, route)
    val oldPosition = dot(8f, 3f, 0xFF8A8F87.toInt())
    val heading = headingArrow(22f, route)

    /** A filled circle with a white edge. Sizes are in dp. */
    private fun dot(radius: Float, edge: Float, color: Int): BitmapDescriptor {
        val outer = (radius + edge) * scale
        val bitmap = createBitmap((outer * 2).toInt() + 2, (outer * 2).toInt() + 2)
        val canvas = Canvas(bitmap)
        val centre = bitmap.width / 2f
        canvas.drawCircle(centre, centre, outer, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE })
        canvas.drawCircle(centre, centre, radius * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /**
     * An arrow that points up, at the top of a square image. The centre of the image is on the position,
     * so a turn about the centre puts the arrow in front of the position dot.
     */
    private fun headingArrow(length: Float, color: Int): BitmapDescriptor {
        val size = (length * scale).let { (it * 2.9f).toInt() }
        val arrow = length * scale
        val bitmap: Bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val middle = size / 2f
        val path = Path().apply {
            moveTo(middle, arrow * 0.08f)
            lineTo(middle + arrow * 0.38f, arrow * 0.9f)
            lineTo(middle, arrow * 0.7f)
            lineTo(middle - arrow * 0.38f, arrow * 0.9f)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = arrow * 0.12f; strokeJoin = Paint.Join.ROUND })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
