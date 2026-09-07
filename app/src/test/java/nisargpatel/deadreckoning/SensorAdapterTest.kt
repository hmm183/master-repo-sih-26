package nisargpatel.deadreckoning

import nisargpatel.deadreckoning.adapter.GyroBiasEstimator
import nisargpatel.deadreckoning.adapter.StationaryDebounceFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorAdapterTest {

    @Test
    fun `stationary debounce requires consecutive samples to latch`() {
        val filter = StationaryDebounceFilter(enterThreshold = 15, exitThreshold = 5)

        // 14 stable samples: should NOT latch stationary yet
        for (i in 1..14) {
            val isStat = filter.update(isCandidateStationary = true)
            assertFalse("Should not be stationary at sample $i", isStat)
        }

        // 15th sample: latches stationary
        val isStat15 = filter.update(isCandidateStationary = true)
        assertTrue("Must latch stationary at 15th consecutive sample", isStat15)

        // Single noisy glitch (e.g. road vibration): should NOT drop stationary
        for (i in 1..4) {
            val isStatGlitch = filter.update(isCandidateStationary = false)
            assertTrue("Glitch sample $i must not drop stationary state", isStatGlitch)
        }

        // 5th consecutive motion sample: drops stationary
        val isStatExit = filter.update(isCandidateStationary = false)
        assertFalse("Must exit stationary after 5 consecutive motion samples", isStatExit)
    }

    @Test
    fun `gyro bias bootstrap resolves cold start deadlock with high factory bias`() {
        // High factory bias on yaw: 0.15 rad/s (exceeds the 0.08 rad/s stationary threshold)
        val estimator = GyroBiasEstimator(bootstrapLimit = 60, minSamplesForEstimate = 30)
        val rawFactoryGyro = floatArrayOf(0.01f, -0.02f, 0.15f)

        // At boot, isStationary is false, but bootstrap window permits learning under ~9.81 m/s² gravity
        var lastCorrected = FloatArray(3)
        for (i in 1..40) {
            lastCorrected = estimator.addSample(
                rawGyro = rawFactoryGyro,
                isStationary = false,
                gravityMagnitude = 9.81f,
                externalStationaryHint = false
            )
        }

        // Check that bias has converged to rawFactoryGyro and corrected gyro is near zero
        assertEquals(0.15f, estimator.gyroBias[2], 0.001f)
        assertEquals(0.0f, lastCorrected[2], 0.001f)
    }

    @Test
    fun `external model stationary hint permits bias learning even without hardware latch`() {
        val estimator = GyroBiasEstimator(bootstrapLimit = 10, minSamplesForEstimate = 5)
        val rawGyro = floatArrayOf(0.05f, 0.05f, 0.05f)

        // Exhaust bootstrap
        for (i in 1..12) {
            estimator.addSample(rawGyro, isStationary = false, gravityMagnitude = 9.81f)
        }
        estimator.reset()

        // With external stationary hint (e.g. PINO-DR ZUPT stop classification p_stop > 0.70)
        var corrected = FloatArray(3)
        for (i in 1..10) {
            corrected = estimator.addSample(
                rawGyro = rawGyro,
                isStationary = false,
                gravityMagnitude = 9.81f,
                externalStationaryHint = true
            )
        }

        assertEquals(0.05f, estimator.gyroBias[0], 0.001f)
        assertEquals(0.0f, corrected[0], 0.001f)
    }
}
