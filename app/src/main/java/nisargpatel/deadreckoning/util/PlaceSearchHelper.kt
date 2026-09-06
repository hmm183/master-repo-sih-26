package nisargpatel.deadreckoning.util

import android.content.Context
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

data class PlaceSuggestion(
    val title: String,
    val subtitle: String,
    val point: GeoPoint,
    val distanceKm: Double? = null
)

object PlaceSearchHelper {
    private const val TAG = "PlaceSearchHelper"

    /**
     * Searches places by name or address query using OpenStreetMap Photon API (fast, free, global)
     * with fallback to Android system Geocoder.
     * Biases search around the user's proximity coordinates (lat, lon).
     */
    suspend fun searchPlaces(
        query: String,
        proximityLat: Double,
        proximityLon: Double,
        context: Context? = null
    ): List<PlaceSuggestion> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val results = mutableListOf<PlaceSuggestion>()
        val userPoint = GeoPoint(proximityLat, proximityLon)

        // 1. Try Photon Geocoding API (backed by OSM, fast autocomplete)
        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val urlString = "https://photon.komoot.io/api/?q=$encodedQuery&lat=$proximityLat&lon=$proximityLon&limit=6"
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 5000
                setRequestProperty("User-Agent", "DeadReckoningPro/1.0 (Android)")
            }

            if (conn.responseCode == 200) {
                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonText)
                val features = root.optJSONArray("features")
                if (features != null) {
                    for (i in 0 until features.length()) {
                        val feature = features.getJSONObject(i)
                        val geom = feature.optJSONObject("geometry")
                        val coords = geom?.optJSONArray("coordinates")
                        val props = feature.optJSONObject("properties")
                        if (coords != null && coords.length() >= 2 && props != null) {
                            val lon = coords.getDouble(0)
                            val lat = coords.getDouble(1)
                            val point = GeoPoint(lat, lon)

                            val name = props.optString("name", "")
                            val street = props.optString("street", "")
                            val city = props.optString("city", props.optString("county", ""))
                            val state = props.optString("state", "")
                            val country = props.optString("country", "")

                            val title = when {
                                name.isNotBlank() -> name
                                street.isNotBlank() -> street
                                city.isNotBlank() -> city
                                else -> trimmed
                            }

                            val subtitleParts = listOf(street, city, state, country).filter {
                                it.isNotBlank() && !it.equals(title, ignoreCase = true)
                            }.distinct()
                            val subtitle = if (subtitleParts.isNotEmpty()) subtitleParts.joinToString(", ") else "Location"

                            val dist = userPoint.distanceToAsDouble(point) / 1000.0
                            val distFormatted = (dist * 10.0).roundToInt() / 10.0

                            results.add(PlaceSuggestion(title, subtitle, point, distFormatted))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Photon geocoding error: ${e.message}")
        }

        // 2. Fallback to Android Geocoder if Photon was empty or unavailable
        if (results.isEmpty() && context != null) {
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(trimmed, 5)
                    if (!addresses.isNullOrEmpty()) {
                        for (addr in addresses) {
                            val point = GeoPoint(addr.latitude, addr.longitude)
                            val title = addr.featureName ?: addr.thoroughfare ?: addr.locality ?: trimmed
                            val subtitle = (0..addr.maxAddressLineIndex)
                                .mapNotNull { addr.getAddressLine(it) }
                                .filter { !it.equals(title, ignoreCase = true) }
                                .joinToString(", ")
                                .ifBlank { addr.adminArea ?: "Location" }
                            val dist = userPoint.distanceToAsDouble(point) / 1000.0
                            val distFormatted = (dist * 10.0).roundToInt() / 10.0
                            results.add(PlaceSuggestion(title, subtitle, point, distFormatted))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Android Geocoder error: ${e.message}")
            }
        }

        // Sort all suggestions by distance from user so closest landmarks/places are always first
        results.sortBy { it.distanceKm ?: Double.MAX_VALUE }
        return@withContext results
    }
}
