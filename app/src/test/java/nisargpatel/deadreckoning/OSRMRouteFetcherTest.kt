package nisargpatel.deadreckoning

import nisargpatel.deadreckoning.util.OSRMRouteFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint

class OSRMRouteFetcherTest {

    @Before
    fun setUp() {
        OSRMRouteFetcher.clearCache()
    }

    @Test
    fun `generateStreetGridRoute produces valid route with alternatives`() {
        val start = GeoPoint(16.5062, 80.6480)
        val end = GeoPoint(16.5150, 80.6600)
        val destination = "Benz Circle"

        val route = OSRMRouteFetcher.generateStreetGridRoute(start, end, destination)

        assertEquals("Current Location", route.sourceName)
        assertEquals("Benz Circle", route.destinationName)
        assertEquals(start, route.sourcePoint)
        assertEquals(end, route.destinationPoint)
        assertTrue(route.routePoints.size >= 2)
        assertEquals(start, route.routePoints.first())
        assertEquals(end, route.routePoints.last())
        assertTrue(route.totalDistanceKm > 0.0)
        assertTrue(route.estimatedTimeMinutes > 0)
        assertNotNull(route.nextManeuver)
        assertTrue(route.alternatives.size >= 2)
    }

    @Test
    fun `cached route retrieval returns cached route for exact and nearby endpoints`() {
        val start = GeoPoint(16.5062, 80.6480)
        val end = GeoPoint(16.5150, 80.6600)
        val route = OSRMRouteFetcher.generateStreetGridRoute(start, end, "Benz Circle")

        assertNull(OSRMRouteFetcher.getCachedRoute(start, end))

        OSRMRouteFetcher.putCachedRoute(start, end, route)
        assertEquals(1, OSRMRouteFetcher.cacheSize)

        // Exact match
        val cachedExact = OSRMRouteFetcher.getCachedRoute(start, end)
        assertNotNull(cachedExact)
        assertEquals("Benz Circle", cachedExact?.destinationName)

        // Nearby match (within ~30m)
        val nearbyStart = GeoPoint(16.5063, 80.6481)
        val nearbyEnd = GeoPoint(16.5151, 80.6601)
        val cachedNearby = OSRMRouteFetcher.getCachedRoute(nearbyStart, nearbyEnd)
        assertNotNull(cachedNearby)
        assertEquals("Benz Circle", cachedNearby?.destinationName)
    }

    @Test
    fun `clearCache removes all cached routes`() {
        val start = GeoPoint(16.5062, 80.6480)
        val end = GeoPoint(16.5150, 80.6600)
        val route = OSRMRouteFetcher.generateStreetGridRoute(start, end, "Test Point")

        OSRMRouteFetcher.putCachedRoute(start, end, route)
        assertEquals(1, OSRMRouteFetcher.cacheSize)

        OSRMRouteFetcher.clearCache()
        assertEquals(0, OSRMRouteFetcher.cacheSize)
        assertNull(OSRMRouteFetcher.getCachedRoute(start, end))
    }
}
