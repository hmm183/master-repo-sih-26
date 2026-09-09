package nisargpatel.deadreckoning.fusion

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
 * Baseline 4: Classical Sequential Importance Resampling (SIR) Particle Filter.
 *
 * Maintains N = 100 particles representing position, speed, and heading.
 * Evaluates observation likelihoods against GNSS fixes and road network distances.
 */
class VehicleParticleFilter(
    private val numParticles: Int = 100,
    private val seed: Long = 42L
) : VehicleEstimator {

    override val name: String = "PF"

    data class Particle(
        var pe: Double,
        var pn: Double,
        var speed: Double,
        var heading: Double,
        var weight: Double
    )

    private var reference: GeoPoint? = null
    private val random = Random(seed)
    private var particles: Array<Particle> = emptyArray()

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
            Particle(
                pe = random.nextGaussian() * posSigma,
                pn = random.nextGaussian() * posSigma,
                speed = (speedMps + random.nextGaussian() * 0.5).coerceAtLeast(0.0),
                heading = MatrixMath.normalizeRadians(headRad + random.nextGaussian() * 0.05),
                weight = 1.0 / numParticles
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
            val dFwd = forwardMeters + random.nextGaussian() * (0.05 * abs(forwardMeters) + 0.3)
            val dPsi = headingDeltaRadians + random.nextGaussian() * 0.02
            p.heading = MatrixMath.normalizeRadians(p.heading + dPsi)
            p.pe += dFwd * sin(p.heading)
            p.pn += dFwd * cos(p.heading)
            p.speed = (dFwd / dt).coerceAtLeast(0.0)
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

        for (p in particles) {
            val v = (forwardMps + random.nextGaussian() * 0.2).coerceAtLeast(0.0)
            p.pe += v * dt * sin(p.heading)
            p.pn += v * dt * cos(p.heading)
            p.speed = v
        }
        return state()
    }

    override fun predictGyro(
        angularVelocityZRadPerSec: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null || intervalSeconds <= 0.0) return null
        val dHead = angularVelocityZRadPerSec * intervalSeconds

        for (p in particles) {
            p.heading = MatrixMath.normalizeRadians(p.heading + dHead + random.nextGaussian() * 0.001)
        }
        return state()
    }

    override fun updateSpeed(measuredMps: Double, uncertaintyMps: Double): FusedVehicleState? {
        if (reference == null) return null
        val sigma = uncertaintyMps.coerceAtLeast(0.5)

        for (p in particles) {
            val err = p.speed - measuredMps
            p.weight *= exp(-0.5 * (err * err) / (sigma * sigma))
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
        val sigma = accuracyMeters.coerceAtLeast(3.0)

        for (p in particles) {
            val distSq = (p.pe - measPe) * (p.pe - measPe) + (p.pn - measPn) * (p.pn - measPn)
            p.weight *= exp(-0.5 * distSq / (sigma * sigma))
        }

        normalizeAndResample()
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
        val bearingRad = Math.toRadians(roadBearingDegrees)
        val crossE = cos(bearingRad)
        val crossN = -sin(bearingRad)

        for (p in particles) {
            val dx = p.pe - measPe
            val dy = p.pn - measPn
            val crossDist = dx * crossE + dy * crossN
            p.weight *= exp(-0.5 * (crossDist * crossDist) / 16.0)
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

        // Effective sample size
        var sumSq = 0.0
        for (p in particles) sumSq += p.weight * p.weight
        val nEff = if (sumSq > 0.0) 1.0 / sumSq else 0.0

        if (nEff < numParticles / 2.0) {
            // Low-variance systematic resampling
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
            sinSum += p.weight * sin(p.heading)
            cosSum += p.weight * cos(p.heading)
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
            speedUncertaintyMps = 0.5,
            headingUncertaintyDegrees = 5.0
        )
    }
}
