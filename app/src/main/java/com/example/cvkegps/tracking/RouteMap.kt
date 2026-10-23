package com.example.cvkegps.tracking

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.cvkegps.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

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
    // Native resource caching honours the tile server's response headers. No offline download or prefetch.
    val style = """{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#e8e5df"}},{"id":"osm","type":"raster","source":"osm"}]}"""
}

@Composable
internal fun MapSurface(lines: List<List<TrackSample>>, modifier: Modifier = Modifier, live: Boolean = false) {
    var retry by rememberSaveable { mutableIntStateOf(0) }
    key(retry) { MapCanvas(lines, modifier, live, onRetry = { retry++ }) }
}

@Composable
private fun MapCanvas(lines: List<List<TrackSample>>, modifier: Modifier, live: Boolean, onRetry: () -> Unit) {
    val context = LocalContext.current
    val routePadding = with(LocalDensity.current) { 48.dp.roundToPx() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val description = stringResource(R.string.map_accessibility)
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
    LaunchedEffect(mapView) {
        delay(15_000)
        if (!loaded) unavailable = true
    }
    var mapStyle by remember { mutableStateOf<Style?>(null) }
    var nativeMap by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var positioned by remember { mutableStateOf(false) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var following by rememberSaveable { mutableStateOf(live) }
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
                if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false
            }
            map.addOnCameraIdleListener {
                if (positioned) {
                latitude = map.cameraPosition.target?.latitude
                longitude = map.cameraPosition.target?.longitude
                zoom = map.cameraPosition.zoom
                bearing = map.cameraPosition.bearing
                }
            }
            map.setStyle(Style.Builder().fromJson(OnlineMap.style)) { style ->
                style.addSource(GeoJsonSource("route", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                style.addLayer(LineLayer("route", "route").withProperties(lineColor("#1765c1"), lineWidth(4f)))
                for ((name, color) in listOf("start" to "#237a3b", "end" to "#b62929")) {
                    style.addSource(GeoJsonSource(name, FeatureCollection.fromFeatures(emptyArray<Feature>())))
                    style.addLayer(CircleLayer(name, name).withProperties(circleColor(color), circleRadius(7f), circleStrokeWidth(2f), circleStrokeColor("#ffffff")))
                }
                mapStyle = style
            }
        }
        onDispose {
            mapView.removeOnDidFailLoadingMapListener(failed)
            mapView.removeOnDidFinishRenderingMapListener(rendered)
        }
    }
    LaunchedEffect(mapStyle, lines, following, fitRequest) {
        val style = mapStyle ?: return@LaunchedEffect
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
        for ((name, sample) in listOf("start" to first, "end" to last)) {
            style.getSourceAs<GeoJsonSource>(name)?.setGeoJson(FeatureCollection.fromFeatures(
                sample?.let { listOf(Feature.fromGeometry(Point.fromLngLat(Math.toDegrees(it.longitudeRadians), Math.toDegrees(it.latitudeRadians)))) }.orEmpty()))
        }
        if (live && following && last != null) {
            nativeMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(Math.toDegrees(last.latitudeRadians), Math.toDegrees(last.longitudeRadians)),
                if (positioned) zoom.coerceIn(3.0, 18.0) else 14.0))
            positioned = true
        }
        if (fitRequest > 0 && !positioned) { latitude = null; longitude = null }
        // Review fits once. Route updates must not reset a user pan or reload the map style.
        if (!positioned && first != null) {
            val map = nativeMap ?: return@LaunchedEffect
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
    }
    Box(modifier.testTag("route_map")) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
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
        Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = MaterialTheme.shapes.medium,
            tonalElevation = 3.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (live) TextButton(onClick = { following = true }, enabled = !following && lines.any { it.isNotEmpty() },
                    modifier = Modifier.testTag("map_follow")) { Text(stringResource(R.string.map_follow)) }
                else TextButton(onClick = { positioned = false; fitRequest++ }, enabled = lines.any { it.isNotEmpty() },
                    modifier = Modifier.testTag("map_fit")) { Text(stringResource(R.string.map_fit)) }
                val zoomIn = stringResource(R.string.map_zoom_in)
                val zoomOut = stringResource(R.string.map_zoom_out)
                IconButton(onClick = { nativeMap?.animateCamera(CameraUpdateFactory.zoomIn()) },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = zoomIn }.testTag("map_zoom_in")) {
                    Text(stringResource(R.string.map_plus), style = MaterialTheme.typography.headlineSmall)
                }
                IconButton(onClick = { nativeMap?.animateCamera(CameraUpdateFactory.zoomOut()) },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = zoomOut }.testTag("map_zoom_out")) {
                    Text(stringResource(R.string.map_minus), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        if (lines.all { it.isEmpty() } || !loaded || unavailable) Surface(
            Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 56.dp),
            shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(8.dp)) {
                Text(stringResource(when {
                    lines.all { it.isEmpty() } -> R.string.map_no_points
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
