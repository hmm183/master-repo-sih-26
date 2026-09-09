package nisargpatel.deadreckoning

import nisargpatel.deadreckoning.core.gnss.GnssQuality
import nisargpatel.deadreckoning.domain.model.NavigationMode
import nisargpatel.deadreckoning.domain.state.GNSSState
import nisargpatel.deadreckoning.domain.state.NavigationState
import nisargpatel.deadreckoning.util.RouteRerouteGating
import org.junit.Assert.*
import org.junit.Test
import org.osmdroid.util.GeoPoint

class RerouteGatingTest {

    // A straight North-South road corridor
    private val routePoints = listOf(
        GeoPoint(12.9700, 77.5900),
        GeoPoint(12.9750, 77.5900),
        GeoPoint(12.9800, 77.5900)
    )

    @Test
    fun drDriftDuringBlackoutDoesNotTriggerReroute() {
        // Given: mid-route driving during a simulated GNSS blackout
        val blackoutGnss = GNSSState(
            isAvailable = false,
            usableForFusion = false,
            quality = GnssQuality.DENIED,
            outageDurationSeconds = 20L
        )
        val blackoutNav = NavigationState(
            isNavigating = true,
            mode = NavigationMode.AI_DEAD_RECKONING,
            outageDurationSeconds = 20L
        )

        // Trustworthiness check MUST fail during blackout
        assertFalse(
            "GNSS must NOT be trustworthy during blackout",
            RouteRerouteGating.isGnssTrustworthyForReroute(blackoutGnss, blackoutNav, hasFreshGnss = false)
        )

        // DR position drifts 60m East of the road centerline
        // Longitude delta: 60m / (111111 * cos(12.975)) ≈ 0.000554 deg
        val driftedDrPos = GeoPoint(12.9750, 77.5900 + 0.000554)

        // Evaluate reroute at tick 0
        val eval1 = RouteRerouteGating.shouldTriggerReroute(
            currentPosition = driftedDrPos,
            routePoints = routePoints,
            gnssState = blackoutGnss,
            navigationState = blackoutNav,
            isRerouting = false,
            consecutiveOffRouteCount = 0,
            hasFreshGnss = false
        )
        assertFalse("Reroute must NOT fire from DR drift during blackout", eval1.shouldReroute)
        assertTrue("Ticks should be reset to prevent stale accumulation", eval1.resetTicks)
        assertTrue("Reason should state GNSS untrusted/outage", eval1.reason.contains("GNSS untrusted/outage"))

        // Even with extreme 150m drift and repeated ticks, reroute must remain suppressed
        val extremeDriftPos = GeoPoint(12.9750, 77.5900 + 0.0015)
        val eval2 = RouteRerouteGating.shouldTriggerReroute(
            currentPosition = extremeDriftPos,
            routePoints = routePoints,
            gnssState = blackoutGnss,
            navigationState = blackoutNav,
            isRerouting = false,
            consecutiveOffRouteCount = 5,
            hasFreshGnss = false
        )
        assertFalse("Reroute must remain suppressed even under extreme DR drift", eval2.shouldReroute)
    }

    @Test
    fun confirmedGpsDeviationAfterOutageTriggersReroute() {
        // Given: GNSS is recovered, confirmed, and trustworthy
        val healthyGnss = GNSSState(
            isAvailable = true,
            usableForFusion = true,
            quality = GnssQuality.GOOD,
            accuracyMeters = 4.5,
            outageDurationSeconds = 0L
        )
        val healthyNav = NavigationState(
            isNavigating = true,
            mode = NavigationMode.GNSS_INS,
            accuracyMeters = 4.5,
            outageDurationSeconds = 0L,
            crossTrackUncertaintyMeters = 2.0,
            alongTrackUncertaintyMeters = 3.0
        )

        assertTrue(
            "GNSS should be trustworthy under good satellite fix",
            RouteRerouteGating.isGnssTrustworthyForReroute(healthyGnss, healthyNav)
        )

        // Case A: Position on route (within 5m) -> No reroute
        val onRoutePos = GeoPoint(12.9750, 77.5900 + 0.00004)
        val evalOnRoute = RouteRerouteGating.shouldTriggerReroute(
            currentPosition = onRoutePos,
            routePoints = routePoints,
            gnssState = healthyGnss,
            navigationState = healthyNav,
            isRerouting = false,
            consecutiveOffRouteCount = 0
        )
        assertFalse("No reroute when vehicle is on route", evalOnRoute.shouldReroute)
        assertTrue(evalOnRoute.resetTicks)

        // Case B: Confirmed immediate off-route deviation (> 50m) -> Triggers immediate reroute
        val largeDeviationPos = GeoPoint(12.9750, 77.5900 + 0.0006) // ~65m East
        val evalImmediate = RouteRerouteGating.shouldTriggerReroute(
            currentPosition = largeDeviationPos,
            routePoints = routePoints,
            gnssState = healthyGnss,
            navigationState = healthyNav,
            isRerouting = false,
            consecutiveOffRouteCount = 0
        )
        assertTrue("Immediate reroute must fire for confirmed >50m deviation", evalImmediate.shouldReroute)
        assertTrue(evalImmediate.distanceMeters > 50.0)

        // Case C: Moderate deviation (~35m) requires 2 confirmation ticks
        val moderateDeviationPos = GeoPoint(12.9750, 77.5900 + 0.00032) // ~35m East
        val evalTick1 = RouteRerouteGating.shouldTriggerReroute(
            currentPosition = moderateDeviationPos,
            routePoints = routePoints,
            gnssState = healthyGnss,
            navigationState = healthyNav,
            isRerouting = false,
            consecutiveOffRouteCount = 0
        )
        assertFalse("First tick of 35m deviation should await confirmation", evalTick1.shouldReroute)
        assertFalse("Ticks should not reset when deviation persists", evalTick1.resetTicks)

        val evalTick2 = RouteRerouteGating.shouldTriggerReroute(
            currentPosition = moderateDeviationPos,
            routePoints = routePoints,
            gnssState = healthyGnss,
            navigationState = healthyNav,
            isRerouting = false,
            consecutiveOffRouteCount = 1
        )
        assertTrue("Second consecutive tick of 35m deviation must trigger reroute", evalTick2.shouldReroute)
    }

    @Test
    fun uncertaintyAwareThresholdInflatesDuringReacquisition() {
        // Given: GNSS has just reacquired, but estimator covariance is still elevated
        val recoveringGnss = GNSSState(
            isAvailable = true,
            usableForFusion = true,
            quality = GnssQuality.DEGRADED,
            outageDurationSeconds = 0L
        )
        // High position uncertainty: crossTrack=25m, alongTrack=25m -> total uncertainty ~35.4m
        val highUncertaintyNav = NavigationState(
            isNavigating = true,
            mode = NavigationMode.GNSS_RECOVERY,
            crossTrackUncertaintyMeters = 25.0,
            alongTrackUncertaintyMeters = 25.0,
            outageDurationSeconds = 0L
        )

        val (baseThresh, immediateThresh) = RouteRerouteGating.computeOffRouteThresholds(highUncertaintyNav)
        assertTrue("Base threshold must inflate beyond default 30m when uncertainty is high", baseThresh > 50.0)
        assertTrue("Immediate threshold must inflate beyond default 50m when uncertainty is high", immediateThresh > 85.0)

        // A 40m deviation is within the 53m uncertainty envelope -> Should NOT reroute
        val ambiguousPos = GeoPoint(12.9750, 77.5900 + 0.00036) // ~39m
        val eval = RouteRerouteGating.shouldTriggerReroute(
            currentPosition = ambiguousPos,
            routePoints = routePoints,
            gnssState = recoveringGnss,
            navigationState = highUncertaintyNav,
            isRerouting = false,
            consecutiveOffRouteCount = 1
        )
        assertFalse("Uncertainty-aware threshold must prevent spurious rerouting", eval.shouldReroute)
    }

    @Test
    fun reroutingSuppressedWhenAlreadyInProgress() {
        val healthyGnss = GNSSState(isAvailable = true, usableForFusion = true, quality = GnssQuality.GOOD)
        val healthyNav = NavigationState(isNavigating = true, mode = NavigationMode.GNSS_INS)
        val offRoutePos = GeoPoint(12.9750, 77.5900 + 0.001)

        val eval = RouteRerouteGating.shouldTriggerReroute(
            currentPosition = offRoutePos,
            routePoints = routePoints,
            gnssState = healthyGnss,
            navigationState = healthyNav,
            isRerouting = true, // Already rerouting
            consecutiveOffRouteCount = 0
        )
        assertFalse("Must not trigger another reroute while already rerouting", eval.shouldReroute)
    }

    @Test
    fun rerouteSuppressedWhenHasFreshGnssIsFalseEvenIfGpsStateLooksAvailable() {
        // Given: GNSS is marked available, but fresh fixes stopped arriving (e.g. GnssQualityMonitor timeout)
        val staleGnss = GNSSState(
            isAvailable = true,
            usableForFusion = true,
            quality = GnssQuality.DEGRADED,
            outageDurationSeconds = 0L
        )
        val navState = NavigationState(
            isNavigating = true,
            mode = NavigationMode.GNSS_INS
        )

        assertFalse(
            "GNSS must be untrustworthy when hasFreshGnss is false",
            RouteRerouteGating.isGnssTrustworthyForReroute(staleGnss, navState, hasFreshGnss = false)
        )

        val offRoutePos = GeoPoint(12.9750, 77.5900 + 0.001)
        val eval = RouteRerouteGating.shouldTriggerReroute(
            currentPosition = offRoutePos,
            routePoints = routePoints,
            gnssState = staleGnss,
            navigationState = navState,
            isRerouting = false,
            consecutiveOffRouteCount = 2,
            hasFreshGnss = false
        )
        assertFalse("Reroute must NOT fire when hasFreshGnss is false", eval.shouldReroute)
        assertTrue("Ticks should be reset", eval.resetTicks)
        assertTrue(eval.reason.contains("fresh=false"))
    }
}
