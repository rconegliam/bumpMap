package com.rconegliam.bumpmap.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rconegliam.bumpmap.R
import com.rconegliam.bumpmap.ui.currentLocale
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

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

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        map.cameraPosition = START_POSITION
                        map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                            localizeLabels(style, language)
                        }
                    }
                }
            },
        )
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
