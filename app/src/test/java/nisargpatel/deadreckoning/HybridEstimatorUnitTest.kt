package nisargpatel.deadreckoning

import nisargpatel.deadreckoning.fusion.VehicleEstimator
import nisargpatel.deadreckoning.fusion.VehicleFusionEkf
import nisargpatel.deadreckoning.fusion.VehicleFusionImmUkf
import nisargpatel.deadreckoning.fusion.VehicleFusionUkf
import nisargpatel.deadreckoning.fusion.VehicleHierarchicalHybridEstimator
import nisargpatel.deadreckoning.fusion.VehicleParticleFilter
import nisargpatel.deadreckoning.fusion.VehicleRbpf
import nisargpatel.deadreckoning.fusion.VehicleSlidingWindowFgo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint
import kotlin.math.abs

class HybridEstimatorUnitTest {

    private val testStart = GeoPoint(51.5074, -0.1278) // London coordinates

    @Test
    fun `all seven estimators implement VehicleEstimator contract`() {
        val estimators: List<VehicleEstimator> = listOf(
            VehicleFusionEkf(),
            VehicleFusionUkf(),
            VehicleFusionImmUkf(),
            VehicleParticleFilter(),
            VehicleRbpf(),
            VehicleSlidingWindowFgo(),
            VehicleHierarchicalHybridEstimator()
        )

        for (est in estimators) {
            assertTrue("Estimator ${est.name} should not be initialized initially", !est.isInitialized())
            est.reset(testStart, 10.0, 90.0, 5.0)
            assertTrue("Estimator ${est.name} should be initialized after reset", est.isInitialized())

            val state = est.state()
            assertNotNull("State must not be null for ${est.name}", state)
            assertEquals(10.0, state!!.speedMps, 1.5)

            // Predict forward 10 meters east (heading 90 deg)
            val propagated = est.predict(forwardMeters = 10.0, lateralMeters = 0.0, headingDeltaRadians = 0.0, intervalSeconds = 1.0)
            assertNotNull("Propagated state must not be null for ${est.name}", propagated)
        }
    }

    @Test
    fun `IMM-UKF detects motion modes and applies stationary ZUPT floor`() {
        val imm = VehicleFusionImmUkf()
        imm.reset(testStart, 12.0, 0.0, 3.0)

        // 1. Straight cruising: constant velocity
        for (i in 0 until 5) {
            imm.predict(forwardMeters = 12.0, lateralMeters = 0.0, headingDeltaRadians = 0.0, intervalSeconds = 1.0)
        }
        assertTrue("CV mode probability should be dominant on straight road", imm.modeProbabilities[0] > 0.40)

        // 2. Hard turn: constant turn rate
        for (i in 0 until 4) {
            imm.predict(forwardMeters = 8.0, lateralMeters = 0.0, headingDeltaRadians = Math.toRadians(25.0), intervalSeconds = 1.0)
        }
        assertTrue("CTRV mode probability should increase during hard turn", imm.modeProbabilities[1] > 0.20)

        // 3. Stationary stop (ZUPT): zero forward displacement
        for (i in 0 until 3) {
            imm.predict(forwardMeters = 0.0, lateralMeters = 0.0, headingDeltaRadians = 0.0, intervalSeconds = 1.0)
        }
        val stoppedState = imm.state()
        assertNotNull(stoppedState)
        assertEquals("Speed must clamp to zero at stationary floor", 0.0, stoppedState!!.speedMps, 1e-3)
        assertTrue("CV/stationary mode should dominate when stopped", imm.modeProbabilities[0] > 0.80)
    }

    @Test
    fun `RBPF maintains multi-hypothesis road constraint and tracks top hypothesis`() {
        val rbpf = VehicleRbpf(numParticles = 20)
        rbpf.reset(testStart, 10.0, 45.0, 3.0)

        // Propagate forward
        for (i in 0 until 5) {
            rbpf.predict(forwardMeters = 10.0, lateralMeters = 0.0, headingDeltaRadians = 0.0, intervalSeconds = 1.0)
        }

        // Apply road constraint
        val roadPos = GeoPoint(testStart.latitude + 0.0003, testStart.longitude + 0.0003)
        rbpf.updateMapConstraint(roadPos, roadBearingDegrees = 45.0, confidence = 90)

        val top = rbpf.topHypothesis
        assertNotNull("Top hypothesis should be present", top)
        assertTrue("Top hypothesis weight should be positive", top!!.weight > 0.0)
    }

    @Test
    fun `FGO optimizes trajectory against road centerline`() {
        val fgo = VehicleSlidingWindowFgo(windowSize = 10)
        fgo.reset(testStart, 10.0, 0.0, 2.0)

        // Drive North 5 steps
        for (i in 0 until 5) {
            fgo.predict(forwardMeters = 10.0, lateralMeters = 0.0, headingDeltaRadians = 0.0, intervalSeconds = 1.0)
        }

        val stateBefore = fgo.state()
        assertNotNull(stateBefore)

        // Update road constraint pulling slightly East
        val snapped = GeoPoint(stateBefore!!.position.latitude, stateBefore.position.longitude + 0.00005)
        fgo.updateMapConstraint(snapped, roadBearingDegrees = 0.0, confidence = 85)

        val stateAfter = fgo.state()
        assertNotNull(stateAfter)
        assertTrue("Trajectory should stay valid after optimization", stateAfter!!.speedMps >= 0.0)
    }

    @Test
    fun `Proposed Hybrid integrates all three tiers harmoniously`() {
        val hybrid = VehicleHierarchicalHybridEstimator()
        hybrid.reset(testStart, 15.0, 90.0, 4.0)

        for (i in 0 until 10) {
            val state = hybrid.predict(
                forwardMeters = 15.0,
                lateralMeters = 0.0,
                headingDeltaRadians = Math.toRadians(2.0),
                intervalSeconds = 1.0
            )
            assertNotNull(state)
            assertTrue("Speed should be positive", state!!.speedMps > 0.0)
            assertTrue("Uncertainty should be bounded (${state.horizontalUncertaintyMeters})", state.horizontalUncertaintyMeters < 60.0)
        }

        assertTrue("Hybrid must produce non-empty mode probabilities", hybrid.currentModeProbabilities.isNotEmpty())
    }
}
