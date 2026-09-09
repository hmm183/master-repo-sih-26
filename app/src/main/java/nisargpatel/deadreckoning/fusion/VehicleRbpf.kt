package nisargpatel.deadreckoning.fusion

import nisargpatel.deadreckoning.data.RoadCandidate
import nisargpatel.deadreckoning.fusion.math.MatrixMath
import org.osmdroid.util.GeoPoint
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Baseline 5: Rao-Blackwellized Particle Filter (RBPF).
 *
 * Decomposes state into:
 * 1. Discrete state: Topological road segment/branch hypotheses r^(m) (tracked by N = 30 particles).
 * 2. Continuous state: Progress along road s, cross-track offset d, and speed v
 *    (tracked analytically per particle via linear Gaussian Kalman updates).
 *
 * When GNSS is off, RBPF confines vehicle hypotheses to the 1D road corridor,
 * preventing cross-track error from exploding into the hundreds of meters.
 */
class VehicleRbpf(
    private val numParticles: Int = 30,
    private val seed: Long = 101L
) : VehicleEstimator {

    override val name: String = "RBPF"

    data class RoadHypothesis(
        val wayId: Long,
        val roadName: String,
        val bearingDeg: Double,
        val weight: Double
    )

    data class RbParticle(
        var wayId: Long,
        var roadName: String,
        var bearingRad: Double,
        var pe: Double,
        var pn: Double,
        var speed: Double,
        var crossTrackOffset: Double,
        var crossTrackVar: Double,
        var weight: Double,
        var roadBearingRad: Double = bearingRad,
        var hasRoadConstraint: Boolean = false
    )

    private var reference: GeoPoint? = null
    private val random = Random(seed)
    private var particles: Array<RbParticle> = emptyArray()

    val topHypothesis: RoadHypothesis?
        get() {
            if (particles.isEmpty()) return null
            val best = particles.maxByOrNull { it.weight } ?: return null
            val wayWeight = particles.filter { it.wayId == best.wayId }.sumOf { it.weight }
            return RoadHypothesis(
                wayId = best.wayId,
                roadName = best.roadName,
                bearingDeg = Math.toDegrees(best.bearingRad),
                weight = wayWeight
            )
        }

    override fun isInitialized(): Boolean = reference != null

    override fun reset(
        position: GeoPoint,
        speedMps: Double,
        headingDegrees: Double,
        accuracyMeters: Double
    ) {
        reference = position
        val headRad = MatrixMath.normalizeRadians(Math.toRadians(headingDegrees))
        val posSigma = accuracyMeters.coerceAtLeast(3.0)

        particles = Array(numParticles) {
            RbParticle(
                wayId = 0L,
                roadName = "Initial Way",
                bearingRad = headRad,
                pe = random.nextGaussian() * posSigma,
                pn = random.nextGaussian() * posSigma,
                speed = speedMps.coerceAtLeast(0.0),
                crossTrackOffset = 0.0,
                crossTrackVar = 4.0,
                weight = 1.0 / numParticles,
                roadBearingRad = headRad,
                hasRoadConstraint = true
            )
        }
    }

    override fun predict(
        forwardMeters: Double,
        lateralMeters: Double,
        headingDeltaRadians: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || !forwardMeters.isFinite()) return null
        val dt = intervalSeconds.coerceIn(0.01, 5.0)

        for (p in particles) {
            val d = forwardMeters + random.nextGaussian() * (0.02 * abs(forwardMeters) + 0.1)
            val dPsi = headingDeltaRadians + random.nextGaussian() * 0.005
            p.bearingRad = MatrixMath.normalizeRadians(p.bearingRad + dPsi)
            p.speed = (d / dt).coerceAtLeast(0.0)

            if (p.hasRoadConstraint) {
                // Road corridor projection along road axis
                val roadTheta = p.roadBearingRad
                val alongE = sin(roadTheta)
                val alongN = cos(roadTheta)
                val crossE = cos(roadTheta)
                val crossN = -sin(roadTheta)

                // Angular difference between vehicle heading and road centerline
                val headingDiff = MatrixMath.shortestAngleDelta(roadTheta, p.bearingRad)

                // Longitudinal displacement along road manifold
                val alongDist = d * cos(headingDiff)

                // Cross-track motion (strictly bounded within road lane width)
                val latDelta = d * sin(headingDiff) + lateralMeters + random.nextGaussian() * 0.1

                // 1D Kalman update on cross-track deviation towards road centerline (pseudo-measurement = 0)
                val priorVar = (p.crossTrackVar + 0.05 * dt).coerceAtMost(9.0)
                val rVar = 2.25
                val k = priorVar / (priorVar + rVar)

                p.crossTrackOffset = ((1.0 - k) * (p.crossTrackOffset + latDelta)).coerceIn(-3.5, 3.5)
                p.crossTrackVar = (1.0 - k) * priorVar

                // Propagate 2D position along the road manifold
                p.pe += alongDist * alongE + (p.crossTrackOffset * 0.1) * crossE
                p.pn += alongDist * alongN + (p.crossTrackOffset * 0.1) * crossN

                // Road heading guidance: soft pull towards road orientation to prevent unbounded yaw drift
                p.bearingRad = MatrixMath.normalizeRadians(p.bearingRad - 0.15 * headingDiff)
            } else {
                p.pe += d * sin(p.bearingRad)
                p.pn += d * cos(p.bearingRad)
                p.crossTrackVar = (p.crossTrackVar + 0.1 * dt).coerceAtMost(16.0)
            }
        }

        normalizeWeights()
        return state()
    }

    override fun predictVelocity(
        forwardMps: Double,
        lateralMps: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || intervalSeconds <= 0.0) return null
        val dt = intervalSeconds.coerceIn(0.001, 1.0)
        return predict(forwardMps * dt, lateralMps * dt, 0.0, dt)
    }

    override fun predictGyro(
        angularVelocityZRadPerSec: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || intervalSeconds <= 0.0) return null
        val dHead = angularVelocityZRadPerSec * intervalSeconds

        for (p in particles) {
            p.bearingRad = MatrixMath.normalizeRadians(p.bearingRad + dHead)
        }
        return state()
    }

    override fun updateSpeed(measuredMps: Double, uncertaintyMps: Double): FusedVehicleState? {
        if (reference == null) return null
        val rVar = (uncertaintyMps * uncertaintyMps).coerceAtLeast(0.2)

        for (p in particles) {
            val k = 0.6
            p.speed = (p.speed + k * (measuredMps - p.speed)).coerceAtLeast(0.0)
            val err = p.speed - measuredMps
            p.weight *= exp(-0.5 * (err * err) / rVar)
        }
        normalizeAndResample()
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
        val rVar = accuracyMeters.coerceAtLeast(3.0).let { it * it }

        for (p in particles) {
            val distSq = (p.pe - measPe) * (p.pe - measPe) + (p.pn - measPn) * (p.pn - measPn)
            p.weight *= exp(-0.5 * distSq / rVar)

            // Analytic Kalman correction of position
            val k = 0.5
            p.pe += k * (measPe - p.pe)
            p.pn += k * (measPn - p.pn)
            p.crossTrackVar = 4.0
        }

        normalizeAndResample()
        return state()!!
    }

    /**
     * Map matching update with multi-hypothesis branching.
     */
    fun updateMapCandidates(candidates: List<RoadCandidate>, currentTurnRate: Double = 0.0): Boolean {
        if (reference == null || candidates.isEmpty()) return false
        val ref = reference!!

        // Distribute particles across top road candidates
        val topCandidates = candidates.take(4)
        val numPerCand = numParticles / topCandidates.size

        var pIdx = 0
        for (cand in topCandidates) {
            val wayId = cand.wayId
            val roadName = cand.roadName
            var bearingRad = Math.toRadians(cand.bearingDegrees)
            val refHeading = particles.firstOrNull()?.bearingRad ?: bearingRad
            if (abs(MatrixMath.shortestAngleDelta(refHeading, bearingRad)) > Math.PI / 2.0) {
                bearingRad = MatrixMath.normalizeRadians(bearingRad + Math.PI)
            }
            val measPn = (cand.point.latitude - ref.latitude) * 111_111.0
            val measPe = (cand.point.longitude - ref.longitude) * (111_111.0 * cos(Math.toRadians(ref.latitude)))

            val limit = minOf(pIdx + numPerCand, numParticles)
            while (pIdx < limit) {
                val p = particles[pIdx]
                p.wayId = wayId
                p.roadName = roadName
                p.bearingRad = bearingRad

                // Snap particle onto road centerline with small noise
                val crossE = cos(bearingRad)
                val crossN = -sin(bearingRad)
                val alongE = sin(bearingRad)
                val alongN = cos(bearingRad)

                val alongDist = (p.pe - measPe) * alongE + (p.pn - measPn) * alongN
                val crossDist = (random.nextGaussian() * 0.5).coerceIn(-2.0, 2.0)
                p.pe = measPe + alongDist * alongE + crossDist * crossE
                p.pn = measPn + alongDist * alongN + crossDist * crossN
                p.crossTrackOffset = crossDist
                p.crossTrackVar = 2.0
                p.roadBearingRad = bearingRad
                p.hasRoadConstraint = true

                // Weight based on distance to road + turn rate alignment
                var w = exp(-0.5 * (cand.distanceMeters * cand.distanceMeters) / 36.0)
                if (abs(currentTurnRate) > 0.15) {
                    val turnSign = if (currentTurnRate > 0) 1.0 else -1.0
                    val angleDiff = MatrixMath.shortestAngleDelta(p.bearingRad, bearingRad)
                    if (angleDiff * turnSign > 0) w *= 1.5 // rewarded for matching turn direction
                }
                p.weight *= w
                pIdx++
            }
        }

        normalizeAndResample()
        return true
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
        var bearingRad = Math.toRadians(roadBearingDegrees)
        val refHeading = particles.firstOrNull()?.bearingRad ?: bearingRad
        if (abs(MatrixMath.shortestAngleDelta(refHeading, bearingRad)) > Math.PI / 2.0) {
            bearingRad = MatrixMath.normalizeRadians(bearingRad + Math.PI)
        }
        val crossE = cos(bearingRad)
        val crossN = -sin(bearingRad)

        for (p in particles) {
            p.hasRoadConstraint = true
            p.roadBearingRad = bearingRad

            val dx = p.pe - measPe
            val dy = p.pn - measPn
            val crossDist = dx * crossE + dy * crossN

            // 1D Kalman update of cross-track offset
            val k = p.crossTrackVar / (p.crossTrackVar + 2.25)
            p.pe -= k * crossDist * crossE
            p.pn -= k * crossDist * crossN
            p.crossTrackOffset = (1.0 - k) * crossDist
            p.crossTrackVar *= (1.0 - k)

            p.weight *= exp(-0.5 * (crossDist * crossDist) / 8.0)
        }

        normalizeAndResample()
        return MapConstraintResult(true, state()!!, 0.0)
    }

    private fun normalizeWeights() {
        var sum = 0.0
        for (p in particles) sum += p.weight
        if (sum > 1e-12) {
            for (p in particles) p.weight /= sum
        } else {
            val uniform = 1.0 / numParticles
            for (p in particles) p.weight = uniform
        }
    }

    private fun normalizeAndResample() {
        normalizeWeights()
        var sumSq = 0.0
        for (p in particles) sumSq += p.weight * p.weight
        val nEff = if (sumSq > 0.0) 1.0 / sumSq else 0.0

        if (nEff < numParticles / 2.0) {
            val newParticles = Array(numParticles) { particles[0].copy() }
            val step = 1.0 / numParticles
            var r = random.nextDouble() * step
            var c = particles[0].weight
            var i = 0

            for (m in 0 until numParticles) {
                val u = r + m * step
                while (u > c && i < numParticles - 1) {
                    i++
                    c += particles[i].weight
                }
                newParticles[m] = particles[i].copy(weight = 1.0 / numParticles)
            }
            particles = newParticles
        }
    }

    override fun state(): FusedVehicleState? {
        val ref = reference ?: return null
        if (particles.isEmpty()) return null

        var meanPe = 0.0
        var meanPn = 0.0
        var meanSpeed = 0.0
        var sinSum = 0.0
        var cosSum = 0.0

        for (p in particles) {
            meanPe += p.weight * p.pe
            meanPn += p.weight * p.pn
            meanSpeed += p.weight * p.speed
            sinSum += p.weight * sin(p.bearingRad)
            cosSum += p.weight * cos(p.bearingRad)
        }

        val meanHeading = atan2(sinSum, cosSum)

        var varPe = 0.0
        var varPn = 0.0
        for (p in particles) {
            varPe += p.weight * (p.pe - meanPe) * (p.pe - meanPe)
            varPn += p.weight * (p.pn - meanPn) * (p.pn - meanPn)
        }

        val lat = ref.latitude + meanPn / 111_111.0
        val lon = ref.longitude + meanPe / (111_111.0 * cos(Math.toRadians(ref.latitude)))
        val worstHorizSigma = sqrt(maxOf(varPe, varPn)).coerceAtLeast(1.0)
        val headingDeg = Math.toDegrees(MatrixMath.normalizeRadians(meanHeading)).let {
            if (it < 0.0) it + 360.0 else it
        }

        return FusedVehicleState(
            position = GeoPoint(lat, lon),
            speedMps = meanSpeed,
            headingDegrees = headingDeg,
            horizontalUncertaintyMeters = worstHorizSigma,
            alongTrackUncertaintyMeters = sqrt(varPe),
            crossTrackUncertaintyMeters = sqrt(varPn),
            speedUncertaintyMps = 0.3,
            headingUncertaintyDegrees = 3.0
        )
    }
}
