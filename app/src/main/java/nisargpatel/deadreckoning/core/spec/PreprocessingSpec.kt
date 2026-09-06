package nisargpatel.deadreckoning.core.spec

/**
 * THE single source of truth for IMU preprocessing.
 *
 * Any Python training pipeline that produces a model for this app MUST mirror the
 * spec it targets, field for field. Divergence between training preprocessing and
 * runtime preprocessing is the defect that made the shipped V8 artifact unusable:
 * training built `accel - gravity` with gyro ordered (yaw, pitch, roll), while the
 * Android runtime fed raw gravity-included accelerometer values with gyro ordered
 * (deviceX, deviceY, deviceZ). Nothing detected the mismatch.
 *
 * Deliberately free of Android imports so the navigation core stays portable.
 */

/** Coordinate frame the six IMU channels are expressed in. */
enum class ImuFrame {
    /** Raw Android device axes, gravity still present. Orientation dependent. */
    PHONE_RAW,

    /**
     * Android device axes with the gravity component removed. Still orientation
     * dependent, but free of the dominant static term.
     *
     * This is what IO-VNBD actually supports. A mount-independent vehicle frame is not
     * trainable from that dataset: its gyro columns behave as Euler angle rates rather
     * than a body angular-velocity vector, so they cannot be rotated. Projecting them
     * onto a gravity-derived Down axis correlates 0.035 with the vehicle's own yaw rate
     * while a single raw channel reaches 0.95, and no permutation or sign flip of the
     * triple reconciles the two.
     */
    PHONE_LINEAR,

    /**
     * Vehicle body frame: X = forward, Y = right, Z = down (FRD, right-handed).
     *
     * Implemented and unit-tested on the runtime side in
     * [nisargpatel.deadreckoning.core.frame.VehicleFrameTransform], but no model is
     * trained against it yet because IO-VNBD cannot express it.
     */
    VEHICLE_FRD
}

/** Whether the gravity component is still present in the accelerometer channels. */
enum class GravityHandling { INCLUDED, REMOVED }

/** Ordering of the three angular-rate channels. */
enum class GyroChannelOrder {
    /** Android device X, Y, Z. */
    DEVICE_XYZ,

    /**
     * IO-VNBD's own column order, kept verbatim without assuming what the labels mean.
     *
     * The dataset labels its three gyro columns Yaw, Pitch and Roll, but measurement
     * disagrees with those names: correlated against the vehicle's reported yaw rate, the
     * column labelled Pitch reaches 0.95 while the one labelled Yaw sits at 0.00. The
     * channels are therefore passed through in file order and the model is left to learn
     * their roles, rather than acting on labels that are demonstrably wrong.
     */
    DATASET_NATIVE,

    /** Rates about the vehicle Down, Right, Forward axes, in that order. */
    VEHICLE_YAW_PITCH_ROLL
}

data class PreprocessingSpec(
    val version: String,
    val sampleRateHz: Int,
    val windowSamples: Int,
    val strideSamples: Int,
    val frame: ImuFrame,
    val gravity: GravityHandling,
    val gyroOrder: GyroChannelOrder,
    val channelNames: List<String>
) {
    val channelCount: Int get() = channelNames.size

    /** Nominal window length. 20 samples at 10 Hz = 2.0 s. */
    val windowSeconds: Double get() = windowSamples / sampleRateHz.toDouble()

    /**
     * Time actually spanned between the first and last sample of a window.
     * This, not [windowSeconds], is the interval a window-displacement target covers.
     * 20 samples at 10 Hz span 1.9 s, not 2.0 s.
     */
    val windowSpanSeconds: Double get() = (windowSamples - 1) / sampleRateHz.toDouble()

    val strideSeconds: Double get() = strideSamples / sampleRateHz.toDouble()

    /** Rate at which a consumer receives a fresh prediction. */
    val predictionHz: Double get() = sampleRateHz.toDouble() / strideSamples

    fun describe(): String = buildString {
        append("PreprocessingSpec[$version] ")
        append("${sampleRateHz}Hz win=$windowSamples(${windowSeconds}s span=${windowSpanSeconds}s) ")
        append("stride=$strideSamples(${strideSeconds}s -> ${predictionHz}Hz) ")
        append("frame=$frame gravity=$gravity gyro=$gyroOrder ch=$channelCount")
    }

    companion object {
        /**
         * Bit-exact description of what the currently shipped `v8_dead_reckoning.onnx`
         * is fed by the existing runtime. Retained as the untouched baseline so V8 keeps
         * behaving identically until a validated replacement exists.
         *
         * This spec is NOT correct — it is merely what is currently happening. The
         * frame is orientation dependent and gravity leaks into the accelerometer
         * channels.
         */
        val LEGACY_V8 = PreprocessingSpec(
            version = "legacy-v8",
            sampleRateHz = 10,
            windowSamples = 20,
            strideSamples = 20,
            frame = ImuFrame.PHONE_RAW,
            gravity = GravityHandling.INCLUDED,
            gyroOrder = GyroChannelOrder.DEVICE_XYZ,
            channelNames = listOf(
                "accel_device_x", "accel_device_y", "accel_device_z",
                "gyro_device_x", "gyro_device_y", "gyro_device_z"
            )
        )

        /**
         * Contract for the Stage 7 replacement model.
         *
         * Describes what IO-VNBD can actually support, verified against the data rather
         * than assumed:
         *
         *  - Gravity removed. The dataset supplies a GRAVITY column whose magnitude is
         *    exactly 9.80665 in every session, so removal is exact rather than filtered.
         *    Measured linear acceleration then has a Z mean near 0.03 instead of 9.79.
         *  - Phone axes retained. The vehicle frame is not reachable from this dataset,
         *    so claiming mount independence here would be false.
         *  - Gyro channels in file order, because the dataset's own labels are wrong.
         *  - Stride 2, giving a 5 Hz prediction cadence from a 2 s window and matching
         *    the training pipeline instead of the runtime's non-overlapping windows.
         *
         * A higher output rate is only safe alongside the velocity head this contract
         * adds: accumulating overlapping window displacements would multiply distance
         * travelled, which
         * [nisargpatel.deadreckoning.fusion.VehicleFusionEkf.predictVelocity] avoids.
         */
        val IDR_V1 = PreprocessingSpec(
            version = "idr-v1",
            sampleRateHz = 10,
            windowSamples = 20,
            strideSamples = 2,
            frame = ImuFrame.PHONE_LINEAR,
            gravity = GravityHandling.REMOVED,
            gyroOrder = GyroChannelOrder.DATASET_NATIVE,
            channelNames = listOf(
                "lin_accel_x", "lin_accel_y", "lin_accel_z",
                "gyro_ch0", "gyro_ch1", "gyro_ch2"
            )
        )

        /**
         * PINO-DR v3 (Physics-Informed Neural Operator for Dead Reckoning).
         *
         * The training pipeline downsamples raw 10 Hz IMU + ECU into 1 Hz bins by taking
         * the arithmetic mean of every 10 raw samples, then feeds a rolling 10-bin window
         * to the model and steps forward one bin at a time. Every model "timestep" is
         * therefore a 1-second average, the full input window spans 10 seconds of history,
         * and one prediction is emitted per second.
         *
         * The four channels are the vehicle-frame forward acceleration, yaw rate,
         * measured lateral acceleration, and previous-second velocity. `w_yaw` is a rate
         * about the vehicle Down axis, not the raw Android `gyroZ`. The runtime falls
         * back to phone-frame proxies (`accelY`, `gyroZ`, `accelX`) when vehicle-frame
         * alignment is not confident, which is a materially different input distribution
         * and is documented rather than silently applied.
         */
        val PINO_V3 = PreprocessingSpec(
            version = "pino-v3",
            sampleRateHz = 1,
            windowSamples = 10,
            strideSamples = 1,
            frame = ImuFrame.VEHICLE_FRD,
            gravity = GravityHandling.REMOVED,
            gyroOrder = GyroChannelOrder.VEHICLE_YAW_PITCH_ROLL,
            channelNames = listOf(
                "a_fwd", "w_yaw", "a_lat_measured", "v_prev"
            )
        )

        /**
         * Raw IMU rate that PINO-DR v3 expects to be pre-averaged from.
         *
         * The spec's own [sampleRateHz] is the model-facing rate (1 Hz after averaging),
         * so this constant is kept alongside it. The engine averages `PINO_V3_RAW_SAMPLE_RATE_HZ /
         * PINO_V3.sampleRateHz = 10` raw samples per bin.
         */
        const val PINO_V3_RAW_SAMPLE_RATE_HZ = 10
    }
}

/**
 * Physical plausibility gate for a model's displacement normalisation statistics.
 *
 * Encodes the lesson from the shipped V8 artifact, whose statistics were physically
 * impossible and therefore proved the training target was not vehicle-frame
 * displacement at all:
 *
 *   speed_mean      = 11.624 m/s over a 1.9 s window  =>  forward mean should be ~+22 m
 *   position_mean   = [-3.00, +3.70] m                =>  forward mean was NEGATIVE
 *   position_std    = [19.85, 18.59] m                =>  lateral/forward ratio 0.936
 *   lateral 1-sigma = 18.59 m in 1.9 s                =>  35 km/h sideways, impossible
 *
 * Call this before trusting any artifact so the same class of defect cannot ship twice.
 */
object DisplacementStatsGuard {

    /** Lateral spread should be a small fraction of forward spread for a road vehicle. */
    const val MAX_LATERAL_TO_FORWARD_STD_RATIO = 0.35

    /** Forward mean must agree with mean speed times window span within this factor. */
    const val FORWARD_MEAN_TOLERANCE = 0.45

    data class Verdict(val plausible: Boolean, val reasons: List<String>) {
        fun requirePlausible() {
            require(plausible) { "Implausible displacement statistics: ${reasons.joinToString("; ")}" }
        }
    }

    fun check(
        speedMeanMps: Double,
        forwardMeanMeters: Double,
        lateralMeanMeters: Double,
        forwardStdMeters: Double,
        lateralStdMeters: Double,
        windowSpanSeconds: Double
    ): Verdict {
        val reasons = mutableListOf<String>()

        val expectedForwardMean = speedMeanMps * windowSpanSeconds
        if (forwardMeanMeters <= 0.0 && expectedForwardMean > 0.0) {
            reasons += "forward mean ${fmt(forwardMeanMeters)} m is not positive while mean speed " +
                "${fmt(speedMeanMps)} m/s over ${fmt(windowSpanSeconds)} s implies " +
                "${fmt(expectedForwardMean)} m"
        } else if (expectedForwardMean > 0.0) {
            val relativeError = kotlin.math.abs(forwardMeanMeters - expectedForwardMean) / expectedForwardMean
            if (relativeError > FORWARD_MEAN_TOLERANCE) {
                reasons += "forward mean ${fmt(forwardMeanMeters)} m disagrees with speed-implied " +
                    "${fmt(expectedForwardMean)} m by ${fmt(relativeError * 100)}%"
            }
        }

        if (forwardStdMeters > 0.0) {
            val ratio = lateralStdMeters / forwardStdMeters
            if (ratio > MAX_LATERAL_TO_FORWARD_STD_RATIO) {
                reasons += "lateral/forward std ratio ${fmt(ratio)} exceeds " +
                    "$MAX_LATERAL_TO_FORWARD_STD_RATIO, displacement axes look isotropic"
            }
        }

        val impliedLateralSpeed = lateralStdMeters / windowSpanSeconds
        if (impliedLateralSpeed > 3.0) {
            reasons += "lateral 1-sigma implies ${fmt(impliedLateralSpeed * 3.6)} km/h sideways"
        }

        if (kotlin.math.abs(lateralMeanMeters) > 2.0) {
            reasons += "lateral mean ${fmt(lateralMeanMeters)} m should be near zero"
        }

        return Verdict(reasons.isEmpty(), reasons)
    }

    private fun fmt(value: Double) = String.format("%.3f", value)
}
