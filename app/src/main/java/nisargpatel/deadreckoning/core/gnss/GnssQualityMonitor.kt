package nisargpatel.deadreckoning.core.gnss

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * GNSS trustworthiness, decided from measurements rather than from whether a callback
 * happened to arrive.
 *
 * The previous logic marked GNSS available on any delivered location and never marked it
 * unavailable, so a fused network fix hundreds of metres off would keep dead reckoning
 * permanently suppressed.
 */
enum class GnssQuality {
    /** Fixes are accurate and self-consistent. Full weight in the estimator. */
    GOOD,

    /** Fixes are usable but degraded. Fused with inflated uncertainty. */
    DEGRADED,

    /** No trustworthy fix. Dead reckoning owns the solution. */
    DENIED,

    /** Fixes have returned but have not yet proven themselves. Fused cautiously. */
    RECOVERING
}

/**
 * One GNSS observation, normalised away from Android specifics so the core stays portable.
 *
 * Nullable fields represent genuinely unavailable information rather than zero, which is
 * what let the old code treat "no satellite data" as "no satellites".
 */
data class GnssFix(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Double,
    val speedMps: Double,
    val bearingDegrees: Double,
    /** Age of the fix at the moment it was handed over, milliseconds. */
    val ageMillis: Long,
    val speedAccuracyMps: Double? = null,
    val bearingAccuracyDegrees: Double? = null,
    val satelliteCount: Int? = null,
    val usedInFixSatelliteCount: Int? = null,
    val provider: String? = null,
    val isFromMockProvider: Boolean = false
)

data class GnssQualityConfig(
    /** Above this horizontal accuracy a fix is not trustworthy at all. */
    val deniedAccuracyMeters: Double = 50.0,
    /** Above this horizontal accuracy a fix is usable but downweighted. */
    val degradedAccuracyMeters: Double = 20.0,
    /** A fix older than this is stale. */
    val maxFixAgeMillis: Long = 3_000L,
    /** No usable fix for this long declares an outage. */
    val silenceTimeoutMillis: Long = 2_000L,
    /** Faster than this between consecutive fixes is a teleport, not motion. */
    val maxPlausibleSpeedMps: Double = 70.0,
    /** Harder than this between consecutive fixes is not a road vehicle. */
    val maxPlausibleAccelerationMps2: Double = 12.0,
    /** Fewer satellites used in the fix than this is a weak solution. */
    val minSatellitesUsedInFix: Int = 4,
    /** Consecutive clean fixes required to leave RECOVERING. */
    val fixesToConfirmRecovery: Int = 3,
    /** Consecutive rejects required to declare DENIED while currently trusted. */
    val rejectsToDeny: Int = 2,
    /** Bearing is meaningless below this speed, so bearing checks are skipped. */
    val minSpeedForBearingMps: Double = 1.5,
    /** Mock fixes are rejected unless a test explicitly permits them. */
    val allowMockProvider: Boolean = false
)

data class GnssAssessment(
    val quality: GnssQuality,
    /** Whether the estimator should consume this fix at all. */
    val usableForFusion: Boolean,
    /**
     * Accuracy the estimator should use, inflated for degraded and recovering fixes so a
     * doubtful fix cannot yank the solution. Null when there is nothing usable.
     */
    val effectiveAccuracyMeters: Double?,
    val reasons: List<String> = emptyList(),
    val outageDurationMillis: Long = 0L,
    val consecutiveAcceptedFixes: Int = 0,
    val consecutiveRejectedFixes: Int = 0,
    val totalRejectedFixes: Int = 0,
    val outageCount: Int = 0
) {
    val isOutage: Boolean get() = quality == GnssQuality.DENIED
}

/**
 * Hysteretic GNSS quality state machine.
 *
 * Time is passed in rather than read from a clock so the whole thing is deterministically
 * testable. Call [onFix] when a location arrives and [onSilence] periodically so an
 * outage is detected even when the platform simply stops delivering callbacks.
 */
class GnssQualityMonitor(private val config: GnssQualityConfig = GnssQualityConfig()) {

    private var quality = GnssQuality.DENIED
    private var lastUsableFixAtMillis: Long? = null
    private var lastAcceptedFix: GnssFix? = null
    private var lastAcceptedAtMillis: Long? = null
    private var consecutiveAccepted = 0
    private var consecutiveRejected = 0
    private var totalRejected = 0
    private var outageStartedAtMillis: Long? = null
    private var outageCount = 0
    private var lastAssessment = GnssAssessment(
        quality = GnssQuality.DENIED,
        usableForFusion = false,
        effectiveAccuracyMeters = null,
        reasons = listOf("no fix yet")
    )

    fun current(): GnssAssessment = lastAssessment

    fun reset() {
        quality = GnssQuality.DENIED
        lastUsableFixAtMillis = null
        lastAcceptedFix = null
        lastAcceptedAtMillis = null
        consecutiveAccepted = 0
        consecutiveRejected = 0
        totalRejected = 0
        outageStartedAtMillis = null
        outageCount = 0
        lastAssessment = GnssAssessment(GnssQuality.DENIED, false, null, listOf("reset"))
    }

    /**
     * @param insLatitude optional dead-reckoned latitude for a consistency cross-check.
     * @param insUncertaintyMeters how far the dead-reckoned solution could plausibly be off.
     */
    fun onFix(
        fix: GnssFix,
        nowMillis: Long,
        insLatitude: Double? = null,
        insLongitude: Double? = null,
        insUncertaintyMeters: Double? = null
    ): GnssAssessment {
        val hardFailures = mutableListOf<String>()
        val softFailures = mutableListOf<String>()

        if (fix.isFromMockProvider && !config.allowMockProvider) {
            hardFailures += "mock provider"
        }
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite()) {
            hardFailures += "non-finite position"
        }
        if (fix.latitude == 0.0 && fix.longitude == 0.0) {
            hardFailures += "null island position"
        }
        if (!fix.horizontalAccuracyMeters.isFinite() || fix.horizontalAccuracyMeters <= 0.0) {
            softFailures += "accuracy not reported"
        } else if (fix.horizontalAccuracyMeters > config.deniedAccuracyMeters) {
            hardFailures += "accuracy ${round(fix.horizontalAccuracyMeters)} m exceeds " +
                "${round(config.deniedAccuracyMeters)} m"
        } else if (fix.horizontalAccuracyMeters > config.degradedAccuracyMeters) {
            softFailures += "accuracy ${round(fix.horizontalAccuracyMeters)} m is degraded"
        }
        if (fix.ageMillis > config.maxFixAgeMillis) {
            hardFailures += "fix is ${fix.ageMillis} ms stale"
        }

        fix.usedInFixSatelliteCount?.let { used ->
            if (used < config.minSatellitesUsedInFix) {
                softFailures += "only $used satellites used in fix"
            }
        }
        fix.speedAccuracyMps?.let { speedAccuracy ->
            if (speedAccuracy > 3.0) softFailures += "speed accuracy ${round(speedAccuracy)} m/s"
        }
        if (fix.speedMps >= config.minSpeedForBearingMps) {
            fix.bearingAccuracyDegrees?.let { bearingAccuracy ->
                if (bearingAccuracy > 30.0) softFailures += "bearing accuracy ${round(bearingAccuracy)} deg"
            }
        }

        // Teleport and impossible-dynamics checks against the previous accepted fix.
        val previous = lastAcceptedFix
        val previousAt = lastAcceptedAtMillis
        if (previous != null && previousAt != null) {
            val elapsedSeconds = (nowMillis - previousAt) / 1_000.0
            if (elapsedSeconds > 0.05) {
                val jumpMeters = flatEarthDistanceMeters(
                    previous.latitude, previous.longitude, fix.latitude, fix.longitude
                )
                val impliedSpeed = jumpMeters / elapsedSeconds
                if (impliedSpeed > config.maxPlausibleSpeedMps) {
                    hardFailures += "implied ${round(impliedSpeed)} m/s jump over ${round(jumpMeters)} m"
                }
                val impliedAcceleration = abs(fix.speedMps - previous.speedMps) / elapsedSeconds
                if (impliedAcceleration > config.maxPlausibleAccelerationMps2) {
                    softFailures += "implied ${round(impliedAcceleration)} m/s2 acceleration"
                }
            }
        }

        // Consistency with the dead-reckoned solution, when one exists.
        if (insLatitude != null && insLongitude != null && insUncertaintyMeters != null) {
            val separation = flatEarthDistanceMeters(insLatitude, insLongitude, fix.latitude, fix.longitude)
            val allowed = 3.0 * (insUncertaintyMeters + fix.horizontalAccuracyMeters.coerceAtLeast(1.0))
            if (separation > allowed) {
                softFailures += "disagrees with dead reckoning by ${round(separation)} m"
            }
        }

        return if (hardFailures.isNotEmpty()) {
            registerRejection(nowMillis, hardFailures)
        } else {
            lastAcceptedFix = fix
            lastAcceptedAtMillis = nowMillis
            registerAcceptance(fix, nowMillis, softFailures)
        }
    }

    /** Call periodically. Detects an outage caused by the platform going quiet. */
    fun onSilence(nowMillis: Long): GnssAssessment {
        val lastUsable = lastUsableFixAtMillis
        val silentFor = if (lastUsable == null) Long.MAX_VALUE else nowMillis - lastUsable
        if (silentFor >= config.silenceTimeoutMillis && quality != GnssQuality.DENIED) {
            enterDenied(nowMillis)
            lastAssessment = assessment(
                reasons = listOf("no usable fix for $silentFor ms"),
                nowMillis = nowMillis
            )
        } else {
            lastAssessment = lastAssessment.copy(
                outageDurationMillis = outageDurationMillis(nowMillis)
            )
        }
        return lastAssessment
    }

    /** Immediately declare outage when platform reports GNSS disabled or unavailable. */
    fun onOutageDeclared(nowMillis: Long, reason: String = "GNSS disabled"): GnssAssessment {
        enterDenied(nowMillis)
        lastAssessment = assessment(
            reasons = listOf(reason),
            nowMillis = nowMillis
        )
        return lastAssessment
    }

    private fun registerAcceptance(fix: GnssFix, nowMillis: Long, softFailures: List<String>): GnssAssessment {
        consecutiveAccepted++
        consecutiveRejected = 0
        lastUsableFixAtMillis = nowMillis

        val degraded = softFailures.isNotEmpty()

        quality = when {
            // A first trustworthy fix after an outage has to earn full trust.
            quality == GnssQuality.DENIED -> {
                closeOutage()
                GnssQuality.RECOVERING
            }
            quality == GnssQuality.RECOVERING &&
                consecutiveAccepted >= config.fixesToConfirmRecovery &&
                !degraded -> GnssQuality.GOOD
            quality == GnssQuality.RECOVERING -> GnssQuality.RECOVERING
            degraded -> GnssQuality.DEGRADED
            else -> GnssQuality.GOOD
        }

        lastAssessment = assessment(reasons = softFailures, nowMillis = nowMillis, fix = fix)
        return lastAssessment
    }

    private fun registerRejection(nowMillis: Long, hardFailures: List<String>): GnssAssessment {
        consecutiveRejected++
        totalRejected++
        consecutiveAccepted = 0

        if (quality != GnssQuality.DENIED && consecutiveRejected >= config.rejectsToDeny) {
            enterDenied(nowMillis)
        }

        lastAssessment = assessment(reasons = hardFailures, nowMillis = nowMillis)
        return lastAssessment
    }

    private fun enterDenied(nowMillis: Long) {
        if (quality != GnssQuality.DENIED) {
            outageStartedAtMillis = nowMillis
            outageCount++
        }
        quality = GnssQuality.DENIED
        consecutiveAccepted = 0
    }

    private fun closeOutage() {
        outageStartedAtMillis = null
    }

    private fun outageDurationMillis(nowMillis: Long): Long =
        outageStartedAtMillis?.let { nowMillis - it } ?: 0L

    private fun assessment(reasons: List<String>, nowMillis: Long, fix: GnssFix? = null): GnssAssessment {
        val usable = quality != GnssQuality.DENIED
        val baseAccuracy = fix?.horizontalAccuracyMeters ?: lastAcceptedFix?.horizontalAccuracyMeters
        val effective = if (!usable || baseAccuracy == null) null else {
            when (quality) {
                GnssQuality.GOOD -> baseAccuracy
                // Inflate so a doubtful fix contributes without dominating.
                GnssQuality.DEGRADED -> baseAccuracy * 2.0
                GnssQuality.RECOVERING -> baseAccuracy * 3.0
                GnssQuality.DENIED -> null
            }
        }
        return GnssAssessment(
            quality = quality,
            usableForFusion = usable,
            effectiveAccuracyMeters = effective,
            reasons = reasons,
            outageDurationMillis = outageDurationMillis(nowMillis),
            consecutiveAcceptedFixes = consecutiveAccepted,
            consecutiveRejectedFixes = consecutiveRejected,
            totalRejectedFixes = totalRejected,
            outageCount = outageCount
        )
    }

    private fun round(value: Double) = String.format("%.1f", value)

    private companion object {
        fun flatEarthDistanceMeters(
            fromLatitude: Double,
            fromLongitude: Double,
            toLatitude: Double,
            toLongitude: Double
        ): Double {
            val north = (toLatitude - fromLatitude) * 111_111.0
            val east = (toLongitude - fromLongitude) * 111_111.0 * cos(Math.toRadians(fromLatitude))
            return sqrt(north * north + east * east)
        }
    }
}
