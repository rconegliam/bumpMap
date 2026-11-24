package com.rconegliam.bumpmap.map

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rconegliam.bumpmap.R
import com.rconegliam.bumpmap.detection.DetectionStore
import com.rconegliam.bumpmap.detection.EventType
import com.rconegliam.bumpmap.detection.Geo
import com.rconegliam.bumpmap.detection.MEAN_SPEED
import com.rconegliam.bumpmap.detection.POTHOLE_COUNT
import com.rconegliam.bumpmap.detection.SPEED_BUMP_COUNT
import com.rconegliam.bumpmap.detection.RoadMapMetrics
import com.rconegliam.bumpmap.detection.RoadQuality
import com.rconegliam.bumpmap.detection.color
import com.rconegliam.bumpmap.detection.labelRes
import com.rconegliam.bumpmap.sync.PilotMapApi
import com.rconegliam.bumpmap.sync.PilotViewport
import com.rconegliam.bumpmap.sync.RecordingSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rconegliam.bumpmap.ui.currentLocale
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val DETECTIONS_SOURCE = "bumpmap-detections"
private const val SEGMENTS_LAYER = "bumpmap-segments"
private const val EVENTS_LAYER = "bumpmap-events"
private const val SPEED_LAYER = "bumpmap-speeds"
private const val MAP_QUALITY = "map_quality"

// Between Barueri and São Paulo, the beta cities.
private val START_POSITION = CameraPosition.Builder()
    .target(LatLng(-23.53, -46.76))
    .zoom(10.5)
    .build()

private enum class MapFilter(val label: Int, val event: EventType?, val count: String?) {
    ALL(R.string.map_filter_all, null, null),
    POTHOLES(R.string.map_filter_potholes, EventType.POTHOLE, POTHOLE_COUNT),
    BUMPS(R.string.map_filter_bumps, EventType.SPEED_BUMP, SPEED_BUMP_COUNT),
}

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val language = currentLocale().language
    val mapView = remember { MapView(context) }
    MapLifecycle(mapView)
    val detectionsVersion by DetectionStore.version.collectAsStateWithLifecycle()
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var localFeatures by remember { mutableStateOf<List<Feature>>(emptyList()) }
    var pilotViewport by remember { mutableStateOf<PilotViewport?>(null) }
    var viewport by remember { mutableStateOf<String?>(null) }
    val token = RecordingSync.token(context)
    var filter by rememberSaveable { mutableStateOf(MapFilter.ALL) }
    var showSpeed by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf<RoadMapMetrics?>(null) }
    val tapRadius = with(LocalDensity.current) { 12.dp.toPx() }
    val features = remember(localFeatures, pilotViewport) {
        (localFeatures + pilotViewport?.features.orEmpty()).distinctBy { it.id() }.onEach { feature ->
            RoadMapMetrics.from(feature)?.let { feature.addStringProperty(MAP_QUALITY, it.quality.code) }
        }
    }

    LaunchedEffect(detectionsVersion) {
        localFeatures = withContext(Dispatchers.IO) {
            DetectionStore.loadMapFeatures(context, RecordingSync.installationId(context))
        }
    }
    LaunchedEffect(token, viewport) {
        val bbox = viewport
        pilotViewport = if (token == null || bbox == null) null else withContext(Dispatchers.IO) {
            runCatching { PilotMapApi.readViewport(bbox, token) }.getOrNull()
        }
    }
    LaunchedEffect(loadedStyle, features) {
        loadedStyle?.getSourceAs<GeoJsonSource>(DETECTIONS_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
    LaunchedEffect(loadedStyle, filter, showSpeed) {
        loadedStyle?.applyFilters(filter, showSpeed)
    }
    DisposableEffect(map, tapRadius) {
        val currentMap = map ?: return@DisposableEffect onDispose {}
        val idleListener = MapLibreMap.OnCameraIdleListener { viewport = currentMap.viewportBbox() }
        val clickListener = MapLibreMap.OnMapClickListener { position ->
            val pixel = currentMap.projection.toScreenLocation(position)
            selection = currentMap.nearestRoad(pixel, tapRadius)
            selection != null
        }
        currentMap.addOnMapClickListener(clickListener)
        currentMap.addOnCameraIdleListener(idleListener)
        onDispose {
            currentMap.removeOnMapClickListener(clickListener)
            currentMap.removeOnCameraIdleListener(idleListener)
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    getMapAsync { readyMap ->
                        readyMap.cameraPosition = START_POSITION
                        readyMap.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                            localizeLabels(style, language)
                            addDetectionLayers(style)
                            loadedStyle = style
                            viewport = readyMap.viewportBbox()
                        }
                        map = readyMap
                    }
                }
            },
        )
        if (features.isEmpty()) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.map_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            MapControls(
                filter = filter,
                showSpeed = showSpeed,
                canZoomForMore = pilotViewport?.hasMore == true,
                onFilter = {
                    filter = it
                    selection = null
                },
                onShowSpeed = { showSpeed = !showSpeed },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
        }
        selection?.let { metrics ->
            RoadDetails(metrics, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
}

private fun MapLibreMap.nearestRoad(pixel: PointF, radius: Float): RoadMapMetrics? {
    val area = RectF(pixel.x - radius, pixel.y - radius, pixel.x + radius, pixel.y + radius)
    val candidates = queryRenderedFeatures(area, SEGMENTS_LAYER)
    var nearest: RoadMapMetrics? = null
    var bestDistance = radius.toDouble()
    for (feature in candidates) {
        val metrics = RoadMapMetrics.from(feature) ?: continue
        val line = feature.geometry() as LineString
        val points = line.coordinates().map { projection.toScreenLocation(LatLng(it.latitude(), it.longitude())) }
        val distance = points.zipWithNext { a, b ->
            Geo.distanceToSegment(
                (a.x - pixel.x).toDouble(), (a.y - pixel.y).toDouble(),
                (b.x - pixel.x).toDouble(), (b.y - pixel.y).toDouble(),
            )
        }.minOrNull() ?: continue
        if (distance < bestDistance) {
            bestDistance = distance
            nearest = metrics
        }
    }
    return nearest
}

private fun MapLibreMap.viewportBbox(): String? {
    val bounds = projection.visibleRegion.latLngBounds
    val west = bounds.longitudeWest
    val east = bounds.longitudeEast
    val south = bounds.latitudeSouth
    val north = bounds.latitudeNorth
    if (west !in -180.0..180.0 || east !in -180.0..180.0 ||
        east - west !in 0.000001..1.0 || north - south !in 0.000001..1.0
    ) return null
    return "$west,$south,$east,$north"
}

@Composable
private fun MapControls(
    filter: MapFilter,
    showSpeed: Boolean,
    canZoomForMore: Boolean,
    onFilter: (MapFilter) -> Unit,
    onShowSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MapFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { onFilter(option) },
                        label = { Text(stringResource(option.label)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoadQuality.entries.forEach { quality ->
                    LegendRow(ComposeColor(quality.color), stringResource(quality.labelRes), isLine = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EventType.entries.filter { filter.event == null || it == filter.event }.forEach { type ->
                    LegendRow(ComposeColor(type.color), stringResource(type.labelRes), isLine = false)
                }
            }
            FilterChip(
                selected = showSpeed,
                onClick = onShowSpeed,
                label = { Text(stringResource(R.string.map_show_speed)) },
            )
            if (canZoomForMore) {
                Text(stringResource(R.string.map_zoom_for_more), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RoadDetails(metrics: RoadMapMetrics, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendRow(ComposeColor(metrics.quality.color), stringResource(metrics.quality.labelRes), isLine = true)
            Text(stringResource(R.string.map_mean_speed_value, metrics.meanSpeedKmh))
            Text(stringResource(R.string.map_obstacle_counts, metrics.potholes, metrics.speedBumps))
            Text(stringResource(R.string.map_penalty_points, metrics.penaltyPoints))
        }
    }
}

@Composable
private fun LegendRow(color: ComposeColor, label: String, isLine: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = if (isLine) 18.dp else 10.dp, height = if (isLine) 5.dp else 10.dp)
                .background(color, if (isLine) RoundedCornerShape(2.dp) else CircleShape),
        )
        Spacer(Modifier.width(if (isLine) 6.dp else 14.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Road segments colored by quality, with potholes and speed bumps as dots on top. */
private fun addDetectionLayers(style: Style) {
    style.addSource(GeoJsonSource(DETECTIONS_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    style.addLayer(
        LineLayer(SEGMENTS_LAYER, DETECTIONS_SOURCE)
            .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("segment")))
            .withProperties(
                PropertyFactory.lineColor(
                    Expression.match(
                        Expression.get(MAP_QUALITY),
                        Expression.color(Color.GRAY),
                        *RoadQuality.entries.map { Expression.stop(it.code, Expression.color(it.color)) }.toTypedArray(),
                    ),
                ),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10, 2f),
                        Expression.stop(16, 7f),
                    ),
                ),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
    )
    style.addLayer(
        CircleLayer(EVENTS_LAYER, DETECTIONS_SOURCE)
            .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("event")))
            .withProperties(
                PropertyFactory.circleColor(
                    Expression.match(
                        Expression.get("type"),
                        Expression.color(Color.GRAY),
                        *EventType.entries.map { Expression.stop(it.code, Expression.color(it.color)) }.toTypedArray(),
                    ),
                ),
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(2f),
            ),
    )
    style.addLayer(
        SymbolLayer(SPEED_LAYER, DETECTIONS_SOURCE)
            .withProperties(
                PropertyFactory.visibility(Property.NONE),
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
                PropertyFactory.textField(
                    Expression.concat(
                        Expression.toString(Expression.round(Expression.get(MEAN_SPEED))),
                        Expression.literal(" km/h"),
                    ),
                ),
                PropertyFactory.textSize(12f),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textColor(Color.BLACK),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(2f),
            ),
    )
}

private fun Style.applyFilters(filter: MapFilter, showSpeed: Boolean) {
    val segments = Expression.eq(Expression.get("kind"), Expression.literal("segment"))
    val events = Expression.eq(Expression.get("kind"), Expression.literal("event"))
    val segmentFilter = filter.count?.let {
        Expression.all(segments, Expression.gt(Expression.get(it), Expression.literal(0)))
    } ?: segments
    val eventFilter = filter.event?.let {
        Expression.all(events, Expression.eq(Expression.get("type"), Expression.literal(it.code)))
    } ?: events
    getLayerAs<LineLayer>(SEGMENTS_LAYER)?.setFilter(segmentFilter)
    getLayerAs<CircleLayer>(EVENTS_LAYER)?.setFilter(eventFilter)
    getLayerAs<SymbolLayer>(SPEED_LAYER)?.apply {
        setFilter(segmentFilter)
        setProperties(PropertyFactory.visibility(if (showSpeed) Property.VISIBLE else Property.NONE))
    }
}

@Composable
private fun MapLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    mapView.onDestroy()
                    destroyed = true
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) {
                val state = lifecycle.currentState
                if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }
    }
}

/** Shows street and place names in the app language when OpenStreetMap has a translation. */
private fun localizeLabels(style: Style, language: String) {
    val localizedName = Expression.coalesce(
        Expression.get("name:$language"),
        Expression.get("name"),
    )
    style.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
        runCatching {
            val textField = layer.textField
            val text = when {
                textField.isExpression -> textField.expression.toString()
                textField.isValue -> textField.value?.formattedSections?.joinToString { it.text }.orEmpty()
                else -> ""
            }
            if (text.contains("name")) {
                layer.setProperties(PropertyFactory.textField(localizedName))
            }
        }
    }
}
