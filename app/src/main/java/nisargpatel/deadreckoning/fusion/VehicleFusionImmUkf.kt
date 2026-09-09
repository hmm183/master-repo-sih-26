package nisargpatel.deadreckoning.fusion

import nisargpatel.deadreckoning.fusion.math.MatrixMath
import org.ejml.data.DenseMatrix64F
import org.osmdroid.util.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Baseline 3: Interacting Multiple Model Unscented Kalman Filter (IMM-UKF).
 *
 * Hosts 3 sub-models:
 *  - Mode 0: Constant Velocity (CV) - Straight roads, steady cruising
 *  - Mode 1: Constant Turn Rate & Velocity (CTRV) - Turns, curves, roundabouts
 *  - Mode 2: Constant Acceleration (CA) - Braking, stop-and-go, acceleration
 *
 * Tightly coupled with:
 *  - Motion Regime Estimation: Dynamically computes mode probability vector μ = [μ_cv, μ_ctrv, μ_ca]
 *  - Stationary Zero-Velocity Update (ZUPT): Freezes drift accumulation when vehicle stops
 */
class VehicleFusionImmUkf(
    private val headingPolicy: HeadingPolicy = HeadingPolicy.GYRO_WITH_MODEL_UPDATE,
    private val nonHolonomic: NonHolonomicConfig = NonHolonomicConfig(),
    private val mapConstraint: MapConstraintConfig = MapConstraintConfig()
) : VehicleEstimator {

    override val name: String = "IMM-UKF"

    enum class MotionMode(val index: Int) {
        CONSTANT_VELOCITY(0),
        CONSTANT_TURN_RATE(1),
        CONSTANT_ACCELERATION(2)
    }

    private companion object {
        const val STATE_DIM = 6
        const val IDX_PE = 0
        const val IDX_PN = 1
        const val IDX_SPEED = 2
        const val IDX_HEADING = 3
        const val IDX_YAWRATE = 4
        const val IDX_ACCEL = 5
        const val ANGLE_INDEX = IDX_HEADING

        const val NUM_MODES = 3

        // Mode transition probability matrix Pi[i][j]: probability of transitioning from mode i to j
        val TRANSITION_MATRIX = arrayOf(
            doubleArrayOf(0.85, 0.10, 0.05), // from CV
            doubleArrayOf(0.15, 0.80, 0.05), // from CTRV
            doubleArrayOf(0.15, 0.05, 0.80)  // from CA
        )
    }

    private var reference: GeoPoint? = null
    private val ut = MatrixMath.UnscentedTransform(STATE_DIM, alpha = 1e-3, beta = 2.0, kappa = 0.0)

    // Mode probabilities μ
    var modeProbabilities: DoubleArray = doubleArrayOf(0.70, 0.20, 0.10)
        private set

    // Sub-filter states and covariances
    private val modeStates = Array(NUM_MODES) { DoubleArray(STATE_DIM) }
    private val modeCovs = Array(NUM_MODES) { DenseMatrix64F(STATE_DIM, STATE_DIM) }

    // Combined state and covariance
    private var combinedState = DoubleArray(STATE_DIM)
    private var combinedCov = DenseMatrix64F(STATE_DIM, STATE_DIM)

    val currentDominantMode: MotionMode
        get() {
            var bestIdx = 0
            var bestP = modeProbabilities[0]
            for (i in 1 until NUM_MODES) {
                if (modeProbabilities[i] > bestP) {
                    bestP = modeProbabilities[i]
                    bestIdx = i
                }
            }
            return MotionMode.values()[bestIdx]
        }

    val estimatedYawRate: Double
        get() = combinedState[IDX_YAWRATE]

    val estimatedSpeed: Double
        get() = combinedState[IDX_SPEED]

    val estimatedHeadingRad: Double
        get() = combinedState[IDX_HEADING]

    override fun isInitialized(): Boolean = reference != null

    override fun reset(
        position: GeoPoint,
        speedMps: Double,
        headingDegrees: Double,
        accuracyMeters: Double
    ) {
        reference = position
        modeProbabilities = doubleArrayOf(0.70, 0.20, 0.10)

        val posVar = accuracyMeters.coerceAtLeast(3.0).let { it * it }
        val speedVar = 4.0
        val headVar = Math.toRadians(15.0).let { it * it }
        val headingRad = MatrixMath.normalizeRadians(Math.toRadians(headingDegrees))

        for (m in 0 until NUM_MODES) {
            modeStates[m][IDX_PE] = 0.0
            modeStates[m][IDX_PN] = 0.0
            modeStates[m][IDX_SPEED] = speedMps.coerceAtLeast(0.0)
            modeStates[m][IDX_HEADING] = headingRad
            modeStates[m][IDX_YAWRATE] = 0.0
            modeStates[m][IDX_ACCEL] = 0.0

            modeCovs[m] = DenseMatrix64F(STATE_DIM, STATE_DIM)
            modeCovs[m].set(IDX_PE, IDX_PE, posVar)
            modeCovs[m].set(IDX_PN, IDX_PN, posVar)
            modeCovs[m].set(IDX_SPEED, IDX_SPEED, speedVar)
            modeCovs[m].set(IDX_HEADING, IDX_HEADING, headVar)
            modeCovs[m].set(IDX_YAWRATE, IDX_YAWRATE, 0.04)
            modeCovs[m].set(IDX_ACCEL, IDX_ACCEL, 1.0)
        }

        combineOutput()
    }

    /**
     * Kinematic point propagation conditioned on mode.
     */
    private fun propagateModePoint(
        point: DoubleArray,
        mode: Int,
        dt: Double,
        forwardMeters: Double?,
        measuredYawRate: Double?
    ): DoubleArray {
        val out = point.clone()
        val pe = point[IDX_PE]
        val pn = point[IDX_PN]
        val v = point[IDX_SPEED]
        val psi = point[IDX_HEADING]
        val w = measuredYawRate ?: point[IDX_YAWRATE]
        val a = point[IDX_ACCEL]

        when (mode) {
            MotionMode.CONSTANT_VELOCITY.index -> {
                // Straight: yaw rate and acceleration assumed 0
                val d = forwardMeters ?: (v * dt)
                out[IDX_PE] = pe + d * sin(psi)
                out[IDX_PN] = pn + d * cos(psi)
                out[IDX_HEADING] = psi
                out[IDX_YAWRATE] = 0.0
                out[IDX_ACCEL] = 0.0
                out[IDX_SPEED] = if (forwardMeters != null) (d / dt).coerceAtLeast(0.0) else v
            }
            MotionMode.CONSTANT_TURN_RATE.index -> {
                // Turn: coordinated turn with yaw rate w
                val effectiveW = if (abs(w) > 1e-4) w else 1e-4
                val dPsi = effectiveW * dt
                if (forwardMeters != null) {
                    val d = forwardMeters
                    out[IDX_PE] = pe + d * (cos(psi) - cos(psi + dPsi)) / dPsi
                    out[IDX_PN] = pn + d * (sin(psi + dPsi) - sin(psi)) / dPsi
                    out[IDX_SPEED] = (d / dt).coerceAtLeast(0.0)
                } else {
                    out[IDX_PE] = pe + (v / effectiveW) * (cos(psi) - cos(psi + dPsi))
                    out[IDX_PN] = pn + (v / effectiveW) * (sin(psi + dPsi) - sin(psi))
                }
                out[IDX_HEADING] = MatrixMath.normalizeRadians(psi + dPsi)
                out[IDX_YAWRATE] = effectiveW
                out[IDX_ACCEL] = 0.0
            }
            MotionMode.CONSTANT_ACCELERATION.index -> {
                // Accelerating / braking along heading
                val d = forwardMeters ?: (v * dt + 0.5 * a * dt * dt)
                out[IDX_PE] = pe + d * sin(psi)
                out[IDX_PN] = pn + d * cos(psi)
                out[IDX_SPEED] = if (forwardMeters != null) (d / dt).coerceAtLeast(0.0) else (v + a * dt).coerceAtLeast(0.0)
                out[IDX_HEADING] = psi
                out[IDX_YAWRATE] = 0.0
                out[IDX_ACCEL] = a
            }
        }
        return out
    }

    /**
     * IMM Step 1: Mode Mixing / Interaction.
     */
    private fun mixModes(): Pair<Array<DoubleArray>, Array<DenseMatrix64F>> {
        // Normalizing factors c_j = sum_i Pi[i][j] * mu_i
        val c = DoubleArray(NUM_MODES)
        for (j in 0 until NUM_MODES) {
            var sum = 0.0
            for (i in 0 until NUM_MODES) {
                sum += TRANSITION_MATRIX[i][j] * modeProbabilities[i]
            }
            c[j] = sum.coerceAtLeast(1e-12)
        }

        // Mixing weights mu_{i|j} = Pi[i][j] * mu_i / c_j
        val mixedStates = Array(NUM_MODES) { DoubleArray(STATE_DIM) }
        val mixedCovs = Array(NUM_MODES) { DenseMatrix64F(STATE_DIM, STATE_DIM) }

        for (j in 0 until NUM_MODES) {
            val muGivenJ = DoubleArray(NUM_MODES) { i -> (TRANSITION_MATRIX[i][j] * modeProbabilities[i]) / c[j] }

            // Mixed mean
            var sinSum = 0.0
            var cosSum = 0.0
            for (i in 0 until NUM_MODES) {
                val st = modeStates[i]
                val w = muGivenJ[i]
                for (d in 0 until STATE_DIM) {
                    if (d == ANGLE_INDEX) {
                        sinSum += w * sin(st[d])
                        cosSum += w * cos(st[d])
                    } else {
                        mixedStates[j][d] += w * st[d]
                    }
                }
            }
            mixedStates[j][ANGLE_INDEX] = MatrixMath.normalizeRadians(kotlin.math.atan2(sinSum, cosSum))

            // Mixed covariance
            for (i in 0 until NUM_MODES) {
                val st = modeStates[i]
                val cv = modeCovs[i]
                val w = muGivenJ[i]

                val diff = DoubleArray(STATE_DIM)
                for (d in 0 until STATE_DIM) {
                    diff[d] = if (d == ANGLE_INDEX) {
                        MatrixMath.shortestAngleDelta(mixedStates[j][d], st[d])
                    } else {
                        st[d] - mixedStates[j][d]
                    }
                }

                for (r in 0 until STATE_DIM) {
                    for (col in 0 until STATE_DIM) {
                        val current = mixedCovs[j].get(r, col)
                        mixedCovs[j].set(r, col, current + w * (cv.get(r, col) + diff[r] * diff[col]))
                    }
                }
            }
        }
        return Pair(mixedStates, mixedCovs)
    }

    /**
     * IMM Step 4: Output Combination.
     */
    private fun combineOutput() {
        combinedState = DoubleArray(STATE_DIM)
        var sinSum = 0.0
        var cosSum = 0.0

        for (m in 0 until NUM_MODES) {
            val w = modeProbabilities[m]
            val st = modeStates[m]
            for (d in 0 until STATE_DIM) {
                if (d == ANGLE_INDEX) {
                    sinSum += w * sin(st[d])
                    cosSum += w * cos(st[d])
                } else {
                    combinedState[d] += w * st[d]
                }
            }
        }
        combinedState[ANGLE_INDEX] = MatrixMath.normalizeRadians(kotlin.math.atan2(sinSum, cosSum))

        combinedCov = DenseMatrix64F(STATE_DIM, STATE_DIM)
        for (m in 0 until NUM_MODES) {
            val w = modeProbabilities[m]
            val st = modeStates[m]
            val cv = modeCovs[m]

            val diff = DoubleArray(STATE_DIM)
            for (d in 0 until STATE_DIM) {
                diff[d] = if (d == ANGLE_INDEX) {
                    MatrixMath.shortestAngleDelta(combinedState[d], st[d])
                } else {
                    st[d] - combinedState[d]
                }
            }

            for (r in 0 until STATE_DIM) {
                for (c in 0 until STATE_DIM) {
                    val current = combinedCov.get(r, c)
                    combinedCov.set(r, c, current + w * (cv.get(r, c) + diff[r] * diff[c]))
                }
            }
        }
    }

    override fun predict(
        forwardMeters: Double,
        lateralMeters: Double,
        headingDeltaRadians: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || !forwardMeters.isFinite() || !headingDeltaRadians.isFinite()) return null
        val dt = intervalSeconds.coerceIn(0.01, 5.0)

        // Stationary Floor / ZUPT Check: Clamp sensor jitter when vehicle is stationary
        val isStationary = combinedState[IDX_SPEED] < 0.05 && abs(forwardMeters) < 0.04
        if (isStationary) {
            modeProbabilities = doubleArrayOf(0.96, 0.02, 0.02)
            for (m in 0 until NUM_MODES) {
                modeStates[m][IDX_SPEED] = 0.0
                modeStates[m][IDX_YAWRATE] = 0.0
                modeStates[m][IDX_ACCEL] = 0.0
                modeCovs[m].set(IDX_SPEED, IDX_SPEED, 0.01)
                modeCovs[m].set(IDX_YAWRATE, IDX_YAWRATE, 0.001)
            }
            combineOutput()
            return state()
        }

        // Step 1: Mixing
        val (mixedStates, mixedCovs) = mixModes()

        val observedYawRate = headingDeltaRadians / dt
        val likelihoods = DoubleArray(NUM_MODES)

        // Step 2: Mode Filtering
        for (m in 0 until NUM_MODES) {
            val sigmas = ut.generateSigmaPoints(mixedStates[m], mixedCovs[m])
            val propagated = Array(ut.numSigmaPoints) { i ->
                propagateModePoint(sigmas[i], m, dt, forwardMeters, observedYawRate)
            }

            val q = DenseMatrix64F(STATE_DIM, STATE_DIM)
            val dist = abs(forwardMeters)
            val along = 0.05 * dist + 0.4
            val cross = if (nonHolonomic.enabled) 0.25 else 1.2
            q.set(IDX_PE, IDX_PE, along * along + cross * cross)
            q.set(IDX_PN, IDX_PN, along * along + cross * cross)
            q.set(IDX_SPEED, IDX_SPEED, if (m == MotionMode.CONSTANT_ACCELERATION.index) 1.0 else 0.3)
            q.set(IDX_HEADING, IDX_HEADING, if (m == MotionMode.CONSTANT_TURN_RATE.index) 0.005 else 0.0005)
            q.set(IDX_YAWRATE, IDX_YAWRATE, if (m == MotionMode.CONSTANT_TURN_RATE.index) 0.1 else 0.01)
            q.set(IDX_ACCEL, IDX_ACCEL, 0.5)

            modeStates[m] = ut.recoverMean(propagated, ANGLE_INDEX)
            modeCovs[m] = ut.recoverCovariance(propagated, modeStates[m], ANGLE_INDEX, q)

            // Likelihood of mode given turning dynamics
            val yawResidual = abs(observedYawRate - modeStates[m][IDX_YAWRATE])
            val yawSigma = when (m) {
                MotionMode.CONSTANT_VELOCITY.index -> 0.05
                MotionMode.CONSTANT_TURN_RATE.index -> 0.30
                MotionMode.CONSTANT_ACCELERATION.index -> 0.08
                else -> 0.1
            }
            likelihoods[m] = (1.0 / (yawSigma * sqrt(2.0 * PI))) * exp(-0.5 * (yawResidual * yawResidual) / (yawSigma * yawSigma))
        }

        // Step 3: Mode Probability Update
        var normSum = 0.0
        val updatedMu = DoubleArray(NUM_MODES)
        for (j in 0 until NUM_MODES) {
            var c_j = 0.0
            for (i in 0 until NUM_MODES) {
                c_j += TRANSITION_MATRIX[i][j] * modeProbabilities[i]
            }
            updatedMu[j] = likelihoods[j] * c_j
            normSum += updatedMu[j]
        }
        if (normSum > 1e-12) {
            for (j in 0 until NUM_MODES) {
                modeProbabilities[j] = (updatedMu[j] / normSum).coerceIn(0.01, 0.98)
            }
        }

        // Step 4: Combination
        combineOutput()
        return state()
    }

    override fun predictVelocity(
        forwardMps: Double,
        lateralMps: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || intervalSeconds <= 0.0 || !forwardMps.isFinite()) return null
        val dt = intervalSeconds.coerceIn(0.001, 1.0)

        for (m in 0 until NUM_MODES) {
            modeStates[m][IDX_SPEED] = forwardMps.coerceAtLeast(0.0)
            val d = forwardMps * dt
            val psi = modeStates[m][IDX_HEADING]
            modeStates[m][IDX_PE] += d * sin(psi)
            modeStates[m][IDX_PN] += d * cos(psi)
        }
        combineOutput()
        return state()
    }

    override fun predictGyro(
        angularVelocityZRadPerSec: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || intervalSeconds <= 0.0 || !angularVelocityZRadPerSec.isFinite()) return null
        val dHeading = angularVelocityZRadPerSec * intervalSeconds

        for (m in 0 until NUM_MODES) {
            modeStates[m][IDX_HEADING] = MatrixMath.normalizeRadians(modeStates[m][IDX_HEADING] + dHeading)
            modeStates[m][IDX_YAWRATE] = angularVelocityZRadPerSec
        }
        combineOutput()
        return state()
    }

    override fun updateSpeed(measuredMps: Double, uncertaintyMps: Double): FusedVehicleState? {
        if (reference == null || !measuredMps.isFinite()) return null
        val rVar = (uncertaintyMps * uncertaintyMps).coerceAtLeast(0.05)

        for (m in 0 until NUM_MODES) {
            val p = modeCovs[m].get(IDX_SPEED, IDX_SPEED)
            val k = p / (p + rVar)
            modeStates[m][IDX_SPEED] = (modeStates[m][IDX_SPEED] + k * (measuredMps - modeStates[m][IDX_SPEED])).coerceAtLeast(0.0)
            modeCovs[m].set(IDX_SPEED, IDX_SPEED, p * (1.0 - k))
        }
        combineOutput()
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
        val measHeading = MatrixMath.normalizeRadians(Math.toRadians(headingDegrees))
        val rPos = accuracyMeters.coerceAtLeast(3.0).let { it * it }

        for (m in 0 until NUM_MODES) {
            val pPe = modeCovs[m].get(IDX_PE, IDX_PE)
            val pPn = modeCovs[m].get(IDX_PN, IDX_PN)
            val kPe = pPe / (pPe + rPos)
            val kPn = pPn / (pPn + rPos)

            modeStates[m][IDX_PE] += kPe * (measPe - modeStates[m][IDX_PE])
            modeStates[m][IDX_PN] += kPn * (measPn - modeStates[m][IDX_PN])
            modeCovs[m].set(IDX_PE, IDX_PE, pPe * (1.0 - kPe))
            modeCovs[m].set(IDX_PN, IDX_PN, pPn * (1.0 - kPn))

            if (speedMps >= 1.5) {
                val pHead = modeCovs[m].get(IDX_HEADING, IDX_HEADING)
                val rHead = Math.toRadians(12.0).let { it * it }
                val kHead = pHead / (pHead + rHead)
                val diffHead = MatrixMath.shortestAngleDelta(modeStates[m][IDX_HEADING], measHeading)
                modeStates[m][IDX_HEADING] = MatrixMath.normalizeRadians(modeStates[m][IDX_HEADING] + kHead * diffHead)
                modeCovs[m].set(IDX_HEADING, IDX_HEADING, pHead * (1.0 - kHead))
            }
        }

        updateSpeed(speedMps, 1.5)
        combineOutput()
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

        val innovE = measPe - combinedState[IDX_PE]
        val innovN = measPn - combinedState[IDX_PN]

        val bearingRad = Math.toRadians(roadBearingDegrees)
        val crossE = cos(bearingRad)
        val crossN = -sin(bearingRad)
        val crossInnov = innovE * crossE + innovN * crossN

        val crossVar = mapConstraint.crossTrackUncertaintyMeters.let { it * it }
        val pCross = crossE * crossE * combinedCov.get(IDX_PE, IDX_PE) + crossN * crossN * combinedCov.get(IDX_PN, IDX_PN)
        val s = pCross + crossVar

        val gate = mapConstraint.gateSigma * sqrt(s)
        if (abs(crossInnov) > gate) {
            return MapConstraintResult(false, state()!!, crossInnov, "gated out")
        }

        val gain = pCross / s
        val correction = gain * crossInnov

        for (m in 0 until NUM_MODES) {
            modeStates[m][IDX_PE] += correction * crossE
            modeStates[m][IDX_PN] += correction * crossN

            val headDiff = MatrixMath.shortestAngleDelta(modeStates[m][IDX_HEADING], bearingRad)
            if (abs(headDiff) < Math.toRadians(45.0)) {
                modeStates[m][IDX_HEADING] = MatrixMath.normalizeRadians(modeStates[m][IDX_HEADING] + 0.2 * headDiff)
            }
        }

        combineOutput()
        return MapConstraintResult(true, state()!!, crossInnov)
    }

    override fun state(): FusedVehicleState? {
        val ref = reference ?: return null
        val pe = combinedState[IDX_PE]
        val pn = combinedState[IDX_PN]
        val lat = ref.latitude + pn / 111_111.0
        val lon = ref.longitude + pe / (111_111.0 * cos(Math.toRadians(ref.latitude)))

        val pEE = combinedCov.get(IDX_PE, IDX_PE)
        val pNN = combinedCov.get(IDX_PN, IDX_PN)
        val worstHorizSigma = sqrt(max(pEE, pNN))

        val headingDeg = Math.toDegrees(MatrixMath.normalizeRadians(combinedState[IDX_HEADING])).let {
            if (it < 0.0) it + 360.0 else it
        }

        return FusedVehicleState(
            position = GeoPoint(lat, lon),
            speedMps = combinedState[IDX_SPEED],
            headingDegrees = headingDeg,
            horizontalUncertaintyMeters = worstHorizSigma,
            alongTrackUncertaintyMeters = sqrt(pEE.coerceAtLeast(0.0)),
            crossTrackUncertaintyMeters = sqrt(pNN.coerceAtLeast(0.0)),
            speedUncertaintyMps = sqrt(combinedCov.get(IDX_SPEED, IDX_SPEED).coerceAtLeast(0.0)),
            headingUncertaintyDegrees = Math.toDegrees(sqrt(combinedCov.get(IDX_HEADING, IDX_HEADING).coerceAtLeast(0.0)))
        )
    }
}
