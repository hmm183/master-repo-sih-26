package nisargpatel.deadreckoning.fusion

import org.osmdroid.util.GeoPoint

/**
 * Common abstraction implemented by all vehicle state estimators:
 * - Baseline 1: EKF (VehicleFusionEkf)
 * - Baseline 2: UKF (VehicleFusionUkf)
 * - Baseline 3: IMM-UKF (VehicleFusionImmUkf)
 * - Baseline 4: PF (VehicleParticleFilter)
 * - Baseline 5: RBPF (VehicleRbpf)
 * - Baseline 6: FGO (VehicleSlidingWindowFgo)
 * - Proposed Hybrid: Hierarchical Fusion Engine (VehicleHierarchicalHybridEstimator)
 */
interface VehicleEstimator {
    val name: String

    fun isInitialized(): Boolean

    fun reset(
        position: GeoPoint,
        speedMps: Double,
        headingDegrees: Double,
        accuracyMeters: Double
    )

    fun predict(
        forwardMeters: Double,
        lateralMeters: Double,
        headingDeltaRadians: Double,
        intervalSeconds: Double
    ): FusedVehicleState?

    fun predictVelocity(
        forwardMps: Double,
        lateralMps: Double,
        intervalSeconds: Double
    ): FusedVehicleState?

    fun predictGyro(
        angularVelocityZRadPerSec: Double,
        intervalSeconds: Double
    ): FusedVehicleState?

    fun updateSpeed(
        measuredMps: Double,
        uncertaintyMps: Double
    ): FusedVehicleState?

    fun updateGnss(
        position: GeoPoint,
        speedMps: Double,
        headingDegrees: Double,
        accuracyMeters: Double
    ): FusedVehicleState

    fun updateMapConstraint(
        matchedPosition: GeoPoint,
        roadBearingDegrees: Double?,
        confidence: Int
    ): MapConstraintResult?

    fun state(): FusedVehicleState?
}
