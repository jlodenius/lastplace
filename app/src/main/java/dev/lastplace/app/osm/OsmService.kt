package dev.lastplace.app.osm

import dev.lastplace.app.domain.StreetMatcher
import dev.lastplace.app.domain.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/** A street search result from Photon. */
data class StreetSuggestion(
    val name: String,
    val label: String,   // name + locality, for display
    val point: LatLng,
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
                    if (!response.isSuccessful) return@use emptyList()
                    parsePhoton(response.body?.string().orEmpty())
                }
            }.getOrDefault(emptyList())
        }

    /**
     * The name of the named road nearest to [point] (per OSM). Used after a map tap so we
     * match OSM's own naming exactly, instead of guessing via reverse geocoding.
     */
    suspend fun nearestStreetName(point: LatLng): String? = withContext(Dispatchers.IO) {
        val query = """
            [out:json][timeout:25];
            way(around:50,${point.lat},${point.lng})["highway"]["name"];
            out geometry;
        """.trimIndent()
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(query.toRequestBody("text/plain".toMediaType()))
            .header("User-Agent", USER_AGENT)
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseNearestName(response.body?.string().orEmpty(), point)
            }
        }.getOrNull()
    }

    /**
     * Fetches the polyline geometry of every way named [name] within [radiusMeters] of [near] —
     * i.e. the whole street, so parking anywhere along it matches. The wide radius covers most
     * city streets in full; same-named roads elsewhere are excluded by being local.
     */
    suspend fun fetchGeometry(name: String, near: LatLng, radiusMeters: Int = 3000): List<List<LatLng>> =
        withContext(Dispatchers.IO) {
            val safeName = name.replace("\"", "")
            val query = """
                [out:json][timeout:25];
                way["highway"]["name"="$safeName"](around:$radiusMeters,${near.lat},${near.lng});
                out geometry;
            """.trimIndent()
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post(query.toRequestBody("text/plain".toMediaType()))
                .header("User-Agent", USER_AGENT)
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    parseOverpass(response.body?.string().orEmpty())
                }
            }.getOrDefault(emptyList())
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
    private fun parseNearestName(body: String, point: LatLng): String? {
        val elements = JSONObject(body).optJSONArray("elements") ?: return null
        var bestName: String? = null
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
                bestName = name
            }
        }
        return bestName
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
        private const val USER_AGENT = "ParkingAssistant/0.1 (personal use)"
    }
}
