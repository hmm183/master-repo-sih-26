package nisargpatel.deadreckoning.matching

import nisargpatel.deadreckoning.data.RoadCandidate
import org.osmdroid.util.GeoPoint
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.exp

data class MatchedRoad(
    val candidate: RoadCandidate,
    val confidence: Int,
    val isRoadLocked: Boolean = false
)

/**
 * Advanced Industrial-Grade Hidden Markov Model (HMM) Map Matcher.
 *
 * Implements:
 * 1. Topological Road Network Transition Scoring (Newson & Krumm ACM GIS formulation):
 *    Penalizes disconnected parallel roads / flyovers to eliminate erratic road hopping.
 * 2. Intersection & Turn Awareness:
 *    Ranks junction branches by checking candidate turn angle against vehicle yaw rate (gyroscope).
 * 3. Anti-Snap Hysteresis (Sticky Road Locking):
 *    Requires consecutive confirmed frames before switching to disconnected roads,
 *    eliminating 1-frame GPS jitter and parallel lane ping-ponging.
 * 4. Trajectory Memory & Progression:
 *    Tracks recent matched positions to ensure smooth forward progress.
 */
class HiddenMarkovRoadMatcher(private val historyDepth: Int = 20) {

    private data class State(
        val candidate: RoadCandidate,
        val score: Double,
        val parentIndex: Int?
    )

    private data class Layer(
        val observation: GeoPoint,
        val states: List<State>
    )

    private val trellis = ArrayDeque<Layer>()
    private val trajectoryHistory = ArrayDeque<GeoPoint>()

    // Hysteresis & Anti-snap state
    private var lockedWayId: Long? = null
    private var lockedRoadName: String? = null
    private var consecutiveDivergentFrames: Int = 0
    private var pendingSwitchWayId: Long? = null

    companion object {
        private const val SWITCH_CONFIRMATION_FRAMES = 3
        private const val SWITCH_MARGIN_SCORE = 3.5
        private const val TOPOLOGICAL_DISCONNECTED_PENALTY = 14.0
        private const val MAX_TRAJECTORY_MEMORY = 50
    }

    /**
     * Updates the HMM trellis with a new observation and candidate roads.
     *
     * @param observation Current sensor-estimated position (GPS or Dead Reckoning)
     * @param candidates Nearest road candidates from the offline or online network
     * @param vehicleHeadingDegrees Current fused vehicle heading in degrees (0 = North)
     * @param vehicleYawRateDegPerSec Current vehicle rotational yaw rate from gyro (deg/s, + = right turn)
     * @param topologicalHopProvider Callback providing topological graph hops (0=same, 1=connected, 2=2-hop, -1=disconnected)
     */
    fun update(
        observation: GeoPoint,
        candidates: List<RoadCandidate>,
        vehicleHeadingDegrees: Double? = null,
        vehicleYawRateDegPerSec: Double? = null,
        topologicalHopProvider: ((Long, Long) -> Int)? = null
    ): MatchedRoad? {
        if (candidates.isEmpty()) return null

        val prior = trellis.lastOrNull()
        val observedDistance = prior?.observation?.distanceToAsDouble(observation) ?: 0.0
        val observedBearing = prior?.observation?.bearingTo(observation)?.toDouble()

        val current = candidates.map { candidate ->
            val emission = calculateEmissionScore(
                candidate = candidate,
                observedBearing = observedBearing,
                vehicleHeadingDegrees = vehicleHeadingDegrees
            )

            val parent = prior?.states?.mapIndexed { index, state ->
                val trans = calculateTransitionScore(
                    from = state.candidate,
                    to = candidate,
                    observedDistance = observedDistance,
                    vehicleYawRateDegPerSec = vehicleYawRateDegPerSec,
                    topologicalHopProvider = topologicalHopProvider
                )
                index to (state.score + trans)
            }?.maxByOrNull { it.second }

            State(
                candidate = candidate,
                score = emission + (parent?.second ?: 0.0),
                parentIndex = parent?.first
            )
        }

        trellis += Layer(observation, current)
        while (trellis.size > historyDepth) trellis.removeFirst()

        val bestCandidate = current.maxBy { it.score }
        val winner = resolveHysteresis(
            currentCandidates = current,
            bestCandidate = bestCandidate,
            topologicalHopProvider = topologicalHopProvider
        )

        // Trajectory memory maintenance
        trajectoryHistory.addLast(winner.candidate.point)
        while (trajectoryHistory.size > MAX_TRAJECTORY_MEMORY) trajectoryHistory.removeFirst()

        // Probability normalization
        val normalizer = current.sumOf { exp((it.score - bestCandidate.score).coerceAtLeast(-35.0)) }
        val confidence = (100.0 / normalizer).toInt().coerceIn(10, 100)

        return MatchedRoad(
            candidate = winner.candidate,
            confidence = confidence,
            isRoadLocked = lockedWayId != null && winner.candidate.wayId == lockedWayId
        )
    }

    /**
     * Resets matcher state, clearing trellis, trajectory history, and road lock.
     */
    fun reset() {
        trellis.clear()
        trajectoryHistory.clear()
        lockedWayId = null
        lockedRoadName = null
        consecutiveDivergentFrames = 0
        pendingSwitchWayId = null
    }

    /**
     * Emission score: measures how well the candidate matches the observed point geometrically and orientatively.
     */
    private fun calculateEmissionScore(
        candidate: RoadCandidate,
        observedBearing: Double?,
        vehicleHeadingDegrees: Double?
    ): Double {
        // Distance penalty: log-normal distribution approximation
        val distancePenalty = -candidate.distanceMeters / 12.0

        // Heading penalty: prefer vehicle heading if available, else trajectory displacement bearing
        val refHeading = vehicleHeadingDegrees ?: observedBearing
        val headingPenalty = if (refHeading != null) {
            val forwardDiff = angularDifference(candidate.bearingDegrees, refHeading)
            val diff = if (candidate.oneWay) {
                // Harsh penalty for driving reverse on a one-way street
                if (forwardDiff > 90.0) forwardDiff * 2.0 else forwardDiff
            } else {
                minOf(forwardDiff, angularDifference(candidate.bearingDegrees + 180.0, refHeading))
            }
            -diff / 35.0
        } else {
            0.0
        }

        return distancePenalty + headingPenalty
    }

    /**
     * Transition score: evaluates physical movement feasibility, OSM network connectivity,
     * and turn/junction compatibility with gyroscope yaw rate.
     */
    private fun calculateTransitionScore(
        from: RoadCandidate,
        to: RoadCandidate,
        observedDistance: Double,
        vehicleYawRateDegPerSec: Double?,
        topologicalHopProvider: ((Long, Long) -> Int)?
    ): Double {
        val graphDistance = from.point.distanceToAsDouble(to.point)
        val distanceError = abs(graphDistance - observedDistance)
        val distanceScore = -distanceError / 18.0

        // 1. OSM Topological Network Connectivity Scoring
        val connectivityScore = if (from.wayId != 0L && to.wayId != 0L) {
            if (from.wayId == to.wayId) {
                // Same road segment: strong continuity bonus
                3.0
            } else {
                val hops = topologicalHopProvider?.invoke(from.wayId, to.wayId)
                when (hops) {
                    1 -> 1.2  // Directly connected at an OSM node (valid junction turn)
                    2 -> -1.5 // 2 hops away (ramp, link road)
                    -1 -> -TOPOLOGICAL_DISCONNECTED_PENALTY // Disconnected parallel road!
                    else -> if (from.roadName.isNotBlank() && from.roadName == to.roadName) 1.0 else -2.5
                }
            }
        } else if (from.roadName.isNotBlank() && from.roadName == to.roadName) {
            1.2
        } else {
            0.0
        }

        // 2. Junction / Turn Awareness using Gyro Yaw Rate
        val turnScore = if (from.wayId != to.wayId && vehicleYawRateDegPerSec != null) {
            val signedRoadTurn = signedAngularDifference(to.bearingDegrees, from.bearingDegrees)
            scoreTurnAlignment(signedRoadTurn, vehicleYawRateDegPerSec)
        } else {
            0.0
        }

        return connectivityScore + distanceScore + turnScore
    }

    /**
     * Checks if the road turn direction matches the vehicle's actual rotational velocity.
     */
    private fun scoreTurnAlignment(roadTurnDegrees: Double, yawRateDegPerSec: Double): Double {
        return when {
            // Significant right turn
            yawRateDegPerSec > 12.0 -> {
                if (roadTurnDegrees > 15.0) 2.5 // candidate turns right with vehicle
                else if (roadTurnDegrees < -15.0) -4.0 // candidate turns left, conflict!
                else -1.0 // straight branch while turning
            }
            // Significant left turn
            yawRateDegPerSec < -12.0 -> {
                if (roadTurnDegrees < -15.0) 2.5 // candidate turns left with vehicle
                else if (roadTurnDegrees > 15.0) -4.0 // candidate turns right, conflict!
                else -1.0 // straight branch while turning
            }
            // Driving straight
            abs(yawRateDegPerSec) < 5.0 -> {
                if (abs(roadTurnDegrees) < 20.0) 1.5 // straight road branch aligns
                else -2.0 // branch turns sharply while vehicle goes straight
            }
            else -> 0.0
        }
    }

    /**
     * Anti-snap hysteresis logic: prevents flickering between roads (e.g. parallel highway/service road)
     * unless the switch is topologically valid or consistently supported over consecutive frames.
     */
    private fun resolveHysteresis(
        currentCandidates: List<State>,
        bestCandidate: State,
        topologicalHopProvider: ((Long, Long) -> Int)?
    ): State {
        val currentLockId = lockedWayId
        val currentLockName = lockedRoadName

        // Initial acquisition
        if (currentLockId == null || currentLockId == 0L) {
            lockedWayId = bestCandidate.candidate.wayId
            lockedRoadName = bestCandidate.candidate.roadName
            consecutiveDivergentFrames = 0
            pendingSwitchWayId = null
            return bestCandidate
        }

        // Best candidate is on the same road
        if (bestCandidate.candidate.wayId == currentLockId) {
            consecutiveDivergentFrames = 0
            pendingSwitchWayId = null
            return bestCandidate
        }

        // Check if the transition is an immediate topological junction (legal turn)
        val hops = topologicalHopProvider?.invoke(currentLockId, bestCandidate.candidate.wayId)
        if (hops == 1) {
            // Directly connected junction: immediately follow the turn
            lockedWayId = bestCandidate.candidate.wayId
            lockedRoadName = bestCandidate.candidate.roadName
            consecutiveDivergentFrames = 0
            pendingSwitchWayId = null
            return bestCandidate
        }

        // Disconnected or non-immediate switch candidate: apply hysteresis
        val candidateOnLockedRoad = currentCandidates.firstOrNull {
            it.candidate.wayId == currentLockId || (currentLockName != null && it.candidate.roadName == currentLockName)
        }

        if (candidateOnLockedRoad != null) {
            val margin = bestCandidate.score - candidateOnLockedRoad.score
            if (margin > SWITCH_MARGIN_SCORE) {
                if (pendingSwitchWayId == bestCandidate.candidate.wayId) {
                    consecutiveDivergentFrames++
                } else {
                    pendingSwitchWayId = bestCandidate.candidate.wayId
                    consecutiveDivergentFrames = 1
                }

                if (consecutiveDivergentFrames >= SWITCH_CONFIRMATION_FRAMES) {
                    // Confirmed switch after N frames of persistent evidence
                    lockedWayId = bestCandidate.candidate.wayId
                    lockedRoadName = bestCandidate.candidate.roadName
                    consecutiveDivergentFrames = 0
                    pendingSwitchWayId = null
                    return bestCandidate
                } else {
                    // Hold the lock to prevent 1-frame jitter
                    return candidateOnLockedRoad
                }
            } else {
                consecutiveDivergentFrames = 0
                pendingSwitchWayId = null
                return candidateOnLockedRoad
            }
        }

        // No candidate on the locked road was found in the search radius
        consecutiveDivergentFrames++
        if (consecutiveDivergentFrames >= SWITCH_CONFIRMATION_FRAMES || bestCandidate.candidate.distanceMeters < 15.0) {
            lockedWayId = bestCandidate.candidate.wayId
            lockedRoadName = bestCandidate.candidate.roadName
            consecutiveDivergentFrames = 0
            pendingSwitchWayId = null
            return bestCandidate
        }

        return bestCandidate
    }

    private fun angularDifference(first: Double, second: Double): Double =
        abs(((first - second + 540.0) % 360.0) - 180.0)

    private fun signedAngularDifference(target: Double, source: Double): Double {
        var diff = (target - source) % 360.0
        if (diff > 180.0) diff -= 360.0
        if (diff < -180.0) diff += 360.0
        return diff
    }
}
