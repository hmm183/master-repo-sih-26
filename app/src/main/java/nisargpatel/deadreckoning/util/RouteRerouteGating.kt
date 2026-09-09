package nisargpatel.deadreckoning.util

import nisargpatel.deadreckoning.core.gnss.GnssQuality
import nisargpatel.deadreckoning.domain.model.NavigationMode
import nisargpatel.deadreckoning.domain.state.GNSSState
import nisargpatel.deadreckoning.domain.state.NavigationState
import org.osmdroid.util.GeoPoint

/**
 * Evaluates whether an automatic off-route recalculation is permissible and warranted.
 *
 * During a GNSS blackout / outage, dead-reckoned positions are unconfirmed and subject
 * to IMU integration drift. Triggering automatic recalculations from DR drift would abandon
 * the planned route and query routing engines for irrelevant coordinates in the middle of
 * a GNSS-denied test or tunnel.
 *
 * This gating mechanism:
 * 1. Suppresses automatic reroutes whenever GNSS is not fresh, usable, and confirmed.
 * 2. Holds the active route manifold so RBPF and map-matching can constrain particles.
 * 3. Incorporates estimator horizontal covariance into the off-route distance threshold
 *    to guard against momentary spikes in position uncertainty during GPS reacquisition.
 */
object RouteRerouteGating {

    const val DEFAULT_BASE_THRESHOLD_METERS = 30.0
    const val DEFAULT_IMMEDIATE_THRESHOLD_METERS = 50.0

    /**
     * True only when GNSS is active, usable for fusion, not denied, and not in an outage.
     */
    fun isGnssTrustworthyForReroute(
        gnssState: GNSSState,
        navigationState: NavigationState
    ): Boolean {
        if (!gnssState.isAvailable) return false
        if (!gnssState.usableForFusion) return false
        if (navigationState.mode == NavigationMode.AI_DEAD_RECKONING) return false
        if (gnssState.quality == GnssQuality.DENIED) return false
        if (gnssState.outageDurationSeconds > 0L || navigationState.outageDurationSeconds > 0L) return false
        return true
    }

    /**
     * Incorporates the estimator's own reported horizontal covariance into the distance
     * threshold, ensuring that an off-route trigger requires the deviation to clearly
     * exceed the current position uncertainty envelope.
     */
    fun computeOffRouteThresholds(
        navigationState: NavigationState,
        baseThresholdMeters: Double = DEFAULT_BASE_THRESHOLD_METERS,
        immediateThresholdMeters: Double = DEFAULT_IMMEDIATE_THRESHOLD_METERS
    ): Pair<Double, Double> {
        val horizontalUncertainty = kotlin.math.sqrt(
            navigationState.crossTrackUncertaintyMeters * navigationState.crossTrackUncertaintyMeters +
            navigationState.alongTrackUncertaintyMeters * navigationState.alongTrackUncertaintyMeters
        ).coerceAtLeast(0.0)

        val effectiveBase = baseThresholdMeters.coerceAtLeast(horizontalUncertainty * 1.5)
        val effectiveImmediate = immediateThresholdMeters.coerceAtLeast(horizontalUncertainty * 2.5)
        return Pair(effectiveBase, effectiveImmediate)
    }

    /**
     * Evaluates whether an off-route recalculation should fire.
     */
    fun shouldTriggerReroute(
        currentPosition: GeoPoint?,
        routePoints: List<GeoPoint>,
        gnssState: GNSSState,
        navigationState: NavigationState,
        isRerouting: Boolean,
        consecutiveOffRouteCount: Int
    ): RerouteEvaluation {
        if (isRerouting) {
            return RerouteEvaluation(shouldReroute = false, resetTicks = false, reason = "Rerouting already in progress")
        }
        if (currentPosition == null || routePoints.size < 2) {
            return RerouteEvaluation(shouldReroute = false, resetTicks = true, reason = "Invalid route or position")
        }

        // Gate 1: GNSS quality & outage gate. During outages, hold route for RBPF/map matching.
        if (!isGnssTrustworthyForReroute(gnssState, navigationState)) {
            return RerouteEvaluation(
                shouldReroute = false,
                resetTicks = true,
                reason = "GNSS untrusted/outage (mode=${navigationState.mode}, quality=${gnssState.quality}, outage=${navigationState.outageDurationSeconds}s); holding route"
            )
        }

        // Gate 2: Geometric route distance match with uncertainty-aware thresholds
        val match = RouteMapMatcher.match(currentPosition, routePoints)
        val distance = match?.distanceMeters ?: Double.MAX_VALUE
        val (baseThresh, immediateThresh) = computeOffRouteThresholds(navigationState)

        return if (distance > baseThresh) {
            val shouldReroute = (consecutiveOffRouteCount + 1 >= 2) || (distance > immediateThresh)
            RerouteEvaluation(
                shouldReroute = shouldReroute,
                resetTicks = false,
                distanceMeters = distance,
                thresholdMeters = if (distance > immediateThresh) immediateThresh else baseThresh,
                reason = if (shouldReroute) {
                    "Confirmed off-route deviation (${String.format("%.1f", distance)}m > threshold ${String.format("%.1f", if (distance > immediateThresh) immediateThresh else baseThresh)}m)"
                } else {
                    "Deviation detected (${String.format("%.1f", distance)}m > ${String.format("%.1f", baseThresh)}m), awaiting confirmation tick"
                }
            )
        } else {
            RerouteEvaluation(shouldReroute = false, resetTicks = true, distanceMeters = distance, reason = "On route")
        }
    }
}

data class RerouteEvaluation(
    val shouldReroute: Boolean,
    val resetTicks: Boolean,
    val distanceMeters: Double = 0.0,
    val thresholdMeters: Double = RouteRerouteGating.DEFAULT_BASE_THRESHOLD_METERS,
    val reason: String = ""
)
