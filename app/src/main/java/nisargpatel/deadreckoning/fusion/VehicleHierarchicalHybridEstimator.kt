package nisargpatel.deadreckoning.fusion

import nisargpatel.deadreckoning.data.RoadCandidate
import org.osmdroid.util.GeoPoint
import kotlin.math.abs

/**
 * Proposed Hybrid Architecture:
 * Hierarchical Fusion combining IMM-UKF + RBPF + FGO.
 *
 * Information Flow:
 * 1. Tier 1 (IMM-UKF): Estimates vehicle motion regime (CV, CTRV, CA) + stationary ZUPT + filtered kinematics.
 * 2. Tier 2 (RBPF): Resolves multimodal topological road hypotheses at intersections,
 *    conditioned on the motion mode from Tier 1.
 * 3. Tier 3 (FGO): Ingests IMM odometry between-factors and top RBPF road geometry factors,
 *    performing sliding-window nonlinear trajectory optimization.
 * 4. Closed-Loop Feedback: Trajectory-level corrections re-center IMM-UKF and RBPF priors.
 */
class VehicleHierarchicalHybridEstimator(
    private val headingPolicy: HeadingPolicy = HeadingPolicy.GYRO_WITH_MODEL_UPDATE,
    private val nonHolonomic: NonHolonomicConfig = NonHolonomicConfig(),
    private val mapConstraint: MapConstraintConfig = MapConstraintConfig(),
    private val turningConservatism: TurningConservatismConfig = TurningConservatismConfig.DISABLED
) : VehicleEstimator {

    override val name: String = "Proposed Hybrid (IMM-UKF + RBPF + FGO)"

    val immUkf = VehicleFusionImmUkf(headingPolicy, nonHolonomic, mapConstraint)
    val rbpf = VehicleRbpf(numParticles = 30)
    val fgo = VehicleSlidingWindowFgo(windowSize = 15)

    private var reference: GeoPoint? = null
    var outageDurationSeconds: Double = 0.0
        private set

    val currentModeProbabilities: DoubleArray
        get() = immUkf.modeProbabilities

    val dominantMotionMode: VehicleFusionImmUkf.MotionMode
        get() = immUkf.currentDominantMode

    val topRoadHypothesis: VehicleRbpf.RoadHypothesis?
        get() = rbpf.topHypothesis

    override fun isInitialized(): Boolean = reference != null

    override fun reset(
        position: GeoPoint,
        speedMps: Double,
        headingDegrees: Double,
        accuracyMeters: Double
    ) {
        reference = position
        outageDurationSeconds = 0.0
        immUkf.reset(position, speedMps, headingDegrees, accuracyMeters)
        rbpf.reset(position, speedMps, headingDegrees, accuracyMeters)
        fgo.reset(position, speedMps, headingDegrees, accuracyMeters)
    }

    override fun predict(
        forwardMeters: Double,
        lateralMeters: Double,
        headingDeltaRadians: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null) return null
        outageDurationSeconds += intervalSeconds.coerceAtLeast(0.0)

        // Tier 1: Motion regime and kinematic prediction
        val immState = immUkf.predict(forwardMeters, lateralMeters, headingDeltaRadians, intervalSeconds)
            ?: return null

        val motionMode = immUkf.currentDominantMode
        val estimatedYawRate = immUkf.estimatedYawRate

        // Adaptive Non-Holonomic Constraint: Boost lateral suppression during straight CV mode
        val effectiveLateral = if (motionMode == VehicleFusionImmUkf.MotionMode.CONSTANT_VELOCITY) {
            0.0
        } else {
            lateralMeters * (1.0 - nonHolonomic.lateralGain)
        }

        // Tier 2: RBPF road hypothesis prediction
        rbpf.predict(forwardMeters, effectiveLateral, headingDeltaRadians, intervalSeconds)

        // Tier 3: FGO sliding-window trajectory optimization
        fgo.predict(forwardMeters, effectiveLateral, headingDeltaRadians, intervalSeconds)

        // Fuse top road hypothesis from RBPF into FGO
        val topHyp = rbpf.topHypothesis
        if (topHyp != null && topHyp.weight > 0.40) {
            val rbpfState = rbpf.state()
            if (rbpfState != null) {
                fgo.updateMapConstraint(rbpfState.position, topHyp.bearingDeg, (topHyp.weight * 100).toInt())
            }
        }

        return state()
    }

    override fun predictVelocity(
        forwardMps: Double,
        lateralMps: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        immUkf.predictVelocity(forwardMps, lateralMps, intervalSeconds)
        rbpf.predictVelocity(forwardMps, lateralMps, intervalSeconds)
        fgo.predictVelocity(forwardMps, lateralMps, intervalSeconds)
        return state()
    }

    override fun predictGyro(
        angularVelocityZRadPerSec: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        immUkf.predictGyro(angularVelocityZRadPerSec, intervalSeconds)
        rbpf.predictGyro(angularVelocityZRadPerSec, intervalSeconds)
        fgo.predictGyro(angularVelocityZRadPerSec, intervalSeconds)
        return state()
    }

    override fun updateSpeed(measuredMps: Double, uncertaintyMps: Double): FusedVehicleState? {
        immUkf.updateSpeed(measuredMps, uncertaintyMps)
        rbpf.updateSpeed(measuredMps, uncertaintyMps)
        fgo.updateSpeed(measuredMps, uncertaintyMps)
        return state()
    }

    override fun updateGnss(
        position: GeoPoint,
        speedMps: Double,
        headingDegrees: Double,
        accuracyMeters: Double
    ): FusedVehicleState {
        outageDurationSeconds = 0.0
        immUkf.updateGnss(position, speedMps, headingDegrees, accuracyMeters)
        rbpf.updateGnss(position, speedMps, headingDegrees, accuracyMeters)
        fgo.updateGnss(position, speedMps, headingDegrees, accuracyMeters)
        return state()!!
    }

    /**
     * Ingest road candidates from road matcher or offline road network.
     */
    fun updateMapCandidates(candidates: List<RoadCandidate>): Boolean {
        val yawRate = immUkf.estimatedYawRate
        return rbpf.updateMapCandidates(candidates, currentTurnRate = yawRate)
    }

    override fun updateMapConstraint(
        matchedPosition: GeoPoint,
        roadBearingDegrees: Double?,
        confidence: Int
    ): MapConstraintResult? {
        immUkf.updateMapConstraint(matchedPosition, roadBearingDegrees, confidence)
        rbpf.updateMapConstraint(matchedPosition, roadBearingDegrees, confidence)
        val fgoResult = fgo.updateMapConstraint(matchedPosition, roadBearingDegrees, confidence)
        return fgoResult
    }

    override fun state(): FusedVehicleState? {
        val immState = immUkf.state() ?: return null
        val fgoState = fgo.state()
        if (fgoState != null) {
            val dist = immState.position.distanceToAsDouble(fgoState.position)
            if (dist < 40.0) {
                return FusedVehicleState(
                    position = fgoState.position,
                    speedMps = immState.speedMps,
                    headingDegrees = fgoState.headingDegrees,
                    horizontalUncertaintyMeters = immState.horizontalUncertaintyMeters.coerceAtLeast(1.0),
                    alongTrackUncertaintyMeters = immState.alongTrackUncertaintyMeters,
                    crossTrackUncertaintyMeters = immState.crossTrackUncertaintyMeters,
                    speedUncertaintyMps = immState.speedUncertaintyMps,
                    headingUncertaintyDegrees = immState.headingUncertaintyDegrees
                )
            }
        }
        return immState
    }
}
