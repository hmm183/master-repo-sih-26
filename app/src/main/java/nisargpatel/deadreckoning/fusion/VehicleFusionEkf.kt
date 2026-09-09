package nisargpatel.deadreckoning.fusion

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.osmdroid.util.GeoPoint

/**
 * Fused vehicle state with genuine uncertainty.
 *
 * Uncertainty is reported per axis rather than as one number because during a GNSS outage
 * the error is strongly anisotropic: heading error times distance travelled makes
 * cross-track uncertainty grow far faster than along-track uncertainty. Collapsing that
 * into a single radius throws away exactly the information the map constraint needs.
 *
 * @param horizontalUncertaintyMeters 1-sigma along the worst-case direction, the major
 *   axis of the position covariance.
 */
data class FusedVehicleState(
    val position: GeoPoint,
    val speedMps: Double,
    val headingDegrees: Double,
    val horizontalUncertaintyMeters: Double,
    val alongTrackUncertaintyMeters: Double = 0.0,
    val crossTrackUncertaintyMeters: Double = 0.0,
    val speedUncertaintyMps: Double = 0.0,
    val headingUncertaintyDegrees: Double = 0.0
)

/**
 * How vehicle heading is propagated during a GNSS outage.
 *
 * Rotational information must enter the state exactly once. The previous
 * implementation integrated gyro yaw at IMU rate *and* separately added the model's
 * whole-window heading change as an independent rotation, so every turn was counted
 * roughly twice.
 */
enum class HeadingPolicy {
    /** Gyro integration is the only source. The model's heading output is monitored, not applied. */
    GYRO_ONLY,

    /** The model's per-window heading change is the only source. Gyro integration is discarded. */
    MODEL_ONLY,

    /**
     * Gyro integration propagates heading; the model's per-window heading change is
     * treated as a *measurement* of that same window's rotation and fused through a
     * gain. This is the default: physics propagates, the network corrects.
     */
    GYRO_WITH_MODEL_UPDATE
}

/**
 * Non-holonomic constraint settings for a road vehicle.
 *
 * A car cannot translate sideways: its velocity vector stays along its heading apart
 * from a small sideslip. Note what this does *not* mean. Over a 1.9 s window a turning
 * vehicle legitimately accumulates metres of lateral displacement measured in its
 * start-of-window frame, because the heading rotated during the window. Forcing that
 * lateral component to zero would destroy every turn.
 *
 * The constraint is therefore applied to sideslip: given how the heading actually
 * rotated during the window, the lateral displacement implied by pure forward motion is
 * computed, and only the residual beyond that is suppressed.
 *
 * @param lateralGain how strongly to pull the reported lateral displacement toward the
 *   constraint-implied value. 1.0 would assert zero sideslip with perfect certainty,
 *   which is not physical, so the default leaves headroom.
 * @param maxCorrectionMeters corrections larger than this indicate a broken model
 *   output rather than sideslip, so they are clamped instead of trusted wholesale.
 * @param minForwardIntegralSeconds below this the window carries too little forward
 *   motion for the constraint to be observable, for example when stationary.
 */
data class NonHolonomicConfig(
    val enabled: Boolean = true,
    val lateralGain: Double = 0.7,
    val maxCorrectionMeters: Double = 6.0,
    val minForwardIntegralSeconds: Double = 0.2
) {
    companion object {
        val DISABLED = NonHolonomicConfig(enabled = false)
    }
}

/**
 * Settings for folding a map match back into the estimator.
 *
 * @param crossTrackUncertaintyMeters how precisely the road centreline locates the
 *   vehicle perpendicular to the road. Lane width and geometry error dominate.
 * @param alongTrackUncertaintyMeters deliberately enormous. Snapping to the nearest point
 *   on a road says almost nothing about how far along that road the vehicle is, so the
 *   along-track channel must carry effectively no information. Treating a map match as an
 *   isotropic position fix would inject fabricated along-track knowledge.
 * @param minConfidence matches below this confidence are not fed back.
 * @param gateSigma cross-track innovations beyond this many sigma are treated as a wrong
 *   road rather than as a position error.
 */
data class MapConstraintConfig(
    val enabled: Boolean = true,
    val crossTrackUncertaintyMeters: Double = 4.0,
    val alongTrackUncertaintyMeters: Double = 10_000.0,
    val minConfidence: Int = 45,
    val gateSigma: Double = 3.0
) {
    companion object {
        val DISABLED = MapConstraintConfig(enabled = false)
    }
}

/**
 * Turning-scenario conservatism. When the model reports a hard turn, lean harder on
 * physics (the non-holonomic constraint already in place) and less on the model's own
 * heading output.
 *
 * ## Why this exists
 *
 * The offline PINO-DR v3 benchmark ranks configurations very differently by scenario.
 * Motorway drift lands near 3 percent of distance; sharp turns and roundabouts land at
 * 76-81 percent, and those turns are the majority of the held-out sequences AND the
 * geometry where GNSS actually drops (tunnels, multi-storey car parks). The trained
 * model shares a pooled context vector between its velocity and orientation heads with
 * no explicit tie-in between them, so on hard turns the heading can drift arbitrarily
 * far from what the physical constraint allows.
 *
 * A retrain that adds a yaw residual head or shorter bins is the correct long-term
 * fix. Until then, the filter can be told to distrust the model more when the model
 * itself reports a hard turn:
 *
 *  - The non-holonomic constraint's lateral gain ramps up from
 *    [NonHolonomicConfig.lateralGain] toward [boostedLateralGain] as the observed yaw
 *    rate climbs past [highYawRateRadPerSec], because a hard-turning car really does
 *    stay along its own heading and a large sideslip almost certainly means the model
 *    is wrong rather than the car is drifting.
 *  - The [HeadingPolicy.GYRO_WITH_MODEL_UPDATE] measurement gain scales down by
 *    [headingMeasurementDampen] over the same range, so the model gets less say in
 *    heading when it is most likely to be wrong about it.
 *
 * Off by default so existing tests and callers see no change. Enable it explicitly
 * from the PINO consumer path, where the failure mode was measured.
 *
 * @param highYawRateRadPerSec yaw rate above which the gate is fully engaged. 0.35 is
 *   roughly a 20 deg/s turn, which corresponds to a fairly tight roundabout entry.
 *   Below [lowYawRateRadPerSec] the gate is off; between the two the effect ramps
 *   linearly so a moderately turning drive does not chatter across the boundary.
 * @param lowYawRateRadPerSec yaw rate below which the gate is a no-op.
 * @param boostedLateralGain lateral gain the non-holonomic constraint uses when the
 *   gate is fully engaged. 0.95 is a strong pull that still leaves headroom for a
 *   physically real sideslip in extreme weather.
 * @param headingMeasurementDampen factor applied to the heading measurement gain when
 *   the gate is fully engaged, so 0.5 means the model's heading correction is halved.
 */
data class TurningConservatismConfig(
    val enabled: Boolean = false,
    val highYawRateRadPerSec: Double = 0.35,
    val lowYawRateRadPerSec: Double = 0.10,
    val boostedLateralGain: Double = 0.95,
    val headingMeasurementDampen: Double = 0.5
) {
    init {
        require(lowYawRateRadPerSec < highYawRateRadPerSec) {
            "lowYawRateRadPerSec ($lowYawRateRadPerSec) must be strictly less than " +
                "highYawRateRadPerSec ($highYawRateRadPerSec)"
        }
        require(boostedLateralGain in 0.0..1.0) {
            "boostedLateralGain $boostedLateralGain must be in [0, 1]"
        }
        require(headingMeasurementDampen in 0.0..1.0) {
            "headingMeasurementDampen $headingMeasurementDampen must be in [0, 1]"
        }
    }

    /**
     * How engaged the gate is for an observed yaw rate, in `[0, 1]`. 0 means fully off,
     * 1 means fully engaged. Between the low and high thresholds the response is
     * linear so a slightly-turning stretch does not oscillate.
     */
    internal fun engagement(yawRateRadPerSec: Double): Double {
        if (!enabled) return 0.0
        val magnitude = kotlin.math.abs(yawRateRadPerSec)
        if (magnitude <= lowYawRateRadPerSec) return 0.0
        if (magnitude >= highYawRateRadPerSec) return 1.0
        val span = highYawRateRadPerSec - lowYawRateRadPerSec
        return ((magnitude - lowYawRateRadPerSec) / span).coerceIn(0.0, 1.0)
    }

    companion object {
        val DISABLED = TurningConservatismConfig(enabled = false)

        /** Reasonable defaults for consumers that want the gate on without picking numbers. */
        val ENABLED = TurningConservatismConfig(enabled = true)
    }
}

/** Outcome of a map-constraint update, for diagnostics and for deciding what to display. */
data class MapConstraintResult(
    val applied: Boolean,
    val state: FusedVehicleState,
    val crossTrackCorrectionMeters: Double,
    val rejectionReason: String? = null
)

/**
 * Error-state filter for the vehicle frame. The AI supplies the propagation delta and
 * GNSS supplies position, speed and heading measurements.
 *
 * ## Coordinate conventions
 *
 * Heading is radians clockwise from North, so 0 = North and pi/2 = East, matching the
 * IO-VNBD reference columns.
 *
 * Displacement arrives as `(forward, lateral)` in the vehicle frame **as it was at the
 * start of the window**, because that is how the training target is constructed in
 * `global_to_vehicle_frame(dEast, dNorth, heading[start])`. It is therefore rotated
 * by the start-of-window heading and only then is heading advanced. Rotating by the
 * end-of-window heading, as the previous implementation did, smears every turn.
 *
 * Yaw rate is expected in the vehicle FRD frame, where a positive rate about the Down
 * axis is exactly `dHeading/dt`. Feeding the raw Android `gyroZ` here is wrong: for a
 * flat phone that axis points *up*, so heading turned the wrong way.
 */
class VehicleFusionEkf(
    private val headingPolicy: HeadingPolicy = HeadingPolicy.GYRO_WITH_MODEL_UPDATE,
    private val nonHolonomic: NonHolonomicConfig = NonHolonomicConfig(),
    private val mapConstraint: MapConstraintConfig = MapConstraintConfig(),
    private val turningConservatism: TurningConservatismConfig = TurningConservatismConfig.DISABLED
) : VehicleEstimator {
    override val name: String = "EKF"
    private companion object {
        /** Weight applied to the model-versus-gyro heading disagreement. */
        const val HEADING_MEASUREMENT_GAIN = 0.35

        /** Disagreement beyond this is treated as a bad model output and rejected. */
        const val MAX_HEADING_INNOVATION_RADIANS = 0.6

        /** Displacement beyond this in a single window is physically implausible. */
        const val MAX_WINDOW_DISPLACEMENT_METERS = 120.0

        /** Along-track displacement error as a fraction of the distance travelled. */
        const val ALONG_TRACK_SCALE_ERROR = 0.05

        /** Along-track error floor per window, metres. */
        const val ALONG_TRACK_FLOOR_METERS = 1.0
    }

    private var reference: GeoPoint? = null
    private var eastMeters = 0.0
    private var northMeters = 0.0
    private var speedMps = 0.0
    private var headingRadians = 0.0

    /**
     * Position covariance in the local East/North frame, symmetric 2x2 stored as its
     * three distinct entries.
     *
     * This replaces the single scalar variance the filter used previously. A scalar cannot
     * express that dead-reckoning error is much larger across the road than along it,
     * which is precisely the structure the map constraint exploits.
     */
    private var pEastEast = 400.0
    private var pEastNorth = 0.0
    private var pNorthNorth = 400.0

    private var speedVariance = 25.0
    private var headingVariance = Math.toRadians(35.0).let { it * it }

    /** Heading as it was when the window currently being accumulated began. */
    private var headingAtWindowStartRadians = 0.0

    /** Gyro-integrated rotation accumulated since the current window began. */
    private var gyroIntegratedThisWindowRadians = 0.0

    /**
     * Shape integrals of the heading trajectory since the window began, in seconds:
     * `integral cos(psi_rel) dt` and `integral sin(psi_rel) dt`, where `psi_rel` is
     * heading relative to the window start.
     *
     * Under the no-sideslip constraint the window displacement is
     * `(v * cosIntegral, v * sinIntegral)` for forward speed `v`, so their ratio
     * predicts lateral displacement from forward displacement without needing to know
     * `v` at all.
     */
    private var headingCosIntegralSeconds = 0.0
    private var headingSinIntegralSeconds = 0.0

    /** Last lateral correction the constraint applied, metres. Positive means pulled right. */
    var lastNonHolonomicCorrectionMeters = 0.0
        private set

    /** Number of windows where the constraint was actually applied. */
    var nonHolonomicUpdates = 0
        private set

    /**
     * Number of windows where the turning-conservatism gate contributed any boost, i.e.
     * where the observed yaw rate exceeded [TurningConservatismConfig.lowYawRateRadPerSec].
     * Exposed so diagnostic prints can distinguish "gate is off" from "gate is on but
     * never engages" from "gate engages every window", each of which points at a
     * different tuning problem.
     */
    var turningConservatismEngagements = 0
        private set

    /** Last cross-track correction the map constraint applied, metres. */
    var lastMapCrossTrackCorrectionMeters = 0.0
        private set

    /** Map constraint updates folded into the state. */
    var mapConstraintUpdates = 0
        private set

    /** Map matches rejected by the confidence or innovation gate. */
    var rejectedMapConstraints = 0
        private set

    /**
     * Last model-versus-gyro heading disagreement, radians. Exposed for diagnostics so
     * a drifting or mis-signed rotational channel is visible instead of silent.
     */
    var lastHeadingInnovationRadians = 0.0
        private set

    /** Count of model heading updates rejected by the innovation gate. */
    var rejectedHeadingUpdates = 0
        private set

    /** Tracked duration of consecutive dead-reckoning predictions since last GNSS fix. */
    var outageSeconds: Double = 0.0
        private set

    override fun isInitialized() = reference != null

    override fun reset(position: GeoPoint, speedMps: Double, headingDegrees: Double, accuracyMeters: Double) {
        reference = position
        eastMeters = 0.0
        northMeters = 0.0
        this.speedMps = speedMps.coerceAtLeast(0.0)
        headingRadians = Math.toRadians(headingDegrees)
        val initialVariance = accuracyMeters.coerceAtLeast(3.0).let { it * it }
        pEastEast = initialVariance
        pNorthNorth = initialVariance
        pEastNorth = 0.0
        speedVariance = 4.0
        headingVariance = Math.toRadians(15.0).let { it * it }
        outageSeconds = 0.0
        anchorWindow()
    }

    /**
     * Consume one window of AI displacement.
     *
     * @param forwardMeters longitudinal displacement over the window, start-of-window frame.
     * @param lateralMeters lateral displacement over the window, start-of-window frame.
     * @param headingDeltaRadians the window's total heading change as predicted by the model.
     * @param intervalSeconds the time the window actually spans, used to derive speed.
     */
    override fun predict(
        forwardMeters: Double,
        lateralMeters: Double,
        headingDeltaRadians: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null) return null
        if (!forwardMeters.isFinite() || !lateralMeters.isFinite() || !headingDeltaRadians.isFinite()) return null
        if (abs(forwardMeters) > MAX_WINDOW_DISPLACEMENT_METERS ||
            abs(lateralMeters) > MAX_WINDOW_DISPLACEMENT_METERS
        ) {
            return state()
        }

        outageSeconds += intervalSeconds.coerceAtLeast(0.0)

        // Displacement is expressed in the frame at the START of the window.
        val rotationHeading = headingAtWindowStartRadians

        // How aggressively the turning gate should engage this window. The model's own
        // reported yaw rate is what triggers it, because the gate is a hedge against the
        // model being wrong precisely when it says the vehicle is turning hard.
        val safeInterval = intervalSeconds.coerceAtLeast(1e-3)
        val observedYawRate = headingDeltaRadians / safeInterval
        val turningEngagement = turningConservatism.engagement(observedYawRate)

        val constrainedLateral = applyNonHolonomicConstraint(
            forwardMeters = forwardMeters,
            lateralMeters = lateralMeters,
            headingDeltaRadians = headingDeltaRadians,
            turningEngagement = turningEngagement
        )

        resolveHeading(headingDeltaRadians, turningEngagement)

        // Stationary sanity floor: clamp sub-centimeter open-loop sensor jitter when stopped
        val isStationaryFloor = this.speedMps < 0.05 && abs(forwardMeters) < 0.04
        val effectiveForward = if (isStationaryFloor) 0.0 else forwardMeters
        val effectiveLateral = if (isStationaryFloor) 0.0 else constrainedLateral

        val north = effectiveForward * cos(rotationHeading) - effectiveLateral * sin(rotationHeading)
        val east = effectiveForward * sin(rotationHeading) + effectiveLateral * cos(rotationHeading)
        northMeters += north
        eastMeters += east

        speedMps = if (isStationaryFloor) 0.0 else (effectiveForward / intervalSeconds.coerceAtLeast(0.1)).coerceAtLeast(0.0)

        addProcessNoise(
            forwardMeters = effectiveForward,
            constrainedLateralMeters = effectiveLateral,
            rotationHeadingRadians = rotationHeading
        )
        speedVariance += if (isStationaryFloor) 0.05 else 0.8
        headingVariance += Math.toRadians(if (isStationaryFloor) 0.2 else 2.0).let { it * it }

        anchorWindow()
        return state()
    }

    /**
     * Grow the position covariance for one propagated window.
     *
     * The noise is built in the vehicle frame and then rotated into East/North, because
     * the two axes are not comparable. Along-track error scales with the distance
     * travelled, whereas cross-track error is dominated by heading uncertainty multiplied
     * by that same distance, which is why cross-track uncertainty runs away during a long
     * outage. A single scalar variance cannot represent that at all.
     */
    private fun addProcessNoise(
        forwardMeters: Double,
        constrainedLateralMeters: Double,
        rotationHeadingRadians: Double
    ) {
        val distance = abs(forwardMeters)
        val headingSigma = sqrt(headingVariance)

        val alongSigma = ALONG_TRACK_SCALE_ERROR * distance + ALONG_TRACK_FLOOR_METERS
        val qAlong = alongSigma * alongSigma

        // Heading error swings the travelled distance sideways.
        val headingLever = distance * headingSigma
        val lateralDoubt = 0.5 * abs(constrainedLateralMeters)
        val constraintDoubt = abs(lastNonHolonomicCorrectionMeters)
        val crossSigma = sqrt(headingLever * headingLever + lateralDoubt * lateralDoubt) + constraintDoubt
        val qCross = crossSigma * crossSigma

        val sinHeading = sin(rotationHeadingRadians)
        val cosHeading = cos(rotationHeadingRadians)

        // Rotate diag(qAlong, qCross) from the vehicle frame into East/North.
        pEastEast += qAlong * sinHeading * sinHeading + qCross * cosHeading * cosHeading
        pNorthNorth += qAlong * cosHeading * cosHeading + qCross * sinHeading * sinHeading
        pEastNorth += (qAlong - qCross) * sinHeading * cosHeading
    }

    /**
     * Suppress sideslip in the reported lateral displacement.
     *
     * The lateral displacement a non-holonomic vehicle must show, given how its heading
     * rotated during the window, is `forward * (sinIntegral / cosIntegral)`. The forward
     * speed cancels in the ratio, so no speed estimate is required.
     *
     * When gyro samples are unavailable the heading is assumed to rotate at a constant
     * rate across the window, which reduces to the exact closed form
     * `forward * tan(headingDelta / 2)`.
     *
     * Vertical velocity needs no explicit constraint here: this filter carries no
     * vertical state, so it is structurally zero.
     */
    private fun applyNonHolonomicConstraint(
        forwardMeters: Double,
        lateralMeters: Double,
        headingDeltaRadians: Double,
        turningEngagement: Double = 0.0
    ): Double {
        lastNonHolonomicCorrectionMeters = 0.0
        if (!nonHolonomic.enabled) return lateralMeters

        val ratio = lateralToForwardRatio(headingDeltaRadians) ?: return lateralMeters
        val impliedLateral = forwardMeters * ratio

        val sideslip = lateralMeters - impliedLateral
        // Ramp the gain from the baseline toward the boosted value as the turning gate
        // engages. When turningEngagement is 0 this is exactly nonHolonomic.lateralGain,
        // so the disabled-by-default case does not change any existing behaviour.
        val engagement = turningEngagement.coerceIn(0.0, 1.0)
        val effectiveGain = if (engagement <= 0.0) {
            nonHolonomic.lateralGain
        } else {
            val boosted = turningConservatism.boostedLateralGain
                .coerceAtLeast(nonHolonomic.lateralGain)
            nonHolonomic.lateralGain + engagement * (boosted - nonHolonomic.lateralGain)
        }
        if (engagement > 0.0) turningConservatismEngagements++

        val correction = (-sideslip * effectiveGain)
            .coerceIn(-nonHolonomic.maxCorrectionMeters, nonHolonomic.maxCorrectionMeters)

        lastNonHolonomicCorrectionMeters = correction
        nonHolonomicUpdates++
        return lateralMeters + correction
    }

    /**
     * `sinIntegral / cosIntegral` for the window, or null when the window carries too
     * little forward motion for the constraint to be observable.
     */
    private fun lateralToForwardRatio(headingDeltaRadians: Double): Double? {
        if (headingCosIntegralSeconds >= nonHolonomic.minForwardIntegralSeconds) {
            return headingSinIntegralSeconds / headingCosIntegralSeconds
        }
        // No usable gyro history. Fall back to a constant yaw rate across the window.
        val half = headingDeltaRadians / 2.0
        if (abs(half) >= Math.PI / 2 - 1e-6) return null
        return kotlin.math.tan(half)
    }

    /**
     * Applies the heading policy for one completed window. Exactly one rotational
     * source reaches [headingRadians].
     */
    private fun resolveHeading(
        modelHeadingDeltaRadians: Double,
        turningEngagement: Double = 0.0
    ) {
        lastHeadingInnovationRadians = modelHeadingDeltaRadians - gyroIntegratedThisWindowRadians

        when (headingPolicy) {
            // Gyro already advanced heading during the window. Nothing more to add.
            HeadingPolicy.GYRO_ONLY -> Unit

            // Discard the gyro contribution and use the model's change instead.
            HeadingPolicy.MODEL_ONLY -> {
                headingRadians = normalizeRadians(headingAtWindowStartRadians + modelHeadingDeltaRadians)
            }

            HeadingPolicy.GYRO_WITH_MODEL_UPDATE -> {
                // Outage-duration aware innovation gating:
                // When an outage extends or gyro alignment is imperfect, uncorrected gyro drift
                // accumulates. A hard gate that unconditionally rejects model innovation causes heading to drift away indefinitely.
                val dynamicGate = (MAX_HEADING_INNOVATION_RADIANS * (1.0 + 0.05 * outageSeconds)).coerceAtMost(1.8)
                if (abs(lastHeadingInnovationRadians) > dynamicGate && outageSeconds < 5.0) {
                    rejectedHeadingUpdates++
                } else {
                    val engagement = turningEngagement.coerceIn(0.0, 1.0)
                    val gainScale = 1.0 - engagement * (1.0 - turningConservatism.headingMeasurementDampen)
                    // Scale model update weight with outage duration to pull gyro back from drift
                    val outageBoost = (1.0 + (outageSeconds / 20.0)).coerceAtMost(2.5)
                    val effectiveGain = (HEADING_MEASUREMENT_GAIN * gainScale * outageBoost).coerceAtMost(0.6)
                    val boundedInnovation = lastHeadingInnovationRadians.coerceIn(-dynamicGate, dynamicGate)
                    headingRadians = normalizeRadians(
                        headingRadians + effectiveGain * boundedInnovation
                    )
                    headingVariance *= 1.0 - effectiveGain * 0.5
                }
            }
        }
    }

    /**
     * Propagate using a velocity estimate over a short interval.
     *
     * This is the high-rate counterpart to [predict] and the correct way to drive a 10 Hz
     * output. Window displacement cannot be used for that: a 1.9 s displacement arriving
     * every 0.2 s would count the same motion roughly ten times over. A velocity multiplied
     * by the elapsed interval integrates cleanly at any rate.
     *
     * Heading is propagated by gyro through [predictGyro], so this method does not touch
     * heading and cannot double-count rotation.
     *
     * @param forwardMps forward speed in the vehicle frame.
     * @param lateralMps lateral speed. Physically near zero for a road vehicle; the
     *   non-holonomic assumption is applied by simply trusting that rather than integrating
     *   an unconstrained sideways velocity.
     * @param intervalSeconds elapsed time since the previous velocity propagation.
     */
    override fun predictVelocity(
        forwardMps: Double,
        lateralMps: Double,
        intervalSeconds: Double
    ): FusedVehicleState? {
        if (reference == null) return null
        if (intervalSeconds <= 0.0 || intervalSeconds > 1.0) return null
        if (!forwardMps.isFinite() || !lateralMps.isFinite()) return null

        val effectiveLateral = if (nonHolonomic.enabled) {
            lateralMps * (1.0 - nonHolonomic.lateralGain)
        } else {
            lateralMps
        }

        val forwardMeters = forwardMps * intervalSeconds
        val lateralMeters = effectiveLateral * intervalSeconds

        val north = forwardMeters * cos(headingRadians) - lateralMeters * sin(headingRadians)
        val east = forwardMeters * sin(headingRadians) + lateralMeters * cos(headingRadians)
        northMeters += north
        eastMeters += east
        speedMps = forwardMps.coerceAtLeast(0.0)

        addProcessNoise(
            forwardMeters = forwardMeters,
            constrainedLateralMeters = lateralMeters,
            rotationHeadingRadians = headingRadians
        )
        speedVariance += 0.05 * intervalSeconds
        return state()
    }

    /**
     * Fold a model speed estimate in as a measurement rather than overwriting the state.
     *
     * @param uncertaintyMps the network's own reported sigma. Passing a real uncertainty is
     *   what lets a doubtful prediction contribute less, instead of every prediction being
     *   trusted equally as the previous code did.
     */
    override fun updateSpeed(measuredMps: Double, uncertaintyMps: Double): FusedVehicleState? {
        if (reference == null) return null
        if (!measuredMps.isFinite() || !uncertaintyMps.isFinite()) return null
        val measurementVariance = (uncertaintyMps * uncertaintyMps).coerceAtLeast(0.05)
        val gain = speedVariance / (speedVariance + measurementVariance)
        speedMps = (speedMps + gain * (measuredMps.coerceAtLeast(0.0) - speedMps)).coerceAtLeast(0.0)
        speedVariance *= 1.0 - gain
        return state()
    }

    /**
     * IMU-rate attitude propagation between the lower-rate AI displacement windows.
     *
     * @param angularVelocityZRadPerSec yaw rate about the vehicle **Down** axis, which
     *   equals dHeading/dt. Must come from the vehicle frame, not raw device axes.
     */
    override fun predictGyro(angularVelocityZRadPerSec: Double, intervalSeconds: Double): FusedVehicleState? {
        if (reference == null || intervalSeconds <= 0.0 || intervalSeconds > 0.25) return null
        if (!angularVelocityZRadPerSec.isFinite()) return null
        val delta = angularVelocityZRadPerSec * intervalSeconds
        headingRadians = normalizeRadians(headingRadians + delta)
        gyroIntegratedThisWindowRadians += delta
        // Trapezoid-free midpoint sample of the heading shape integrals used by the
        // non-holonomic constraint. Accumulated at IMU rate so an arbitrary yaw profile
        // is captured, not just a constant turn.
        val midpointRelativeHeading = gyroIntegratedThisWindowRadians - delta / 2.0
        headingCosIntegralSeconds += cos(midpointRelativeHeading) * intervalSeconds
        headingSinIntegralSeconds += sin(midpointRelativeHeading) * intervalSeconds
        headingVariance += Math.toRadians(0.6).let { it * it }
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
            return state()
        }
        outageSeconds = 0.0
        val measurement = toLocal(position)
        val measurementVariance = accuracyMeters.coerceAtLeast(3.0).let { it * it }

        // Full 2x2 Kalman update: K = P (P + R)^-1 with isotropic R, then P = (I - K) P.
        val sEE = pEastEast + measurementVariance
        val sEN = pEastNorth
        val sNN = pNorthNorth + measurementVariance
        val determinant = sEE * sNN - sEN * sEN
        if (determinant > 1e-9) {
            val invEE = sNN / determinant
            val invEN = -sEN / determinant
            val invNN = sEE / determinant

            val kEE = pEastEast * invEE + pEastNorth * invEN
            val kEN = pEastEast * invEN + pEastNorth * invNN
            val kNE = pEastNorth * invEE + pNorthNorth * invEN
            val kNN = pEastNorth * invEN + pNorthNorth * invNN

            val innovationEast = measurement.first - eastMeters
            val innovationNorth = measurement.second - northMeters
            eastMeters += kEE * innovationEast + kEN * innovationNorth
            northMeters += kNE * innovationEast + kNN * innovationNorth

            val newEastEast = (1.0 - kEE) * pEastEast - kEN * pEastNorth
            val newEastNorth = (1.0 - kEE) * pEastNorth - kEN * pNorthNorth
            val newNorthNorth = -kNE * pEastNorth + (1.0 - kNN) * pNorthNorth
            pEastEast = newEastEast.coerceAtLeast(0.01)
            pNorthNorth = newNorthNorth.coerceAtLeast(0.01)
            pEastNorth = newEastNorth
            enforceCovarianceValidity()
        }

        val speedGain = speedVariance / (speedVariance + 2.25)
        this.speedMps += speedGain * (speedMps.coerceAtLeast(0.0) - this.speedMps)
        speedVariance *= 1.0 - speedGain

        if (speedMps >= 1.5) {
            val measurementHeading = Math.toRadians(headingDegrees)
            val headingGain = headingVariance / (headingVariance + Math.toRadians(12.0).let { it * it })
            headingRadians = normalizeRadians(headingRadians + headingGain * shortestDelta(headingRadians, measurementHeading))
            headingVariance *= 1.0 - headingGain
        }

        // GNSS just supplied truth, so the partially accumulated window is stale.
        anchorWindow()
        return state()
    }

    /**
     * Fold an accepted map match into the estimator state.
     *
     * This is the step the previous implementation was missing entirely. Matches were used
     * to move the marker on screen while the filter carried on from its uncorrected
     * position, so the next propagation continued from the drifted state and the display
     * only looked corrected.
     *
     * The correction is anisotropic. The innovation is split into components across and
     * along the road, and only the across-road component is trusted, because projecting
     * onto the nearest point of a road reveals nothing about progress along it.
     *
     * @param roadBearingDegrees road direction, 0 = North. When null the road orientation
     *   is unknown, so no correction is applied rather than guessing isotropically.
     */
    override fun updateMapConstraint(
        matchedPosition: GeoPoint,
        roadBearingDegrees: Double?,
        confidence: Int
    ): MapConstraintResult? {
        if (reference == null) return null
        if (!mapConstraint.enabled) {
            return MapConstraintResult(false, state(), 0.0, "constraint disabled")
        }
        if (confidence < mapConstraint.minConfidence) {
            rejectedMapConstraints++
            return MapConstraintResult(false, state(), 0.0, "confidence $confidence below ${mapConstraint.minConfidence}")
        }
        if (roadBearingDegrees == null || !roadBearingDegrees.isFinite()) {
            rejectedMapConstraints++
            return MapConstraintResult(false, state(), 0.0, "road bearing unknown")
        }
        if (!matchedPosition.latitude.isFinite() || !matchedPosition.longitude.isFinite()) {
            rejectedMapConstraints++
            return MapConstraintResult(false, state(), 0.0, "non-finite match")
        }

        val (measuredEast, measuredNorth) = toLocal(matchedPosition)
        val innovationEast = measuredEast - eastMeters
        val innovationNorth = measuredNorth - northMeters

        // Unit vectors along and across the road. Bearing is clockwise from North.
        val bearing = Math.toRadians(roadBearingDegrees)
        val alongEast = sin(bearing)
        val alongNorth = cos(bearing)
        val crossEast = cos(bearing)
        val crossNorth = -sin(bearing)

        val crossInnovation = innovationEast * crossEast + innovationNorth * crossNorth

        // Cross-track variance projected out of the covariance, so the gate and the gain
        // both reflect how uncertain the estimator actually is in that direction.
        val crossVariance = mapConstraint.crossTrackUncertaintyMeters.let { it * it }
        val projectedCross = projectVariance(crossEast, crossNorth)
        val innovationVariance = projectedCross + crossVariance
        val gateLimit = mapConstraint.gateSigma * sqrt(innovationVariance)
        if (abs(crossInnovation) > gateLimit) {
            rejectedMapConstraints++
            return MapConstraintResult(
                applied = false,
                state = state(),
                crossTrackCorrectionMeters = 0.0,
                rejectionReason = "cross-track innovation ${fmt(crossInnovation)} m exceeds gate ${fmt(gateLimit)} m"
            )
        }

        // Exact rank-1 Kalman update for the scalar measurement z = cross . x.
        // With the covariance matrix in hand this is no longer an approximation.
        val crossCorrection = rankOneUpdate(crossEast, crossNorth, crossInnovation, crossVariance)

        // The along-road channel is treated the same way. With the default enormous
        // along-track sigma its gain is effectively zero, which is the intended
        // modelling statement rather than an accident.
        val alongInnovation = innovationEast * alongEast + innovationNorth * alongNorth
        rankOneUpdate(
            alongEast,
            alongNorth,
            alongInnovation,
            mapConstraint.alongTrackUncertaintyMeters.let { it * it }
        )

        lastMapCrossTrackCorrectionMeters = crossCorrection
        mapConstraintUpdates++
        return MapConstraintResult(true, state(), crossCorrection)
    }

    /** Variance of the position estimate along a unit direction: `d^T P d`. */
    private fun projectVariance(east: Double, north: Double): Double =
        east * east * pEastEast + 2.0 * east * north * pEastNorth + north * north * pNorthNorth

    /**
     * Kalman update for a single scalar measurement of position along a unit direction.
     *
     * @return the correction applied along that direction, metres.
     */
    private fun rankOneUpdate(
        directionEast: Double,
        directionNorth: Double,
        innovation: Double,
        measurementVariance: Double
    ): Double {
        val projected = projectVariance(directionEast, directionNorth)
        val s = projected + measurementVariance
        if (s <= 1e-9) return 0.0

        // P d
        val pdEast = pEastEast * directionEast + pEastNorth * directionNorth
        val pdNorth = pEastNorth * directionEast + pNorthNorth * directionNorth

        val gainEast = pdEast / s
        val gainNorth = pdNorth / s

        eastMeters += gainEast * innovation
        northMeters += gainNorth * innovation

        // P -= (P d)(P d)^T / s
        pEastEast = (pEastEast - pdEast * pdEast / s).coerceAtLeast(0.01)
        pNorthNorth = (pNorthNorth - pdNorth * pdNorth / s).coerceAtLeast(0.01)
        pEastNorth -= pdEast * pdNorth / s
        enforceCovarianceValidity()

        return projected / s * innovation
    }

    /**
     * Keep the covariance a valid symmetric positive-definite matrix. Repeated rank-1
     * updates in floating point can otherwise drift the correlation term past the bound
     * implied by the diagonal, producing a negative determinant and nonsense uncertainty.
     */
    private fun enforceCovarianceValidity() {
        val maxCorrelation = 0.999 * sqrt(pEastEast * pNorthNorth)
        if (abs(pEastNorth) > maxCorrelation) {
            pEastNorth = if (pEastNorth > 0) maxCorrelation else -maxCorrelation
        }
    }

    override fun state(): FusedVehicleState {
        val ref = checkNotNull(reference)
        val latitude = ref.latitude + northMeters / 111_111.0
        val longitude = ref.longitude + eastMeters / (111_111.0 * cos(Math.toRadians(ref.latitude)))

        // Major axis of the position covariance: the worst-case 1-sigma direction.
        val mean = 0.5 * (pEastEast + pNorthNorth)
        val halfDifference = 0.5 * (pEastEast - pNorthNorth)
        val spread = sqrt(halfDifference * halfDifference + pEastNorth * pEastNorth)
        val majorVariance = (mean + spread).coerceAtLeast(0.0)

        // Uncertainty resolved onto the vehicle's own axes, which is what the map
        // constraint and the UI actually care about.
        val alongEast = sin(headingRadians)
        val alongNorth = cos(headingRadians)
        val crossEast = cos(headingRadians)
        val crossNorth = -sin(headingRadians)

        return FusedVehicleState(
            position = GeoPoint(latitude, longitude),
            speedMps = speedMps,
            headingDegrees = (Math.toDegrees(headingRadians) + 360.0) % 360.0,
            horizontalUncertaintyMeters = sqrt(majorVariance).coerceAtLeast(1.0),
            alongTrackUncertaintyMeters = sqrt(projectVariance(alongEast, alongNorth).coerceAtLeast(0.0)),
            crossTrackUncertaintyMeters = sqrt(projectVariance(crossEast, crossNorth).coerceAtLeast(0.0)),
            speedUncertaintyMps = sqrt(speedVariance.coerceAtLeast(0.0)),
            headingUncertaintyDegrees = Math.toDegrees(sqrt(headingVariance.coerceAtLeast(0.0)))
        )
    }

    /** Re-anchors the window reference to the current heading and clears the accumulators. */
    private fun anchorWindow() {
        headingAtWindowStartRadians = headingRadians
        gyroIntegratedThisWindowRadians = 0.0
        headingCosIntegralSeconds = 0.0
        headingSinIntegralSeconds = 0.0
    }

    private fun toLocal(position: GeoPoint): Pair<Double, Double> {
        val ref = checkNotNull(reference)
        val north = (position.latitude - ref.latitude) * 111_111.0
        val east = (position.longitude - ref.longitude) * 111_111.0 * cos(Math.toRadians(ref.latitude))
        return east to north
    }

    private fun shortestDelta(from: Double, to: Double): Double = normalizeRadians(to - from)
    private fun normalizeRadians(value: Double): Double =
        ((value + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI

    private fun fmt(value: Double) = String.format("%.2f", value)
}
