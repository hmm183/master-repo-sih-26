package nisargpatel.deadreckoning

import nisargpatel.deadreckoning.core.gnss.GnssFix
import nisargpatel.deadreckoning.core.gnss.GnssQuality
import nisargpatel.deadreckoning.core.gnss.GnssQualityConfig
import nisargpatel.deadreckoning.core.gnss.GnssQualityMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4 verification.
 *
 * The behaviour that matters is that trust follows the measurements. A delivered callback
 * is not evidence of a good fix, and a good fix arriving after an outage is not immediately
 * evidence that the outage is over.
 */
class GnssQualityMonitorTest {

    private val config = GnssQualityConfig()

    private fun goodFix(
        latitude: Double = 16.5,
        longitude: Double = 80.6,
        accuracy: Double = 5.0,
        speed: Double = 12.0,
        age: Long = 200L
    ) = GnssFix(
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = accuracy,
        speedMps = speed,
        bearingDegrees = 90.0,
        ageMillis = age,
        speedAccuracyMps = 0.5,
        bearingAccuracyDegrees = 5.0,
        satelliteCount = 18,
        usedInFixSatelliteCount = 11,
        provider = "gps",
        isFromMockProvider = false
    )

    private val fixIntervalMillis = 1_000L

    /**
     * Drives enough clean fixes to reach GOOD from a cold start.
     *
     * @return the next free timestamp, which is one interval *after* the final warm-up
     *   fix. Silence tests must measure from the final fix, so they subtract one interval.
     */
    private fun GnssQualityMonitor.warmUp(startMillis: Long = 1_000L): Long {
        var now = startMillis
        repeat(config.fixesToConfirmRecovery + 1) {
            onFix(goodFix(), now)
            now += fixIntervalMillis
        }
        return now
    }

    @Test
    fun `cold start is denied until a fix proves itself`() {
        val monitor = GnssQualityMonitor(config)

        assertEquals(GnssQuality.DENIED, monitor.current().quality)
        assertFalse(monitor.current().usableForFusion)
        assertNull(monitor.current().effectiveAccuracyMeters)
    }

    @Test
    fun `first good fix after denial is recovering not good`() {
        val monitor = GnssQualityMonitor(config)

        val assessment = monitor.onFix(goodFix(), 1_000L)

        assertEquals(GnssQuality.RECOVERING, assessment.quality)
        assertTrue("recovering fixes are still usable", assessment.usableForFusion)
    }

    @Test
    fun `recovering promotes to good after enough clean fixes`() {
        val monitor = GnssQualityMonitor(config)
        monitor.warmUp()

        assertEquals(GnssQuality.GOOD, monitor.current().quality)
        assertTrue(monitor.current().usableForFusion)
    }

    @Test
    fun `recovering inflates accuracy more than good does`() {
        val monitor = GnssQualityMonitor(config)

        val recovering = monitor.onFix(goodFix(accuracy = 6.0), 1_000L)
        assertEquals(GnssQuality.RECOVERING, recovering.quality)
        val recoveringAccuracy = requireNotNull(recovering.effectiveAccuracyMeters)

        monitor.warmUp(startMillis = 2_000L)
        val good = monitor.current()
        assertEquals(GnssQuality.GOOD, good.quality)
        val goodAccuracy = requireNotNull(good.effectiveAccuracyMeters)

        assertTrue(
            "recovering $recoveringAccuracy should be inflated above good $goodAccuracy",
            recoveringAccuracy > goodAccuracy
        )
    }

    @Test
    fun `inaccurate fix is denied rather than trusted`() {
        val monitor = GnssQualityMonitor(config)
        var now = monitor.warmUp()

        // A 120 m fused fix used to be treated as "available".
        repeat(config.rejectsToDeny) {
            monitor.onFix(goodFix(accuracy = 120.0), now)
            now += 1_000L
        }

        assertEquals(GnssQuality.DENIED, monitor.current().quality)
        assertFalse(monitor.current().usableForFusion)
        assertTrue(monitor.current().reasons.any { it.contains("accuracy") })
    }

    @Test
    fun `moderately inaccurate fix is degraded but still usable`() {
        val monitor = GnssQualityMonitor(config)
        val now = monitor.warmUp()

        val assessment = monitor.onFix(goodFix(accuracy = 30.0), now)

        assertEquals(GnssQuality.DEGRADED, assessment.quality)
        assertTrue(assessment.usableForFusion)
        assertNotNull(assessment.effectiveAccuracyMeters)
    }

    @Test
    fun `stale fix is rejected`() {
        val monitor = GnssQualityMonitor(config)
        var now = monitor.warmUp()

        repeat(config.rejectsToDeny) {
            monitor.onFix(goodFix(age = 9_000L), now)
            now += 1_000L
        }

        assertEquals(GnssQuality.DENIED, monitor.current().quality)
        assertTrue(monitor.current().reasons.any { it.contains("stale") })
    }

    @Test
    fun `mock provider is rejected`() {
        val monitor = GnssQualityMonitor(config)
        var now = monitor.warmUp()

        repeat(config.rejectsToDeny) {
            monitor.onFix(goodFix().copy(isFromMockProvider = true), now)
            now += 1_000L
        }

        assertEquals(GnssQuality.DENIED, monitor.current().quality)
        assertTrue(monitor.current().reasons.any { it.contains("mock") })
    }

    @Test
    fun `null island position is rejected`() {
        val monitor = GnssQualityMonitor(config)
        val assessment = monitor.onFix(goodFix(latitude = 0.0, longitude = 0.0), 1_000L)

        assertTrue(assessment.reasons.any { it.contains("null island") })
        assertEquals(0, assessment.consecutiveAcceptedFixes)
    }

    @Test
    fun `teleport between consecutive fixes is rejected`() {
        val monitor = GnssQualityMonitor(config)
        var now = monitor.warmUp()

        // Roughly 11 km away one second later.
        repeat(config.rejectsToDeny) {
            monitor.onFix(goodFix(latitude = 16.6), now)
            now += 1_000L
        }

        assertEquals(GnssQuality.DENIED, monitor.current().quality)
        assertTrue(monitor.current().reasons.any { it.contains("jump") })
    }

    @Test
    fun `impossible acceleration degrades the fix`() {
        val monitor = GnssQualityMonitor(config)
        val now = monitor.warmUp()

        // 12 m/s to 60 m/s in one second is not a road vehicle.
        val assessment = monitor.onFix(goodFix(speed = 60.0), now)

        assertTrue(assessment.reasons.any { it.contains("acceleration") })
        assertEquals(GnssQuality.DEGRADED, assessment.quality)
    }

    @Test
    fun `weak satellite count degrades the fix`() {
        val monitor = GnssQualityMonitor(config)
        val now = monitor.warmUp()

        val assessment = monitor.onFix(goodFix().copy(usedInFixSatelliteCount = 2), now)

        assertEquals(GnssQuality.DEGRADED, assessment.quality)
        assertTrue(assessment.reasons.any { it.contains("satellites") })
    }

    @Test
    fun `unknown satellite count is not treated as zero satellites`() {
        val monitor = GnssQualityMonitor(config)
        val now = monitor.warmUp()

        val assessment = monitor.onFix(
            goodFix().copy(satelliteCount = null, usedInFixSatelliteCount = null),
            now
        )

        assertEquals("missing satellite data must not degrade a good fix", GnssQuality.GOOD, assessment.quality)
    }

    @Test
    fun `silence declares an outage even with no rejected fixes`() {
        val monitor = GnssQualityMonitor(config)
        val lastFixAt = monitor.warmUp() - fixIntervalMillis

        // The platform simply stops delivering. The old code never noticed this.
        val assessment = monitor.onSilence(lastFixAt + config.silenceTimeoutMillis + 1)

        assertEquals(GnssQuality.DENIED, assessment.quality)
        assertFalse(assessment.usableForFusion)
        assertTrue(assessment.reasons.any { it.contains("no usable fix") })
    }

    @Test
    fun `silence before the timeout does not disturb a good state`() {
        val monitor = GnssQualityMonitor(config)
        val lastFixAt = monitor.warmUp() - fixIntervalMillis

        val assessment = monitor.onSilence(lastFixAt + config.silenceTimeoutMillis - 500)

        assertEquals(GnssQuality.GOOD, assessment.quality)
        assertTrue(assessment.usableForFusion)
    }

    @Test
    fun `hysteresis prevents flapping on a single bad fix`() {
        val monitor = GnssQualityMonitor(config)
        val now = monitor.warmUp()

        // One rejected fix, with rejectsToDeny = 2, must not tip the state.
        monitor.onFix(goodFix(accuracy = 500.0), now)

        assertTrue(
            "a single bad fix should not deny GNSS, got ${monitor.current().quality}",
            monitor.current().quality != GnssQuality.DENIED
        )

        monitor.onFix(goodFix(accuracy = 500.0), now + 1_000L)
        assertEquals(GnssQuality.DENIED, monitor.current().quality)
    }

    @Test
    fun `outage duration and count are tracked`() {
        val monitor = GnssQualityMonitor(config)
        var now = monitor.warmUp() - fixIntervalMillis

        val outageStart = now + config.silenceTimeoutMillis + 1
        monitor.onSilence(outageStart)
        assertEquals(1, monitor.current().outageCount)

        val during = monitor.onSilence(outageStart + 5_000L)
        assertTrue("outage should be at least 5 s, was ${during.outageDurationMillis}", during.outageDurationMillis >= 5_000L)

        // Recovery clears the running outage.
        now = outageStart + 6_000L
        val recovered = monitor.onFix(goodFix(), now)
        assertEquals(GnssQuality.RECOVERING, recovered.quality)
        assertEquals(0L, recovered.outageDurationMillis)
    }

    @Test
    fun `disagreement with dead reckoning degrades the fix`() {
        val monitor = GnssQualityMonitor(config)
        val now = monitor.warmUp()

        // Dead reckoning believes we are 400 m away and is fairly confident.
        val assessment = monitor.onFix(
            fix = goodFix(),
            nowMillis = now,
            insLatitude = 16.5036,
            insLongitude = 80.6,
            insUncertaintyMeters = 10.0
        )

        assertTrue(assessment.reasons.any { it.contains("dead reckoning") })
        assertEquals(GnssQuality.DEGRADED, assessment.quality)
    }

    @Test
    fun `agreement with dead reckoning keeps the fix good`() {
        val monitor = GnssQualityMonitor(config)
        val now = monitor.warmUp()

        val assessment = monitor.onFix(
            fix = goodFix(),
            nowMillis = now,
            insLatitude = 16.50002,
            insLongitude = 80.60002,
            insUncertaintyMeters = 10.0
        )

        assertEquals(GnssQuality.GOOD, assessment.quality)
    }

    @Test
    fun `reset returns the monitor to a cold denied state`() {
        val monitor = GnssQualityMonitor(config)
        monitor.warmUp()
        assertEquals(GnssQuality.GOOD, monitor.current().quality)

        monitor.reset()

        assertEquals(GnssQuality.DENIED, monitor.current().quality)
        assertEquals(0, monitor.current().outageCount)
        assertEquals(0, monitor.current().totalRejectedFixes)
    }

    @Test
    fun `full outage and recovery cycle`() {
        val monitor = GnssQualityMonitor(config)
        var now = monitor.warmUp() - fixIntervalMillis
        assertEquals(GnssQuality.GOOD, monitor.current().quality)

        // Signal lost.
        now += config.silenceTimeoutMillis + 1
        assertEquals(GnssQuality.DENIED, monitor.onSilence(now).quality)

        // Signal returns and must earn trust back.
        now += 1_000L
        assertEquals(GnssQuality.RECOVERING, monitor.onFix(goodFix(), now).quality)
        now += 1_000L
        assertEquals(GnssQuality.RECOVERING, monitor.onFix(goodFix(), now).quality)
        now += 1_000L
        assertEquals(GnssQuality.GOOD, monitor.onFix(goodFix(), now).quality)

        assertEquals(1, monitor.current().outageCount)
    }

    @Test
    fun `onOutageDeclared immediately denies quality and clears usability`() {
        val monitor = GnssQualityMonitor(config)
        val now = monitor.warmUp()
        assertEquals(GnssQuality.GOOD, monitor.current().quality)
        assertTrue(monitor.current().usableForFusion)

        val outage = monitor.onOutageDeclared(now + 50L, "Platform GNSS unavailable")
        assertEquals(GnssQuality.DENIED, outage.quality)
        assertFalse(outage.usableForFusion)
        assertTrue(outage.reasons.any { it.contains("Platform GNSS unavailable") })
        assertEquals(1, monitor.current().outageCount)
    }
}
