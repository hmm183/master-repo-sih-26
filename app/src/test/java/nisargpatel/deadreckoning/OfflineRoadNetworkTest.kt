package nisargpatel.deadreckoning

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

class OfflineRoadNetworkTest {

    private data class StoredNetwork(
        val packages: List<Map<String, Any>>,
        val segments: List<StoredSegment>
    )

    private data class StoredSegment(
        val wayId: Long,
        val name: String,
        val highway: String,
        val oneWay: Boolean,
        val points: List<StoredPoint>
    )

    private data class StoredPoint(val latitude: Double, val longitude: Double)

    @Test
    fun `bundled demo regional road network parses and indexes roads correctly`() {
        val assetFile = File("src/main/assets/roads/default_regional_network.json")
        assertTrue("default_regional_network.json asset must exist", assetFile.exists())

        val gson = Gson()
        val network: StoredNetwork = gson.fromJson(
            assetFile.readText(),
            object : TypeToken<StoredNetwork>() {}.type
        )

        assertNotNull("Network should parse successfully", network)
        assertTrue("Must contain at least 8 demonstration road segments", network.segments.size >= 8)

        val roadNames = network.segments.map { it.name }
        assertTrue("Must contain MG Road", roadNames.any { it.contains("MG Road") })
        assertTrue("Must contain NH16", roadNames.any { it.contains("NH16") })
        assertTrue("Must contain Benz Circle", roadNames.any { it.contains("Benz Circle") })

        // Verify valid coordinate ranges for Vijayawada region
        network.segments.forEach { seg ->
            assertTrue("Segment ${seg.name} must have at least 2 points", seg.points.size >= 2)
            seg.points.forEach { pt ->
                assertTrue("Latitude ${pt.latitude} must be in Andhra Pradesh range", pt.latitude in 16.0..17.0)
                assertTrue("Longitude ${pt.longitude} must be in Andhra Pradesh range", pt.longitude in 80.0..81.5)
            }
        }
    }

    @Test
    fun `nearest road query snaps correctly to Benz Circle`() {
        val assetFile = File("src/main/assets/roads/default_regional_network.json")
        val network: StoredNetwork = Gson().fromJson(
            assetFile.readText(),
            object : TypeToken<StoredNetwork>() {}.type
        )

        // Point right next to Benz Circle Rotary (16.5001, 80.6481)
        val queryLat = 16.5001
        val queryLon = 80.6481

        val nearest = network.segments.minByOrNull { seg ->
            seg.points.minOf { pt ->
                val dLat = (pt.latitude - queryLat) * 111_000.0
                val dLon = (pt.longitude - queryLon) * 111_000.0 * cos(Math.toRadians(queryLat))
                sqrt(dLat * dLat + dLon * dLon)
            }
        }

        assertNotNull(nearest)
        assertTrue(
            "Nearest segment must be Benz Circle or connecting MG Road/NH16 corridor",
            nearest!!.name.contains("Benz Circle") || nearest.name.contains("MG Road") || nearest.name.contains("NH16")
        )
    }
}
