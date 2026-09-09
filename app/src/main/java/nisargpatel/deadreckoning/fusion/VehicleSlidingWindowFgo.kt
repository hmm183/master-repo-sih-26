package nisargpatel.deadreckoning.fusion

import nisargpatel.deadreckoning.fusion.math.MatrixMath
import org.ejml.data.DenseMatrix64F
import org.osmdroid.util.GeoPoint
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Baseline 6: Sliding-Window Factor Graph Trajectory Optimizer (FGO).
 *
 * Maintains a sliding window of M = 15 keyframe poses X = [pE, pN, psi].
 * Formulates a nonlinear least squares factor graph incorporating:
 *  1. Prior anchor factor
 *  2. Between-motion odometry factors
 *  3. Non-holonomic zero-sideslip constraints
 *  4. Road centerline cross-track factors
 *  5. GNSS position factors
 *
 * Solved via damped Levenberg-Marquardt Gauss-Newton with step limiting.
 */
class VehicleSlidingWindowFgo(
    private val windowSize: Int = 15,
    private val maxIterations: Int = 4,
    private val lambdaDamping: Double = 2.0
) : VehicleEstimator {

    override val name: String = "FGO"

    data class Keyframe(
        var pe: Double,
        var pn: Double,
        var headingRad: Double,
        var speedMps: Double,
        var timestampSeconds: Double,
        var deltaFwd: Double = 0.0,
        var deltaHeading: Double = 0.0,
        var roadPe: Double? = null,
        var roadPn: Double? = null,
        var roadBearingRad: Double? = null,
        var gnssPe: Double? = null,
        var gnssPn: Double? = null
    )

    private var reference: GeoPoint? = null
    private val keyframes = ArrayDeque<Keyframe>()
    private var anchorPe = 0.0
    private var anchorPn = 0.0
    private var anchorPsi = 0.0
    private var currentTimeSeconds: Double = 0.0
    private var lastSpeedMps: Double = 0.0

    override fun isInitialized(): Boolean = reference != null

    override fun reset(
        position: GeoPoint,
        speedMps: Double,
        headingDegrees: Double,
        accuracyMeters: Double
    ) {
        reference = position
        keyframes.clear()
        currentTimeSeconds = 0.0
        lastSpeedMps = speedMps.coerceAtLeast(0.0)

        anchorPe = 0.0
        anchorPn = 0.0
        anchorPsi = MatrixMath.normalizeRadians(Math.toRadians(headingDegrees))

        keyframes.add(
            Keyframe(
                pe = 0.0,
                pn = 0.0,
                headingRad = anchorPsi,
                speedMps = lastSpeedMps,
                timestampSeconds = 0.0
            )
        )
    }

    override fun predict(
        forwardMeters: Double,
        lateralMeters: Double,
        headingDeltaRadians: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || !forwardMeters.isFinite()) return null
        val dt = intervalSeconds.coerceIn(0.01, 5.0)
        currentTimeSeconds += dt

        val last = keyframes.last()
        val newHeading = MatrixMath.normalizeRadians(last.headingRad + headingDeltaRadians)
        val newPe = last.pe + forwardMeters * sin(last.headingRad)
        val newPn = last.pn + forwardMeters * cos(last.headingRad)
        val speed = (forwardMeters / dt).coerceAtLeast(0.0)
        lastSpeedMps = speed

        val frame = Keyframe(
            pe = newPe,
            pn = newPn,
            headingRad = newHeading,
            speedMps = speed,
            timestampSeconds = currentTimeSeconds,
            deltaFwd = forwardMeters,
            deltaHeading = headingDeltaRadians
        )
        keyframes.add(frame)

        if (keyframes.size > windowSize) {
            val removed = keyframes.removeFirst()
            anchorPe = removed.pe
            anchorPn = removed.pn
            anchorPsi = removed.headingRad
        }

        optimizeGraph()
        return state()
    }

    override fun predictVelocity(
        forwardMps: Double,
        lateralMps: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        return predict(forwardMps * intervalSeconds, 0.0, 0.0, intervalSeconds)
    }

    override fun predictGyro(
        angularVelocityZRadPerSec: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || intervalSeconds <= 0.0) return null
        val last = keyframes.lastOrNull() ?: return null
        last.headingRad = MatrixMath.normalizeRadians(last.headingRad + angularVelocityZRadPerSec * intervalSeconds)
        return state()
    }

    override fun updateSpeed(measuredMps: Double, uncertaintyMps: Double): FusedVehicleState? {
        if (reference == null) return null
        lastSpeedMps = (lastSpeedMps + 0.3 * (measuredMps - lastSpeedMps)).coerceAtLeast(0.0)
        keyframes.lastOrNull()?.speedMps = lastSpeedMps
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
        val measPn = (position.latitude - ref.latitude) * 111_111.0
        val measPe = (position.longitude - ref.longitude) * (111_111.0 * cos(Math.toRadians(ref.latitude)))

        val latest = keyframes.last()
        latest.gnssPe = measPe
        latest.gnssPn = measPn
        lastSpeedMps = speedMps.coerceAtLeast(0.0)
        latest.speedMps = lastSpeedMps

        optimizeGraph()
        return state()!!
    }

    override fun updateMapConstraint(
        matchedPosition: GeoPoint,
        roadBearingDegrees: Double?,
        confidence: Int
    ): MapConstraintResult? {
        if (reference == null || roadBearingDegrees == null) return null
        val ref = reference!!
        val measPn = (matchedPosition.latitude - ref.latitude) * 111_111.0
        val measPe = (matchedPosition.longitude - ref.longitude) * (111_111.0 * cos(Math.toRadians(ref.latitude)))

        val latest = keyframes.last()
        latest.roadPe = measPe
        latest.roadPn = measPn

        // Ensure road bearing matches travel direction
        var bRad = Math.toRadians(roadBearingDegrees)
        if (abs(MatrixMath.shortestAngleDelta(latest.headingRad, bRad)) > Math.PI / 2.0) {
            bRad = MatrixMath.normalizeRadians(bRad + Math.PI)
        }
        latest.roadBearingRad = bRad

        optimizeGraph()
        return MapConstraintResult(true, state()!!, 0.0)
    }

    /**
     * Solves normal equations: H * DeltaX = b
     * where b = -grad = -J^T * W * r.
     */
    private fun optimizeGraph() {
        val n = keyframes.size
        if (n < 2) return

        val kfList = keyframes.toList()
        val numParams = 3 * n // [pe_i, pn_i, psi_i]

        for (iter in 0 until maxIterations) {
            val h = DenseMatrix64F(numParams, numParams)
            val b = DenseMatrix64F(numParams, 1)

            // Factor 1: Prior anchor on oldest keyframe (kf 0)
            val wPriorPos = 1.0 / 4.0
            val wPriorRot = 1.0 / 0.04
            val rPriorE = kfList[0].pe - anchorPe
            val rPriorN = kfList[0].pn - anchorPn
            val rPriorPsi = MatrixMath.shortestAngleDelta(anchorPsi, kfList[0].headingRad)

            h.set(0, 0, h.get(0, 0) + wPriorPos)
            h.set(1, 1, h.get(1, 1) + wPriorPos)
            h.set(2, 2, h.get(2, 2) + wPriorRot)
            b.set(0, 0, b.get(0, 0) - wPriorPos * rPriorE)
            b.set(1, 0, b.get(1, 0) - wPriorPos * rPriorN)
            b.set(2, 0, b.get(2, 0) - wPriorRot * rPriorPsi)

            // Factor 2 & 3: Between Odometry + NHC
            for (i in 0 until n - 1) {
                val kfA = kfList[i]
                val kfB = kfList[i + 1]

                val idxA = 3 * i
                val idxB = 3 * (i + 1)

                val psiA = kfA.headingRad
                val deltaFwd = kfB.deltaFwd
                val deltaPsi = kfB.deltaHeading

                val dx = kfB.pe - kfA.pe
                val dy = kfB.pn - kfA.pn

                val sA = sin(psiA)
                val cA = cos(psiA)

                // Residuals
                val rFwd = dx * sA + dy * cA - deltaFwd
                val rSide = dx * cA - dy * sA // NHC zero sideslip
                val rRot = MatrixMath.shortestAngleDelta(psiA + deltaPsi, kfB.headingRad)

                val wFwd = 1.0 / (0.05 * abs(deltaFwd) + 0.4).let { it * it }
                val wSide = 1.0 / 0.25 // strong non-holonomic constraint
                val wRot = 1.0 / 0.01

                // Hessian contributions:
                // d(rFwd)/d(peB) = sA, d(rFwd)/d(peA) = -sA
                // d(rFwd)/d(pnB) = cA, d(rFwd)/d(pnA) = -cA
                // d(rSide)/d(peB) = cA, d(rSide)/d(peA) = -cA
                // d(rSide)/d(pnB) = -sA, d(rSide)/d(pnA) = sA
                val hEE = wFwd * sA * sA + wSide * cA * cA
                val hNN = wFwd * cA * cA + wSide * sA * sA
                val hEN = (wFwd - wSide) * sA * cA

                // Node A
                h.set(idxA, idxA, h.get(idxA, idxA) + hEE)
                h.set(idxA + 1, idxA + 1, h.get(idxA + 1, idxA + 1) + hNN)
                h.set(idxA, idxA + 1, h.get(idxA, idxA + 1) + hEN)
                h.set(idxA + 1, idxA, h.get(idxA + 1, idxA) + hEN)

                // Node B
                h.set(idxB, idxB, h.get(idxB, idxB) + hEE)
                h.set(idxB + 1, idxB + 1, h.get(idxB + 1, idxB + 1) + hNN)
                h.set(idxB, idxB + 1, h.get(idxB, idxB + 1) + hEN)
                h.set(idxB + 1, idxB, h.get(idxB + 1, idxB) + hEN)

                // Off-diagonal A-B
                h.set(idxA, idxB, h.get(idxA, idxB) - hEE)
                h.set(idxB, idxA, h.get(idxB, idxA) - hEE)
                h.set(idxA + 1, idxB + 1, h.get(idxA + 1, idxB + 1) - hNN)
                h.set(idxB + 1, idxA + 1, h.get(idxB + 1, idxA + 1) - hNN)
                h.set(idxA, idxB + 1, h.get(idxA, idxB + 1) - hEN)
                h.set(idxB + 1, idxA, h.get(idxB + 1, idxA) - hEN)
                h.set(idxA + 1, idxB, h.get(idxA + 1, idxB) - hEN)
                h.set(idxB, idxA + 1, h.get(idxB, idxA + 1) - hEN)

                // Rotation factor
                h.set(idxA + 2, idxA + 2, h.get(idxA + 2, idxA + 2) + wRot)
                h.set(idxB + 2, idxB + 2, h.get(idxB + 2, idxB + 2) + wRot)
                h.set(idxA + 2, idxB + 2, h.get(idxA + 2, idxB + 2) - wRot)
                h.set(idxB + 2, idxA + 2, h.get(idxB + 2, idxA + 2) - wRot)

                // RHS b = -grad = -J^T * W * r
                val gFwdE = wFwd * rFwd * sA
                val gFwdN = wFwd * rFwd * cA
                val gSideE = wSide * rSide * cA
                val gSideN = -wSide * rSide * sA

                b.set(idxA, 0, b.get(idxA, 0) + (gFwdE + gSideE))
                b.set(idxA + 1, 0, b.get(idxA + 1, 0) + (gFwdN + gSideN))
                b.set(idxB, 0, b.get(idxB, 0) - (gFwdE + gSideE))
                b.set(idxB + 1, 0, b.get(idxB + 1, 0) - (gFwdN + gSideN))

                b.set(idxA + 2, 0, b.get(idxA + 2, 0) + wRot * rRot)
                b.set(idxB + 2, 0, b.get(idxB + 2, 0) - wRot * rRot)
            }

            // Factor 4: Road centerline cross-track factors
            for (i in 0 until n) {
                val kf = kfList[i]
                if (kf.roadPe != null && kf.roadPn != null && kf.roadBearingRad != null) {
                    val idx = 3 * i
                    val bRad = kf.roadBearingRad!!
                    val crossE = cos(bRad)
                    val crossN = -sin(bRad)
                    val crossDist = (kf.pe - kf.roadPe!!) * crossE + (kf.pn - kf.roadPn!!) * crossN
                    val wRoad = 1.0 / 4.0

                    h.set(idx, idx, h.get(idx, idx) + wRoad * crossE * crossE)
                    h.set(idx + 1, idx + 1, h.get(idx + 1, idx + 1) + wRoad * crossN * crossN)
                    h.set(idx, idx + 1, h.get(idx, idx + 1) + wRoad * crossE * crossN)
                    h.set(idx + 1, idx, h.get(idx + 1, idx) + wRoad * crossE * crossN)

                    b.set(idx, 0, b.get(idx, 0) - wRoad * crossDist * crossE)
                    b.set(idx + 1, 0, b.get(idx + 1, 0) - wRoad * crossDist * crossN)
                }
            }

            // Factor 5: GNSS factors
            for (i in 0 until n) {
                val kf = kfList[i]
                if (kf.gnssPe != null && kf.gnssPn != null) {
                    val idx = 3 * i
                    val wGnss = 1.0 / 9.0
                    h.set(idx, idx, h.get(idx, idx) + wGnss)
                    h.set(idx + 1, idx + 1, h.get(idx + 1, idx + 1) + wGnss)

                    b.set(idx, 0, b.get(idx, 0) - wGnss * (kf.pe - kf.gnssPe!!))
                    b.set(idx + 1, 0, b.get(idx + 1, 0) - wGnss * (kf.pn - kf.gnssPn!!))
                }
            }

            // Damping: H + lambda * I
            for (p in 0 until numParams) {
                h.set(p, p, h.get(p, p) + lambdaDamping)
            }

            val delta = MatrixMath.solve(h, b)

            for (i in 0 until n) {
                val idx = 3 * i
                val dPe = delta.get(idx, 0).coerceIn(-3.0, 3.0)
                val dPn = delta.get(idx + 1, 0).coerceIn(-3.0, 3.0)
                val dPsi = delta.get(idx + 2, 0).coerceIn(-0.15, 0.15)

                kfList[i].pe += dPe
                kfList[i].pn += dPn
                kfList[i].headingRad = MatrixMath.normalizeRadians(kfList[i].headingRad + dPsi)
            }
        }
    }

    override fun state(): FusedVehicleState? {
        val ref = reference ?: return null
        val latest = keyframes.lastOrNull() ?: return null

        val lat = ref.latitude + latest.pn / 111_111.0
        val lon = ref.longitude + latest.pe / (111_111.0 * cos(Math.toRadians(ref.latitude)))
        val headingDeg = Math.toDegrees(MatrixMath.normalizeRadians(latest.headingRad)).let {
            if (it < 0.0) it + 360.0 else it
        }

        return FusedVehicleState(
            position = GeoPoint(lat, lon),
            speedMps = latest.speedMps,
            headingDegrees = headingDeg,
            horizontalUncertaintyMeters = 3.0,
            alongTrackUncertaintyMeters = 2.0,
            crossTrackUncertaintyMeters = 2.0,
            speedUncertaintyMps = 0.3,
            headingUncertaintyDegrees = 2.0
        )
    }
}
