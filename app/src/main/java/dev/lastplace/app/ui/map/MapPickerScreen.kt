package dev.lastplace.app.ui.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.lastplace.app.ParkingApp
import dev.lastplace.app.domain.model.LatLng
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Full-screen OpenStreetMap picker. Tap the map to drop a pin; confirm to return the point.
 * Kept self-contained so a map issue can't break the rest of the form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerScreen(
    initial: LatLng?,
    onPicked: (LatLng) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf(initial) }

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(17.0)
            val start = initial ?: LatLng(59.3293, 18.0686) // Stockholm fallback
            controller.setCenter(GeoPoint(start.lat, start.lng))
        }
    }
    val marker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            initial?.let { position = GeoPoint(it.lat, it.lng) }
        }
    }

    // Center on the user's current location when adding a new street (no initial point).
    val container = (context.applicationContext as ParkingApp).container
    val scope = rememberCoroutineScope()
    fun centerOnCurrentLocation() = scope.launch {
        container.locationProvider.current()?.let {
            mapView.controller.animateTo(GeoPoint(it.lat, it.lng))
        }
    }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) centerOnCurrentLocation() }
    LaunchedEffect(Unit) {
        if (initial == null) {
            if (container.locationProvider.hasPermission()) centerOnCurrentLocation()
            else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                picked = LatLng(p.latitude, p.longitude)
                marker.position = p
                if (!mapView.overlays.contains(marker)) mapView.overlays.add(marker)
                mapView.invalidate()
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        mapView.overlays.add(0, MapEventsOverlay(receiver))
        if (initial != null) mapView.overlays.add(marker)
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tap your street") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { picked?.let(onPicked) },
                        enabled = picked != null,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Confirm location")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

// Keys used to return the result through the nav back stack.
object MapPickerResult {
    const val LAT = "map_picker_lat"
    const val LNG = "map_picker_lng"
}
