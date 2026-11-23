package com.rconegliam.bumpmap.map

import android.graphics.Color
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rconegliam.bumpmap.R
import com.rconegliam.bumpmap.detection.DetectionStore
import com.rconegliam.bumpmap.detection.EventType
import com.rconegliam.bumpmap.detection.GeoJson
import com.rconegliam.bumpmap.detection.RoadQuality
import com.rconegliam.bumpmap.detection.color
import com.rconegliam.bumpmap.detection.labelRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rconegliam.bumpmap.ui.currentLocale
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val DETECTIONS_SOURCE = "bumpmap-detections"
private const val SEGMENTS_LAYER = "bumpmap-segments"
private const val EVENTS_LAYER = "bumpmap-events"

// Between Barueri and São Paulo, the beta cities.
private val START_POSITION = CameraPosition.Builder()
    .target(LatLng(-23.53, -46.76))
    .zoom(10.5)
    .build()

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val language = currentLocale().language
    val mapView = remember { MapView(context) }
    MapLifecycle(mapView)
    val detectionsVersion by DetectionStore.version.collectAsStateWithLifecycle()
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var featureCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(loadedStyle, detectionsVersion) {
        val style = loadedStyle ?: return@LaunchedEffect
        val features = withContext(Dispatchers.IO) { DetectionStore.loadFeatures(context) }
        featureCount = features.size
        if (style.isFullyLoaded) {
            style.getSourceAs<GeoJsonSource>(DETECTIONS_SOURCE)?.setGeoJson(GeoJson.collection(features))
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        map.cameraPosition = START_POSITION
                        map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                            localizeLabels(style, language)
                            addDetectionLayers(style)
                            loadedStyle = style
                        }
                    }
                }
            },
        )
        if (featureCount == 0) {
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
            Legend(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RoadQuality.entries.forEach { quality ->
                LegendRow(ComposeColor(quality.color), stringResource(quality.labelRes), isLine = true)
            }
            EventType.entries.forEach { type ->
                LegendRow(ComposeColor(type.color), stringResource(type.labelRes), isLine = false)
            }
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
    style.addSource(GeoJsonSource(DETECTIONS_SOURCE, GeoJson.collection(emptyList())))
    style.addLayer(
        LineLayer(SEGMENTS_LAYER, DETECTIONS_SOURCE)
            .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("segment")))
            .withProperties(
                PropertyFactory.lineColor(
                    Expression.match(
                        Expression.get("quality"),
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
