package dev.lastplace.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.lastplace.app.domain.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Foreground location + reverse geocoding. Uses the platform [LocationManager] and
 * [Geocoder] (no Google Play Services) — sufficient for "use my location" and smart Park.
 */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(): LatLng? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            .firstNotNullOfOrNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            ?.let { LatLng(it.latitude, it.longitude) }
    }

    /** Best-effort reverse geocode to a street (thoroughfare) name. */
    @Suppress("DEPRECATION")
    suspend fun reverseStreetName(point: LatLng): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(context, Locale.getDefault())
                .getFromLocation(point.lat, point.lng, 1)
                ?.firstOrNull()
                ?.let { it.thoroughfare ?: it.featureName }
        }.getOrNull()
    }

    companion object {
        // Kept for callers that need to know the API level for fused-provider gating later.
        val supportsModernGeocoder: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
}
