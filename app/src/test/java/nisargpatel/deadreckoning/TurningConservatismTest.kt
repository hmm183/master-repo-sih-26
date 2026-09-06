package nisargpatel.deadreckoning

import kotlin.math.abs
import nisargpatel.deadreckoning.fusion.HeadingPolicy
import nisargpatel.deadreckoning.fusion.NonHolonomicConfig
import nisargpatel.deadreckoning.fusion.TurningConservatismConfig
import nisargpatel.deadreckoning.fusion.VehicleFusionEkf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Guards the turning-conservatism gate.
 *
 * Two properties matter and, as with the non-holonomic constraint, they pull against
 * each other:
 *
 *  1. On a straight or gently curving drive the gate MUST be a no-op, otherwise it
 *     would turn a small everyday sideslip into a large sideslip on the drives that
 *     dominate the total mileage.
 *  2. On a hard-turning drive the gate MUST cut injected lateral error more
 *     aggressively than the baseline non-holonomic constraint alone, because that is
 *     the entire point.
 *
 * A test that only checked the first property could pass with the gate disabled; a
 * test that only checked the second could pass with the gate stuck fully on; both are
 * therefore asserted together.
 */
class TurningConservatismTest {

    private val origin = GeoPoint(16.5, 80.6)
    private val sampleRateHz = 10
    private val dt = 1.0 / sampleRateHz

    @Test
    fun `engagement ramps linearly between low and high thresholds`() {
        val config = TurningConservatismConfig(enabled = true)
        assertEquals(0.0, config.engagement(0.0), 1e-9)
        assertEquals(0.0, config.engagement(config.lowYawRateRadPerSec - 0.01), 1e-9)
        assertEquals(1.0, config.engagement(config.highYawRateRadPerSec + 0.01), 1e-9)
        val midpoint = (config.lowYawRateRadPerSec + config.highYawRateRadPerSec) / 2
        assertEquals(0.5, config.engagement(midpoint), 1e-6)
    }

    @Test
    fun `disabled config engages nothing`() {
        val config = TurningConservatismConfig.DISABLED
        assertEquals(0.0, config.engagement(0.0), 1e-9)
        assertEquals(0.0, config.engagement(10.0), 1e-9)
    }

    @Test
    fun `configuration rejects nonsensical thresholds`() {
        val exception = runCatching {
            TurningConservatismConfig(
                enabled = true,
                lowYawRateRadPerSec = 0.5,
                highYawRateRadPerSec = 0.3
            )
        }.exceptionOrNull()
        assertTrue(
            "expected IllegalArgumentException, got $exception",
            exception is IllegalArgumentException
        )
    }

    /**
     * Feed one PINO-scale window (1 s span, moderate speed) with an intentionally
     * injected sideslip. With turning conservatism off, the baseline NHC correction is
     * applied. With it on and the yaw rate above the high threshold, the correction
     * must be larger, i.e. the filter is leaning harder on physics.
     */
    @Test
    fun `boosted lateral gain kicks in on a hard turn`() {
        val forward = 12.0
        // Model claim well above the honest lateral for the yaw rate, so the sideslip
        // being suppressed is unambiguously large and the corrections are separable.
        val lateralModelClaim = 6.0
        // 30 deg/s over 1 s is well above the 0.35 rad/s high threshold.
        val headingDelta = Math.toRadians(30.0)
        val interval = 1.0

        val baseline = runOneWindow(
            NonHolonomicConfig(), TurningConservatismConfig.DISABLED,
            forward, lateralModelClaim, headingDelta, interval
        )
        val boosted = runOneWindow(
            NonHolonomicConfig(), TurningConservatismConfig.ENABLED,
            forward, lateralModelClaim, headingDelta, interval
        )

        assertTrue(
            "baseline should apply a substantial NHC correction, got ${baseline.correction}",
            abs(baseline.correction) > 1.0
        )
        assertTrue(
            "boosted correction ${boosted.correction} should exceed baseline ${baseline.correction} " +
                "on a hard turn",
            abs(boosted.correction) > abs(baseline.correction) * 1.15
        )
        assertEquals(
            "gate must have engaged on a hard turn",
            1, boosted.engagements
        )
    }

    @Test
    fun `gate is a no-op below the low threshold`() {
        val forward = 12.0
        val lateralModelClaim = 1.0
        // 3 deg/s over 1 s is 0.052 rad/s, safely below the 0.10 rad/s low threshold.
        val headingDelta = Math.toRadians(3.0)
        val interval = 1.0

        val baseline = runOneWindow(
            NonHolonomicConfig(), TurningConservatismConfig.DISABLED,
            forward, lateralModelClaim, headingDelta, interval
        )
        val boosted = runOneWindow(
            NonHolonomicConfig(), TurningConservatismConfig.ENABLED,
            forward, lateralModelClaim, headingDelta, interval
        )

        assertEquals(
            "on a straight drive the gate must not change the correction",
            baseline.correction, boosted.correction, 1e-9
        )
        assertEquals(
            "gate must not report any engagements on a straight drive",
            0, boosted.engagements
        )
    }

    /**
     * The critical guard: the gate must not damage a physically honest hard turn.
     * When the model reports the ground-truth lateral displacement for its yaw rate,
     * the boosted correction must stay small relative to the maxCorrectionMeters clamp,
     * because the model was already right.
     */
    @Test
    fun `gate does not damage an honest hard turn`() {
        val forward = 15.0
        val headingDelta = Math.toRadians(25.0)
        // For a constant yaw rate over the window, the honest lateral displacement is
        // forward * tan(headingDelta / 2). Feed exactly that.
        val honestLateral = forward * kotlin.math.tan(headingDelta / 2.0)

        val boosted = runOneWindow(
            NonHolonomicConfig(), TurningConservatismConfig.ENABLED,
            forward, honestLateral, headingDelta, interval = 1.0
        )

        assertTrue(
            "honest hard turn should not attract a large correction, got ${boosted.correction}",
            abs(boosted.correction) < 0.5
        )
    }

    private data class WindowOutcome(val correction: Double, val engagements: Int)

    private fun runOneWindow(
        nhc: NonHolonomicConfig,
        turning: TurningConservatismConfig,
        forward: Double,
        lateral: Double,
        headingDelta: Double,
        interval: Double
    ): WindowOutcome {
        val fusion = VehicleFusionEkf(
            headingPolicy = HeadingPolicy.MODEL_ONLY,
            nonHolonomic = nhc,
            turningConservatism = turning
        )
        fusion.reset(origin, forward, 0.0, 5.0)
        // Seed heading integrals from a constant yaw rate matching headingDelta over the
        // interval, so the ratio path in the NHC has data instead of hitting the closed
        // form.
        val yawRate = headingDelta / interval
        val samplesInWindow = (sampleRateHz * interval).toInt()
        repeat(samplesInWindow) { fusion.predictGyro(yawRate, dt) }
        fusion.predict(forward, lateral, headingDelta, interval)
        return WindowOutcome(
            correction = fusion.lastNonHolonomicCorrectionMeters,
            engagements = fusion.turningConservatismEngagements
        )
    }
}
