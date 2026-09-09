package nisargpatel.deadreckoning.fusion

import nisargpatel.deadreckoning.fusion.math.MatrixMath
import org.ejml.data.DenseMatrix64F
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Baseline 2: Single-Model Unscented Kalman Filter (UKF) with CTRV kinematics.
 *
 * State: [pEast, pNorth, speed, headingRad, yawRateRadPerSec] (dimension = 5).
 * Unlike EKF, UKF propagates nonlinear coordinate rotations and coordinated turns
 * through deterministic sigma points without first-order Jacobian truncation.
 */
class VehicleFusionUkf(
    private val headingPolicy: HeadingPolicy = HeadingPolicy.GYRO_WITH_MODEL_UPDATE,
    private val nonHolonomic: NonHolonomicConfig = NonHolonomicConfig(),
    private val mapConstraint: MapConstraintConfig = MapConstraintConfig()
) : VehicleEstimator {

    override val name: String = "UKF"

    private companion object {
        const val STATE_DIM = 5
        const val IDX_PE = 0
        const val IDX_PN = 1
        const val IDX_SPEED = 2
        const val IDX_HEADING = 3
        const val IDX_YAWRATE = 4
        const val ANGLE_INDEX = IDX_HEADING
    }

    private var reference: GeoPoint? = null
    private val ut = MatrixMath.UnscentedTransform(STATE_DIM, alpha = 1e-3, beta = 2.0, kappa = 0.0)

    // State vector: [pE, pN, speed, heading, yawRate]
    private var stateMean = DoubleArray(STATE_DIM)
    private var stateCov = DenseMatrix64F(STATE_DIM, STATE_DIM)

    override fun isInitialized(): Boolean = reference != null

    override fun reset(
        position: GeoPoint,
        speedMps: Double,
        headingDegrees: Double,
        accuracyMeters: Double
    ) {
        reference = position
        stateMean[IDX_PE] = 0.0
        stateMean[IDX_PN] = 0.0
        stateMean[IDX_SPEED] = speedMps.coerceAtLeast(0.0)
        stateMean[IDX_HEADING] = MatrixMath.normalizeRadians(Math.toRadians(headingDegrees))
        stateMean[IDX_YAWRATE] = 0.0

        val initialPosVar = accuracyMeters.coerceAtLeast(3.0).let { it * it }
        val initialSpeedVar = 4.0
        val initialHeadingVar = Math.toRadians(15.0).let { it * it }
        val initialYawRateVar = 0.04

        stateCov = DenseMatrix64F(STATE_DIM, STATE_DIM)
        stateCov.set(IDX_PE, IDX_PE, initialPosVar)
        stateCov.set(IDX_PN, IDX_PN, initialPosVar)
        stateCov.set(IDX_SPEED, IDX_SPEED, initialSpeedVar)
        stateCov.set(IDX_HEADING, IDX_HEADING, initialHeadingVar)
        stateCov.set(IDX_YAWRATE, IDX_YAWRATE, initialYawRateVar)
    }

    /**
     * CTRV Kinematic transition function for a single sigma point.
     */
    private fun propagatePoint(point: DoubleArray, dt: Double, fwdMeters: Double?, turnYawRate: Double?): DoubleArray {
        val out = point.clone()
        val pe = point[IDX_PE]
        val pn = point[IDX_PN]
        val v = point[IDX_SPEED]
        val psi = point[IDX_HEADING]
        val w = turnYawRate ?: point[IDX_YAWRATE]

        if (fwdMeters != null) {
            // Window displacement driven
            val d = fwdMeters
            val dPsi = w * dt
            if (abs(dPsi) > 1e-4) {
                out[IDX_PE] = pe + d * (cos(psi) - cos(psi + dPsi)) / dPsi
                out[IDX_PN] = pn + d * (sin(psi + dPsi) - sin(psi)) / dPsi
            } else {
                out[IDX_PE] = pe + d * sin(psi)
                out[IDX_PN] = pn + d * cos(psi)
            }
            out[IDX_HEADING] = MatrixMath.normalizeRadians(psi + dPsi)
            out[IDX_SPEED] = (d / dt.coerceAtLeast(0.05)).coerceAtLeast(0.0)
        } else {
            // Speed driven
            if (abs(w) > 1e-4) {
                out[IDX_PE] = pe + (v / w) * (cos(psi) - cos(psi + w * dt))
                out[IDX_PN] = pn + (v / w) * (sin(psi + w * dt) - sin(psi))
                out[IDX_HEADING] = MatrixMath.normalizeRadians(psi + w * dt)
            } else {
                out[IDX_PE] = pe + v * dt * sin(psi)
                out[IDX_PN] = pn + v * dt * cos(psi)
                out[IDX_HEADING] = psi
            }
        }
        return out
    }

    override fun predict(
        forwardMeters: Double,
        lateralMeters: Double,
        headingDeltaRadians: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || !forwardMeters.isFinite() || !headingDeltaRadians.isFinite()) return null
        val dt = intervalSeconds.coerceIn(0.01, 5.0)

        // Generate sigma points
        val sigmas = ut.generateSigmaPoints(stateMean, stateCov)
        val wObserved = headingDeltaRadians / dt

        // Propagate sigma points
        val propagatedSigmas = Array(ut.numSigmaPoints) { i ->
            propagatePoint(sigmas[i], dt, forwardMeters, wObserved)
        }

        // Process noise matrix Q
        val q = DenseMatrix64F(STATE_DIM, STATE_DIM)
        val dist = abs(forwardMeters)
        val alongNoise = 0.05 * dist + 0.5
        val crossNoise = if (nonHolonomic.enabled) 0.3 else 1.5
        q.set(IDX_PE, IDX_PE, alongNoise * alongNoise + crossNoise * crossNoise)
        q.set(IDX_PN, IDX_PN, alongNoise * alongNoise + crossNoise * crossNoise)
        q.set(IDX_SPEED, IDX_SPEED, 0.5)
        q.set(IDX_HEADING, IDX_HEADING, 0.002)
        q.set(IDX_YAWRATE, IDX_YAWRATE, 0.05)

        stateMean = ut.recoverMean(propagatedSigmas, ANGLE_INDEX)
        stateCov = ut.recoverCovariance(propagatedSigmas, stateMean, ANGLE_INDEX, q)

        return state()
    }

    override fun predictVelocity(
        forwardMps: Double,
        lateralMps: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || intervalSeconds <= 0.0 || !forwardMps.isFinite()) return null
        val dt = intervalSeconds.coerceIn(0.001, 1.0)
        stateMean[IDX_SPEED] = forwardMps.coerceAtLeast(0.0)

        val sigmas = ut.generateSigmaPoints(stateMean, stateCov)
        val propagated = Array(ut.numSigmaPoints) { i ->
            propagatePoint(sigmas[i], dt, null, null)
        }

        val q = DenseMatrix64F(STATE_DIM, STATE_DIM)
        val dist = forwardMps * dt
        q.set(IDX_PE, IDX_PE, (0.05 * dist + 0.1).let { it * it })
        q.set(IDX_PN, IDX_PN, (0.05 * dist + 0.1).let { it * it })
        q.set(IDX_SPEED, IDX_SPEED, 0.2 * dt)
        q.set(IDX_HEADING, IDX_HEADING, 0.001 * dt)
        q.set(IDX_YAWRATE, IDX_YAWRATE, 0.02 * dt)

        stateMean = ut.recoverMean(propagated, ANGLE_INDEX)
        stateCov = ut.recoverCovariance(propagated, stateMean, ANGLE_INDEX, q)
        return state()
    }

    override fun predictGyro(
        angularVelocityZRadPerSec: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || intervalSeconds <= 0.0 || !angularVelocityZRadPerSec.isFinite()) return null
        val deltaHeading = angularVelocityZRadPerSec * intervalSeconds
        stateMean[IDX_HEADING] = MatrixMath.normalizeRadians(stateMean[IDX_HEADING] + deltaHeading)
        stateMean[IDX_YAWRATE] = angularVelocityZRadPerSec
        stateCov.set(IDX_HEADING, IDX_HEADING, stateCov.get(IDX_HEADING, IDX_HEADING) + 0.0001)
        stateCov.set(IDX_YAWRATE, IDX_YAWRATE, 0.01)
        return state()
    }

    override fun updateSpeed(measuredMps: Double, uncertaintyMps: Double): FusedVehicleState? {
        if (reference == null || !measuredMps.isFinite() || !uncertaintyMps.isFinite()) return null
        val rVar = (uncertaintyMps * uncertaintyMps).coerceAtLeast(0.05)
        val pSpeed = stateCov.get(IDX_SPEED, IDX_SPEED)
        val k = pSpeed / (pSpeed + rVar)
        stateMean[IDX_SPEED] = (stateMean[IDX_SPEED] + k * (measuredMps - stateMean[IDX_SPEED])).coerceAtLeast(0.0)
        stateCov.set(IDX_SPEED, IDX_SPEED, pSpeed * (1.0 - k))
        return state()
    }

    override fun updateGnss(
        position: GeoPoint,
        speedMps: Double,
        headingDegrees: Double,
        accuracyMeters: Double
    ): FusedVehicleState {
        if (reference == null) {
            reset(position, speedMps, headingDegrees, accuracyMeters)
            return state()!!
        }

        val ref = reference!!
        val dLat = position.latitude - ref.latitude
        val dLon = position.longitude - ref.longitude
        val measPn = dLat * 111_111.0
        val measPe = dLon * (111_111.0 * cos(Math.toRadians(ref.latitude)))
        val measSpeed = speedMps.coerceAtLeast(0.0)
        val measHeading = MatrixMath.normalizeRadians(Math.toRadians(headingDegrees))

        // Position Kalman update
        val rPos = accuracyMeters.coerceAtLeast(3.0).let { it * it }
        val pPe = stateCov.get(IDX_PE, IDX_PE)
        val pPn = stateCov.get(IDX_PN, IDX_PN)

        val kPe = pPe / (pPe + rPos)
        val kPn = pPn / (pPn + rPos)

        stateMean[IDX_PE] += kPe * (measPe - stateMean[IDX_PE])
        stateMean[IDX_PN] += kPn * (measPn - stateMean[IDX_PN])
        stateCov.set(IDX_PE, IDX_PE, pPe * (1.0 - kPe))
        stateCov.set(IDX_PN, IDX_PN, pPn * (1.0 - kPn))

        // Speed update
        updateSpeed(measSpeed, 1.5)

        // Heading update when moving
        if (measSpeed >= 1.5) {
            val pHead = stateCov.get(IDX_HEADING, IDX_HEADING)
            val rHead = Math.toRadians(12.0).let { it * it }
            val kHead = pHead / (pHead + rHead)
            val diffHead = MatrixMath.shortestAngleDelta(stateMean[IDX_HEADING], measHeading)
            stateMean[IDX_HEADING] = MatrixMath.normalizeRadians(stateMean[IDX_HEADING] + kHead * diffHead)
            stateCov.set(IDX_HEADING, IDX_HEADING, pHead * (1.0 - kHead))
        }

        return state()!!
    }

    override fun updateMapConstraint(
        matchedPosition: GeoPoint,
        roadBearingDegrees: Double?,
        confidence: Int
    ): MapConstraintResult? {
        if (reference == null) return null
        if (!mapConstraint.enabled) return MapConstraintResult(false, state()!!, 0.0, "disabled")
        if (confidence < mapConstraint.minConfidence) return MapConstraintResult(false, state()!!, 0.0, "low confidence")
        if (roadBearingDegrees == null || !roadBearingDegrees.isFinite()) return MapConstraintResult(false, state()!!, 0.0, "unknown bearing")

        val ref = reference!!
        val dLat = matchedPosition.latitude - ref.latitude
        val dLon = matchedPosition.longitude - ref.longitude
        val measPn = dLat * 111_111.0
        val measPe = dLon * (111_111.0 * cos(Math.toRadians(ref.latitude)))

        val innovE = measPe - stateMean[IDX_PE]
        val innovN = measPn - stateMean[IDX_PN]

        val bearingRad = Math.toRadians(roadBearingDegrees)
        val crossE = cos(bearingRad)
        val crossN = -sin(bearingRad)

        val crossInnov = innovE * crossE + innovN * crossN
        val crossVar = mapConstraint.crossTrackUncertaintyMeters.let { it * it }
        val pCross = crossE * crossE * stateCov.get(IDX_PE, IDX_PE) + crossN * crossN * stateCov.get(IDX_PN, IDX_PN)
        val s = pCross + crossVar

        val gate = mapConstraint.gateSigma * sqrt(s)
        if (abs(crossInnov) > gate) {
            return MapConstraintResult(false, state()!!, crossInnov, "gated out")
        }

        val gain = pCross / s
        val correction = gain * crossInnov
        stateMean[IDX_PE] += correction * crossE
        stateMean[IDX_PN] += correction * crossN

        // Heading alignment toward road bearing
        val headDiff = MatrixMath.shortestAngleDelta(stateMean[IDX_HEADING], bearingRad)
        if (abs(headDiff) < Math.toRadians(45.0)) {
            val kHead = 0.2
            stateMean[IDX_HEADING] = MatrixMath.normalizeRadians(stateMean[IDX_HEADING] + kHead * headDiff)
        }

        return MapConstraintResult(true, state()!!, crossInnov)
    }

    override fun state(): FusedVehicleState? {
        val ref = reference ?: return null
        val pe = stateMean[IDX_PE]
        val pn = stateMean[IDX_PN]
        val lat = ref.latitude + pn / 111_111.0
        val lon = ref.longitude + pe / (111_111.0 * cos(Math.toRadians(ref.latitude)))

        val pEE = stateCov.get(IDX_PE, IDX_PE)
        val pNN = stateCov.get(IDX_PN, IDX_PN)
        val worstHorizSigma = sqrt(maxOf(pEE, pNN))

        val headingDeg = Math.toDegrees(MatrixMath.normalizeRadians(stateMean[IDX_HEADING])).let {
            if (it < 0.0) it + 360.0 else it
        }

        return FusedVehicleState(
            position = GeoPoint(lat, lon),
            speedMps = stateMean[IDX_SPEED],
            headingDegrees = headingDeg,
            horizontalUncertaintyMeters = worstHorizSigma,
            alongTrackUncertaintyMeters = sqrt(pEE.coerceAtLeast(0.0)),
            crossTrackUncertaintyMeters = sqrt(pNN.coerceAtLeast(0.0)),
            speedUncertaintyMps = sqrt(stateCov.get(IDX_SPEED, IDX_SPEED).coerceAtLeast(0.0)),
            headingUncertaintyDegrees = Math.toDegrees(sqrt(stateCov.get(IDX_HEADING, IDX_HEADING).coerceAtLeast(0.0)))
        )
    }
}
