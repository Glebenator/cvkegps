package com.example.cvkegps.tracking

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.cvkegps.R
import com.example.cvkegps.location.TrackSample
import com.example.cvkegps.ui.FloatingBackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.log.Logger
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

private object OnlineMap {
    private var initialized = false
    fun initialize(context: android.content.Context) {
        if (initialized) return
        MapLibre.getInstance(context.applicationContext)
        HttpRequestUtil.setLogEnabled(false)
        Logger.setVerbosity(Logger.NONE)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "CVKEGPS/1.0 (Android; com.example.cvkegps)").build())
        }.build())
        initialized = true
    }

    /**
     * Native resource caching honours the tile server's response headers. No offline download or prefetch.
     * The dark theme dims and desaturates the same tiles on the device, so the tile source and its requests do not change.
     */
    fun style(dark: Boolean): String {
        val background = if (dark) "#1d201b" else "#e8e5df"
        val paint = if (dark) """"raster-brightness-max":0.62,"raster-saturation":-0.45,"raster-contrast":0.1""" else ""
        return """{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"$background"}},{"id":"osm","type":"raster","source":"osm","paint":{$paint}}]}"""
    }
}

/** The shortest time between two redraws of a live route line. The position marker still moves with each fix. */
private const val ROUTE_REDRAW_MILLIS = 5_000L

/** How the live map follows the newest position. A map gesture turns following off. */
private enum class FollowMode { Off, North, Course }

/** The newest saved position of a recording trip. [fresh] is false when the last fix is old. */
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
    val density = LocalDensity.current
    val routePadding = with(density) { 48.dp.roundToPx() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val description = stringResource(R.string.map_accessibility)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val routeColor = if (dark) "#8ab4f8" else "#1765c1"
    var loaded by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var latitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var longitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var zoom by rememberSaveable { mutableStateOf(12.0) }
    var bearing by rememberSaveable { mutableStateOf(0.0) }
    val mapView = remember {
        OnlineMap.initialize(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)
            .setPrefetchZoomDelta(0).attributionEnabled(false).logoEnabled(false)).apply {
            contentDescription = description
            onCreate(Bundle())
        }
    }
    DisposableEffect(mapView, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    // Tiles usually arrive during the screen transition. Show the loading note only when they are slow.
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(mapView) {
        delay(1_500)
        slow = true
        delay(13_500)
        if (!loaded) unavailable = true
    }
    var mapStyle by remember { mutableStateOf<Style?>(null) }
    var nativeMap by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var positioned by remember { mutableStateOf(false) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var follow by rememberSaveable { mutableStateOf(if (live) FollowMode.North else FollowMode.Off) }
    // Zoom follows speed until the zoom buttons change it.
    var userZoomed by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(mapView) {
        val failed = MapView.OnDidFailLoadingMapListener { unavailable = true }
        val rendered = MapView.OnDidFinishRenderingMapListener { complete -> if (complete) { loaded = true; unavailable = false } }
        mapView.addOnDidFailLoadingMapListener(failed)
        mapView.addOnDidFinishRenderingMapListener(rendered)
        mapView.getMapAsync { map ->
            nativeMap = map
            map.setPrefetchZoomDelta(0)
            map.uiSettings.isTiltGesturesEnabled = false
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = FollowMode.Off
            }
            map.addOnCameraIdleListener {
                if (positioned) {
                latitude = map.cameraPosition.target?.latitude
                longitude = map.cameraPosition.target?.longitude
                zoom = map.cameraPosition.zoom
                bearing = map.cameraPosition.bearing
                }
            }
            map.setStyle(Style.Builder().fromJson(OnlineMap.style(dark))) { style ->
                style.addImage("heading", headingArrow(with(density) { 22.dp.roundToPx() }, routeColor))
                style.addSource(GeoJsonSource("route", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addLayer(LineLayer("route", "route").withProperties(lineColor(routeColor), lineWidth(4f)))
                for ((name, color) in listOf("start" to "#237a3b", "end" to "#b62929")) {
                    style.addSource(GeoJsonSource(name, FeatureCollection.fromFeatures(emptyArray<Feature>())))
                    style.addLayer(CircleLayer(name, name).withProperties(circleColor(color), circleRadius(7f), circleStrokeWidth(2f), circleStrokeColor("#ffffff")))
                }
                style.addSource(GeoJsonSource("accuracy", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addLayer(FillLayer("accuracy", "accuracy").withProperties(fillColor(routeColor), fillOpacity(0.18f)))
                style.addSource(GeoJsonSource("position", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addLayer(CircleLayer("position", "position").withProperties(circleColor(routeColor), circleRadius(8f),
                    circleStrokeWidth(3f), circleStrokeColor("#ffffff")))
                style.addSource(GeoJsonSource("heading", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addLayer(SymbolLayer("heading", "heading").withProperties(iconImage("heading"), iconRotate(Expression.get("bearing")),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconOffset(arrayOf(0f, -16f)),
                    iconAllowOverlap(true), iconIgnorePlacement(true)))
                mapStyle = style
            }
        }
        onDispose {
            mapView.removeOnDidFailLoadingMapListener(failed)
            mapView.removeOnDidFinishRenderingMapListener(rendered)
        }
    }
    // The live marker: an accuracy circle, a grey dot for an old fix, and a direction arrow only while moving with a current fix.
    LaunchedEffect(mapStyle, position) {
        val style = mapStyle ?: return@LaunchedEffect
        val sample = position?.sample
        val point = sample?.let { Point.fromLngLat(Math.toDegrees(it.longitudeRadians), Math.toDegrees(it.latitudeRadians)) }
        style.getSourceAs<GeoJsonSource>("position")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(point?.let(Feature::fromGeometry))))
        style.getLayerAs<CircleLayer>("position")?.setProperties(circleColor(if (position?.fresh == true) routeColor else "#8a8f87"))
        val accuracy = sample?.accuracyMeters?.takeIf { position.fresh && sample.usablePosition && it > 0f }
        style.getSourceAs<GeoJsonSource>("accuracy")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(
            accuracy?.let { Feature.fromGeometry(circle(sample, it.toDouble())) })))
        val course = sample?.takeIf { position.fresh }?.let(::courseRadians)
        style.getSourceAs<GeoJsonSource>("heading")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(
            course?.let { radians -> Feature.fromGeometry(point).apply { addNumberProperty("bearing", Math.toDegrees(radians)) } })))
    }
    // The route line. A live route redraws at most every ROUTE_REDRAW_MILLIS, so a long trip does not rebuild the line for each fix.
    val latestLines by rememberUpdatedState(lines)
    LaunchedEffect(mapStyle, fitRequest) {
        val style = mapStyle ?: return@LaunchedEffect
        snapshotFlow { latestLines }.conflate().collect { lines ->
            val geometry = withContext(Dispatchers.Default) {
                FeatureCollection.fromFeatures(lines.flatMap { line ->
                    val pieces = mutableListOf<MutableList<TrackSample>>()
                    for (sample in line) {
                        val previous = pieces.lastOrNull()?.lastOrNull()
                        if (previous == null || kotlin.math.abs(sample.longitudeRadians - previous.longitudeRadians) > Math.PI) pieces += mutableListOf<TrackSample>()
                        pieces.last().add(sample)
                    }
                    pieces.filter { it.size > 1 }.map { piece ->
                        Feature.fromGeometry(LineString.fromLngLats(piece.map { Point.fromLngLat(Math.toDegrees(it.longitudeRadians), Math.toDegrees(it.latitudeRadians)) }))
                    }
                })
            }
            style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(geometry)
            val first = lines.firstOrNull { it.isNotEmpty() }?.first()
            val last = lines.lastOrNull { it.isNotEmpty() }?.last()
            // While recording, the position marker shows the newest point instead of an end marker.
            for ((name, sample) in listOf("start" to first, "end" to last.takeIf { !live })) {
                style.getSourceAs<GeoJsonSource>(name)?.setGeoJson(FeatureCollection.fromFeatures(
                    sample?.let { listOf(Feature.fromGeometry(Point.fromLngLat(Math.toDegrees(it.longitudeRadians), Math.toDegrees(it.latitudeRadians)))) }.orEmpty()))
            }
            if (fitRequest > 0 && !positioned) { latitude = null; longitude = null }
            // Review fits once. Route updates must not reset a user pan or reload the map style.
            // A live map that follows the position places the camera itself.
            val map = nativeMap
            if (!positioned && first != null && map != null && !(live && follow != FollowMode.Off)) {
                if (latitude != null && longitude != null) {
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(latitude!!, longitude!!)).zoom(zoom).bearing(bearing).build()))
                } else {
                    val bounds = withContext(Dispatchers.Default) { lines.flatten().map { LatLng(Math.toDegrees(it.latitudeRadians), Math.toDegrees(it.longitudeRadians)) } }
                    if (bounds.distinct().size > 1) {
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(bounds).build(), routePadding))
                        // Keep short routes at a useful street scale.
                        if (map.cameraPosition.zoom > 17.0) map.moveCamera(CameraUpdateFactory.zoomTo(17.0))
                    }
                    else map.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.first(), 14.0))
                }
                positioned = true
            }
            if (live) delay(ROUTE_REDRAW_MILLIS)
        }
    }
    // A live map that follows moves to each new position. The route line can update later.
    LaunchedEffect(mapStyle, nativeMap, position?.sample, follow) {
        val last = position?.sample ?: return@LaunchedEffect
        val map = nativeMap ?: return@LaunchedEffect
        if (!live || follow == FollowMode.Off || mapStyle == null) return@LaunchedEffect
        val target = LatLng(Math.toDegrees(last.latitudeRadians), Math.toDegrees(last.longitudeRadians))
        val nextZoom = when {
            !positioned -> speedZoom(last) ?: 15.0
            userZoomed -> map.cameraPosition.zoom.coerceIn(3.0, 18.0)
            else -> speedZoom(last) ?: map.cameraPosition.zoom.coerceIn(3.0, 18.0)
        }
        val nextBearing = if (follow == FollowMode.Course) courseRadians(last)?.let(Math::toDegrees) ?: map.cameraPosition.bearing else 0.0
        val camera = CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(target).zoom(nextZoom).bearing(nextBearing).build())
        // One short camera move for each new fix. There is no continuous animation.
        if (positioned) map.animateCamera(camera, 800) else map.moveCamera(camera)
        positioned = true
    }
    Box(modifier.testTag("route_map")) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        // The map can run under the system bars. Its controls stay inside the safe area.
        Box(Modifier.matchParentSize().windowInsetsPadding(insets)) {
            // Keep the visible caption small and the touch target at least 48 dp high.
            Box(Modifier.align(Alignment.BottomStart).heightIn(min = 48.dp)
                .clickable(role = Role.Button) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.openstreetmap.org/copyright".toUri()))
                }, contentAlignment = Alignment.BottomStart) {
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                    Text(stringResource(R.string.map_attribution),
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (onBack != null) FloatingBackButton(onBack, Modifier.align(Alignment.TopStart).padding(8.dp))
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val hasRoute = lines.any { it.isNotEmpty() }
                if (live) {
                    val label = stringResource(if (follow == FollowMode.Course) R.string.map_follow_course else R.string.map_follow)
                    val state = stringResource(when (follow) {
                        FollowMode.Off -> R.string.map_follow_off
                        FollowMode.North -> R.string.map_follow_north
                        FollowMode.Course -> R.string.map_follow_direction
                    })
                    // The first selection follows with north up. The next selection turns the map to the direction of travel.
                    FilledTonalIconToggleButton(checked = follow != FollowMode.Off, enabled = hasRoute,
                        onCheckedChange = {
                            follow = if (follow == FollowMode.North) FollowMode.Course else FollowMode.North
                            userZoomed = false
                        },
                        modifier = Modifier.size(48.dp).testTag("map_follow").semantics { stateDescription = state }) {
                        Icon(painterResource(if (follow == FollowMode.Course) R.drawable.ic_navigation else R.drawable.ic_my_location),
                            contentDescription = label)
                    }
                } else FilledTonalIconButton(onClick = { positioned = false; fitRequest++ }, enabled = hasRoute,
                    modifier = Modifier.size(48.dp).testTag("map_fit")) {
                    Icon(painterResource(R.drawable.ic_fit_route), contentDescription = stringResource(R.string.map_fit))
                }
                FilledTonalIconButton(onClick = { userZoomed = true; nativeMap?.animateCamera(CameraUpdateFactory.zoomIn()) },
                    modifier = Modifier.size(48.dp).testTag("map_zoom_in")) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.map_zoom_in))
                }
                FilledTonalIconButton(onClick = { userZoomed = true; nativeMap?.animateCamera(CameraUpdateFactory.zoomOut()) },
                    modifier = Modifier.size(48.dp).testTag("map_zoom_out")) {
                    Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.map_zoom_out))
                }
            }
            // A route that is still loading is not reported as empty. The map style loads at the same time.
            val noPoints = routeReady && lines.all { it.isEmpty() }
            if (noPoints || (!loaded && slow) || unavailable) Surface(
                Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 56.dp),
                shape = MaterialTheme.shapes.small) {
                Column(Modifier.padding(8.dp)) {
                    Text(stringResource(when {
                        noPoints -> R.string.map_no_points
                        unavailable -> R.string.map_unavailable
                        else -> R.string.map_loading
                    }), style = MaterialTheme.typography.bodySmall)
                    if (unavailable) TextButton(onClick = onRetry, modifier = Modifier.testTag("map_retry")) {
                        Text(stringResource(R.string.map_retry))
                    }
                }
            }
        }
    }
}

/** Direction of travel, only from a usable speed of at least 1 m/s. A slow or stopped fix has no reliable direction. */
private fun courseRadians(sample: TrackSample): Double? =
    sample.bearingRadians?.takeIf { it.isFinite() && (sample.usableSpeed ?: 0f) >= 1f }

/** A street scale at low speed and a wider view at road and plane speeds. */
private fun speedZoom(sample: TrackSample): Double? = sample.usableSpeed?.let { speed ->
    when {
        speed < 3f -> 17.0
        speed < 10f -> 16.0
        speed < 25f -> 15.0
        speed < 60f -> 14.0
        else -> 11.0
    }
}

/** A polygon for the horizontal accuracy around a sample. Small radii make a flat-earth approximation sufficient. */
private fun circle(sample: TrackSample, radiusMeters: Double): Polygon {
    val latitude = Math.toDegrees(sample.latitudeRadians)
    val longitude = Math.toDegrees(sample.longitudeRadians)
    val dLat = Math.toDegrees(radiusMeters / 6_371_008.8)
    val dLon = dLat / cos(sample.latitudeRadians).coerceAtLeast(0.01)
    val ring = (0..48).map { step ->
        val angle = 2 * Math.PI * step / 48
        Point.fromLngLat(longitude + dLon * cos(angle), latitude + dLat * sin(angle))
    }
    return Polygon.fromLngLats(listOf(ring))
}

/** An arrow that points up. The symbol layer turns it to the direction of travel. */
private fun headingArrow(size: Int, color: String): Bitmap {
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val path = Path().apply {
        moveTo(size / 2f, size * 0.08f)
        lineTo(size * 0.88f, size * 0.9f)
        lineTo(size / 2f, size * 0.7f)
        lineTo(size * 0.12f, size * 0.9f)
        close()
    }
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = size * 0.12f; strokeJoin = Paint.Join.ROUND })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toColorInt() })
    return bitmap
}
