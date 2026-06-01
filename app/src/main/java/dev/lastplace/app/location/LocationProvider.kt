package dev.lastplace.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.lastplace.app.domain.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.function.Consumer
import kotlin.coroutines.resume

/**
 * Foreground location + reverse geocoding. Uses the platform [LocationManager] and
 * [Geocoder] (no Google Play Services).
 *
 * Crucially [current] requests a **fresh** GPS fix rather than returning the last-known
 * location, which on Android is often stale by hours or completely missing. A stale
 * fix is the main cause of "Park where I am" matching the wrong street.
 */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Best-effort current location. Tries GPS first, then network, with a per-provider
     * timeout and an overall budget. Returns `null` rather than a stale fix on failure —
     * a wrong fix would silently match the wrong street.
     */
    suspend fun current(): LatLng? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        val manager = context.getSystemService(LocationManager::class.java) ?: return@withContext null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return@withContext null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
                providers.firstNotNullOfOrNull { provider ->
                    withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS) {
                        requestFreshFix(manager, provider)
                    }
                }
            }
        } else {
            // API 26-29: getCurrentLocation isn't available; degrade to last-known
            // (rare in 2026+; documented fallback rather than a crash path).
            providers
                .firstNotNullOfOrNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                ?.toLatLng()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
    private suspend fun requestFreshFix(
        manager: LocationManager,
        provider: String,
    ): LatLng? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            manager.getCurrentLocation(
                provider,
                signal,
                context.mainExecutor,
                Consumer { location ->
                    if (continuation.isActive) continuation.resume(location?.toLatLng())
                },
            )
        } catch (_: Throwable) {
            if (continuation.isActive) continuation.resume(null)
        }
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

    private fun Location.toLatLng(): LatLng = LatLng(latitude, longitude)

    companion object {
        private const val PER_PROVIDER_TIMEOUT_MS = 8_000L
        private const val OVERALL_TIMEOUT_MS = 15_000L
    }
}
