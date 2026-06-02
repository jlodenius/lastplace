package dev.lastplace.app.osm

import android.util.Log
import dev.lastplace.app.domain.StreetMatcher
import dev.lastplace.app.domain.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * A hard failure talking to an OSM service (non-2xx HTTP, network error, timeout) — as
 * opposed to a successful-but-empty result. Carries a short, user-presentable [message]
 * so the UI can say *why* a fetch failed instead of a generic "couldn't fetch".
 */
class OsmRequestException(message: String) : Exception(message)

/** A street search result from Photon. */
data class StreetSuggestion(
    val name: String,
    val label: String,   // name + locality, for display
    val point: LatLng,
)

/** The OSM road nearest a query point, with the geometry of that single way segment. */
data class NearestStreet(
    val name: String,
    val geometry: List<LatLng>,
)

/**
 * Talks to public OpenStreetMap services:
 *  - Photon (photon.komoot.io) for street autocomplete,
 *  - Overpass for the street's actual geometry.
 *
 * Both are free and keyless; we send a descriptive User-Agent per their usage policies.
 */
class OsmService(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun searchStreets(query: String, near: LatLng?): List<StreetSuggestion> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val url = buildString {
                append("https://photon.komoot.io/api/?q=")
                append(URLEncoder.encode(query, "UTF-8"))
                append("&limit=8")
                if (near != null) append("&lat=${near.lat}&lon=${near.lng}")
            }
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "searchStreets: HTTP ${response.code}")
                        return@use emptyList()
                    }
                    parsePhoton(response.body?.string().orEmpty())
                }
            }.onFailure { Log.w(TAG, "searchStreets: request failed", it) }
                .getOrDefault(emptyList())
        }

    /**
     * The OSM named road nearest to [point], together with the geometry of that single
     * way segment. Used after a map tap so we match OSM's own naming exactly, and so we
     * always have at least one segment of real street geometry to fall back to even if
     * a wider name-based fetch later turns up empty. Radius 150 m is forgiving of saved
     * points that are slightly off the road itself.
     */
    suspend fun nearestStreet(point: LatLng): NearestStreet? = withContext(Dispatchers.IO) {
        val query = """
            [out:json][timeout:25];
            way(around:150,${point.lat},${point.lng})["highway"]["name"];
            out geom;
        """.trimIndent()
        parseNearestStreet(overpass(query, "nearestStreet"), point)
    }

    /**
     * Fetches the polyline geometry of every way named [name] within [radiusMeters] of [near] —
     * i.e. the whole street, so parking anywhere along it matches. The wide radius covers most
     * city streets in full; same-named roads elsewhere are excluded by being local.
     */
    suspend fun fetchGeometry(name: String, near: LatLng, radiusMeters: Int = 5000): List<List<LatLng>> =
        withContext(Dispatchers.IO) {
            val safeName = name.replace("\"", "")
            val query = """
                [out:json][timeout:25];
                way["highway"]["name"="$safeName"](around:$radiusMeters,${near.lat},${near.lng});
                out geom;
            """.trimIndent()
            parseOverpass(overpass(query, "fetchGeometry"))
        }

    /**
     * POSTs an Overpass QL [query] and returns the response body. Logs and throws
     * [OsmRequestException] on any hard failure (non-2xx, network, timeout) so callers
     * can surface a specific reason; a successful-but-empty result comes back as a body
     * the parser turns into an empty list. [label] tags the log line for the call site.
     */
    private fun overpass(query: String, label: String): String {
        val request = Request.Builder()
            .url(OVERPASS_URL)
            .post(query.toRequestBody("text/plain".toMediaType()))
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        429 -> "rate limited (429) — too many requests, wait a moment"
                        in 500..599 -> "Overpass server error (${response.code})"
                        else -> "Overpass rejected the request (${response.code})"
                    }
                    Log.w(TAG, "$label: HTTP ${response.code} — ${body.take(300)}")
                    throw OsmRequestException(reason)
                }
                return body
            }
        } catch (e: IOException) {
            Log.w(TAG, "$label: network failure", e)
            throw OsmRequestException("network error (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun parsePhoton(body: String): List<StreetSuggestion> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val results = mutableListOf<Pair<StreetSuggestion, Boolean>>() // suggestion, isHighway
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val name = props.optString("name").ifBlank { props.optString("street") }
            if (name.isBlank()) continue
            val lng = coords.optDouble(0)
            val lat = coords.optDouble(1)
            val locality = listOf(props.optString("city"), props.optString("state"))
                .filter { it.isNotBlank() }
                .joinToString(", ")
            val label = if (locality.isBlank()) name else "$name — $locality"
            val isHighway = props.optString("osm_key") == "highway"
            results += StreetSuggestion(name, label, LatLng(lat, lng)) to isHighway
        }
        // Prefer actual streets, keep order otherwise.
        return results.sortedByDescending { it.second }.map { it.first }
    }

    /** Among the named ways returned, picks the one whose line is closest to [point]. */
    private fun parseNearestStreet(body: String, point: LatLng): NearestStreet? {
        val elements = JSONObject(body).optJSONArray("elements") ?: return null
        var best: NearestStreet? = null
        var bestDistance = Double.MAX_VALUE
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val name = element.optJSONObject("tags")?.optString("name").orEmpty()
            if (name.isBlank()) continue
            val geometry = element.optJSONArray("geometry") ?: continue
            val points = mutableListOf<LatLng>()
            for (j in 0 until geometry.length()) {
                val node = geometry.optJSONObject(j) ?: continue
                points += LatLng(node.optDouble("lat"), node.optDouble("lon"))
            }
            if (points.isEmpty()) continue
            val distance = StreetMatcher.distanceToPolylineMeters(point, points)
            if (distance < bestDistance) {
                bestDistance = distance
                best = NearestStreet(name, points)
            }
        }
        return best
    }

    private fun parseOverpass(body: String): List<List<LatLng>> {
        val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val ways = mutableListOf<List<LatLng>>()
        for (i in 0 until elements.length()) {
            val geometry = elements.optJSONObject(i)?.optJSONArray("geometry") ?: continue
            val points = mutableListOf<LatLng>()
            for (j in 0 until geometry.length()) {
                val node = geometry.optJSONObject(j) ?: continue
                points += LatLng(node.optDouble("lat"), node.optDouble("lon"))
            }
            if (points.isNotEmpty()) ways += points
        }
        return ways
    }

    companion object {
        private const val TAG = "OsmService"
        private const val USER_AGENT = "ParkingAssistant/0.1 (personal use)"
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    }
}
