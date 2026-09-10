package nisargpatel.deadreckoning.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlin.math.exp
import kotlin.math.max
import nisargpatel.deadreckoning.core.spec.PreprocessingSpec

/**
 * One PINO-DR v7 inference. Represents motion over the last one-second bin with
 * Mixture-of-Experts routing weights.
 *
 * `stepIntervalSeconds` is exposed alongside the derived per-step distances so the
 * fusion layer never has to guess. Every consumer of a [PinoPrediction] must integrate
 * over exactly this interval; feeding a 1-second displacement at a 5 Hz cadence would
 * five-times-count the same motion.
 */
data class PinoPrediction(
    val speedMps: Float,
    val speedKmh: Float,
    val yawRateRadPerSec: Float,
    val isStationary: Boolean,
    val zuptProbability: Float,
    val stepForwardMeters: Float,
    val stepLateralMeters: Float,
    val stepHeadingDeltaRadians: Float,
    val stepIntervalSeconds: Float,
    val inferenceTimeMs: Long,
    val routerWeights: FloatArray = FloatArray(5),
    val dominantExpert: String = ""
)

/**
 * Deployment metadata for the packaged PINO-DR v7 artifact.
 *
 * `preprocessing_version` is asserted against [PreprocessingSpec.PINO_V7.version] at
 * engine load, so a training pipeline that produces a differently-preprocessed model
 * cannot be shipped silently. `raw_sample_rate_hz`, `bin_seconds` and `window_size` are
 * asserted against the runtime constants for the same reason.
 */
data class PinoManifest(
    val model: String = "PINO-DR v7 Supreme MoE",
    val architecture: String = "5-Expert Mixture-of-Experts with Kinematic Router",
    val deployment_status: String = "PINO-DR v7 Supreme MoE",
    val preprocessing_version: String = "",
    val parameters: Int = 0,
    val raw_sample_rate_hz: Double = 0.0,
    val bin_seconds: Double = 0.0,
    val bin_sample_rate_hz: Double = 0.0,
    val window_size: Int = 0,
    val window_seconds: Double = 0.0,
    val prediction_hz: Double = 0.0,
    val zupt_threshold: Float = 0.70f,
    val sha256: String = "",
    val channels: List<String> = emptyList(),
    val experts: List<String> = emptyList()
)

/**
 * Runs the PINO-DR v7 Supreme 5-Expert Mixture-of-Experts (MoE) model on-device.
 *
 * ## 6-Channel Windowing Contract
 *
 * Channels:
 *  0: a_fwd (m/s^2) - Vehicle forward acceleration
 *  1: w_yaw (rad/s) - Vehicle yaw rate about Down axis
 *  2: a_lat (m/s^2) - Vehicle lateral acceleration
 *  3: v_prev (m/s)  - Velocity feedback / previous speed
 *  4: yaw_accel (rad/s^2) - 10 Hz Savitzky-Golay numerical derivative (order 2, deriv 1, dt=0.1s)
 *  5: a_cent_residual (m/s^2) - Centripetal residual: a_lat - v_prev * w_yaw
 *
 * The pipeline:
 *  1. Enforce ~10 Hz decimation on the incoming sensor stream.
 *  2. Calculate 10 Hz yaw_accel via 5-point Savitzky-Golay filter and centripetal residual.
 *  3. Accumulate 10 raw samples into a 6-channel bin (1 full second). Mean-reduce to 1 timestep.
 *  4. Push reduced bin into a 10-bin rolling deque (10 seconds of history).
 *  5. Emit predictions at 1 Hz with [disp_pred, ori_pred, zupt_logits, router_weights].
 */
class PinoDrMotionEngine(
    context: Context,
    private val spec: PreprocessingSpec = PreprocessingSpec.PINO_V7
) : AutoCloseable {

    companion object {
        private const val TAG = "PinoDrEngine"
        private const val MODEL_ASSET = "ml/v7_supreme_moe.onnx"
        private const val MANIFEST_ASSET = "ml/v7_pino_manifest.json"

        // Physical clamps applied to every raw sample before averaging. Matches the
        // training pipeline's clamping bounds.
        private const val CLIP_ACCEL_MIN = -8.0f
        private const val CLIP_ACCEL_MAX = 8.0f
        private const val CLIP_GYRO_MIN = -1.0f
        private const val CLIP_GYRO_MAX = 1.0f
        private const val CLIP_V_PREV_MIN = 0.0f
        private const val CLIP_V_PREV_MAX = 45.0f

        private const val CLIP_YAW_ACCEL_MIN = -2.0f
        private const val CLIP_YAW_ACCEL_MAX = 2.0f
        private const val CLIP_CENTRIPETAL_MIN = -8.0f
        private const val CLIP_CENTRIPETAL_MAX = 8.0f

        // Output clamps.
        private const val CLIP_DISP_MIN = 0.0f
        private const val CLIP_DISP_MAX = 45.0f
        private const val CLIP_YAW_MIN = -1.2f
        private const val CLIP_YAW_MAX = 1.2f

        // MinMax scaler constants baked from scalers_v7.pkl.
        private const val S_A_FWD_SCALE = 0.076441556f
        private const val S_A_FWD_MIN = 0.52882564f

        private const val S_W_YAW_SCALE = 0.5f
        private const val S_W_YAW_MIN = 0.5f

        private const val S_A_LAT_SCALE = 0.083676726f
        private const val S_A_LAT_MIN = 0.5240971f

        private const val S_V_PREV_SCALE = 0.022222223f // 1.0 / 45.0
        private const val S_V_PREV_MIN = 0.0f

        private const val S_YAW_ACCEL_SCALE = 0.25f
        private const val S_YAW_ACCEL_MIN = 0.5f

        private const val S_CENTRIPETAL_SCALE = 0.0625f
        private const val S_CENTRIPETAL_MIN = 0.5f

        private const val S_Y_DISP_SCALE = 0.022222223f
        private const val S_Y_ORI_SCALE = 0.49350372f
        private const val S_Y_ORI_MIN = 0.504565f

        // ZUPT hysteresis. Enter stationary after this many consecutive high-confidence
        // stops, exit after this many low-confidence samples; chattering is expensive.
        private const val ZUPT_HIGH_THRESH = 0.70f
        private const val ZUPT_LOW_THRESH = 0.30f
        private const val ZUPT_N_ENTER = 3
        private const val ZUPT_N_EXIT = 2

        /** Below this reported speed, the ZUPT gate zeroes out the model's leftover drift. */
        private const val MIN_MOVING_SPEED_MPS = 0.4f

        val EXPERT_NAMES = listOf(
            "Motorway Cruising",
            "Roundabout",
            "Quick Accel",
            "Hard Brake",
            "Sharp Turns"
        )
    }

    /** Model-facing rate: predictions are emitted at this Hz. */
    val predictionHz: Double get() = spec.predictionHz

    /** Seconds represented by one bin, i.e. one model timestep. */
    private val binSeconds: Float = 1.0f / spec.sampleRateHz.toFloat()

    /** Raw sensor samples per bin. Ten at 10 Hz becomes one 1 Hz averaged bin. */
    private val rawSamplesPerBin: Int =
        PreprocessingSpec.PINO_V7_RAW_SAMPLE_RATE_HZ / spec.sampleRateHz

    private val windowSize: Int = spec.windowSamples
    private val channelCount: Int = spec.channelCount

    /** Minimum wall clock between accepted raw samples, in nanoseconds. */
    private val rawSampleIntervalNs: Long =
        1_000_000_000L / PreprocessingSpec.PINO_V7_RAW_SAMPLE_RATE_HZ

    /** Slack applied to the raw-sample gate to tolerate a slightly early sensor callback. */
    private val rawSampleGuardNs: Long = (rawSampleIntervalNs * 4) / 5

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    val manifest: PinoManifest

    /** Accumulator for the current one-second bin (6 channels). */
    private val binAccumulator = FloatArray(6)
    private var samplesInCurrentBin: Int = 0

    /** Ring buffer of last 5 yaw samples for 10 Hz Savitzky-Golay differentiation. */
    private val yawRingBuffer = FloatArray(5)
    private var yawSampleCount: Int = 0

    /** Rolling deque of the last [windowSize] one-second bins. */
    private val window = ArrayDeque<FloatArray>(windowSize)
    private var lastAcceptedTimestampNs: Long = 0L

    // Engine persistent state.
    private var currentVelocityMps: Float = 0.0f
    private var isStopped: Boolean = false
    private var zuptHighCount: Int = 0
    private var zuptLowCount: Int = 0

    init {
        require(spec.channelCount == 6) {
            "PINO-DR v7 expects 6 channels but spec has ${spec.channelCount}"
        }
        require(rawSamplesPerBin >= 1) {
            "raw sample rate ${PreprocessingSpec.PINO_V7_RAW_SAMPLE_RATE_HZ} Hz cannot bin to " +
                "model rate ${spec.sampleRateHz} Hz"
        }

        manifest = context.assets.open(MANIFEST_ASSET).bufferedReader().use {
            Gson().fromJson(it, PinoManifest::class.java)
        }

        require(manifest.preprocessing_version == spec.version) {
            "PINO manifest is for '${manifest.preprocessing_version}' but this build " +
                "expects '${spec.version}'. Refusing to load; the runtime and training " +
                "preprocessing pipelines would silently disagree."
        }
        require(manifest.window_size == windowSize) {
            "PINO manifest window_size ${manifest.window_size} does not match runtime $windowSize"
        }
        val binRate = if (manifest.bin_sample_rate_hz > 0.0) manifest.bin_sample_rate_hz
        else 1.0 / manifest.bin_seconds.coerceAtLeast(1e-9)
        require(kotlin.math.abs(binRate - spec.sampleRateHz.toDouble()) < 1e-6) {
            "PINO manifest bin rate $binRate Hz does not match runtime ${spec.sampleRateHz} Hz"
        }
        require(kotlin.math.abs(manifest.raw_sample_rate_hz -
            PreprocessingSpec.PINO_V7_RAW_SAMPLE_RATE_HZ.toDouble()) < 1e-6) {
            "PINO manifest raw_sample_rate_hz ${manifest.raw_sample_rate_hz} does not match " +
                "runtime ${PreprocessingSpec.PINO_V7_RAW_SAMPLE_RATE_HZ}"
        }

        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        if (manifest.sha256.isNotBlank()) {
            val digest = MessageDigest.getInstance("SHA-256")
            val computedHash = digest.digest(modelBytes).joinToString("") { "%02x".format(it) }
            require(computedHash.equals(manifest.sha256, ignoreCase = true)) {
                "PINO-DR v7 model file integrity check failed! Expected sha256: ${manifest.sha256}, computed: $computedHash"
            }
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        session = environment.createSession(modelBytes, options)
        Log.i(
            TAG,
            "Loaded ${manifest.model} (${manifest.parameters} params). " +
                "Contract: ${spec.describe()}. " +
                "Bin ${rawSamplesPerBin} raw samples -> 1 timestep; window ${windowSize} bins = " +
                "${windowSize * binSeconds} s of history; prediction ${predictionHz} Hz."
        )
    }

    /**
     * Feed a single raw IMU sample. Returns a prediction only when a full second of raw
     * samples has completed a bin AND the 10-bin window is full, i.e. once per second.
     *
     * @param timestampNs sample timestamp on the sensor event clock.
     * @param aFwd vehicle forward acceleration, m/s^2.
     * @param wYaw yaw rate about the vehicle Down axis, rad/s.
     * @param aLat vehicle lateral acceleration, m/s^2.
     * @param seedVelocityMps current velocity hint, typically the last GNSS speed or the
     *   engine's own previous output when GNSS is silent.
     */
    fun addSample(
        timestampNs: Long,
        aFwd: Float,
        wYaw: Float,
        aLat: Float,
        seedVelocityMps: Float
    ): PinoPrediction? {
        // 10 Hz decimation on the raw stream.
        if (lastAcceptedTimestampNs != 0L &&
            (timestampNs - lastAcceptedTimestampNs) < rawSampleGuardNs
        ) {
            return null
        }
        lastAcceptedTimestampNs = timestampNs

        val clampedFwd = aFwd.coerceIn(CLIP_ACCEL_MIN, CLIP_ACCEL_MAX)
        val clampedYaw = wYaw.coerceIn(CLIP_GYRO_MIN, CLIP_GYRO_MAX)
        val clampedLat = aLat.coerceIn(CLIP_ACCEL_MIN, CLIP_ACCEL_MAX)
        val vPrev = (if (seedVelocityMps > 0f) seedVelocityMps else currentVelocityMps)
            .coerceIn(CLIP_V_PREV_MIN, CLIP_V_PREV_MAX)

        // Numerical angular acceleration (d_omega/dt) using 5-point Savitzky-Golay filter
        // at 10 Hz (Delta t = 0.1 s).
        yawRingBuffer[yawSampleCount % 5] = clampedYaw
        yawSampleCount++

        val rawYawAccel = when {
            yawSampleCount == 1 -> 0.0f
            yawSampleCount < 5 -> {
                // Backward finite difference for samples 1..3 before 5-point buffer saturates
                val prevYaw = yawRingBuffer[(yawSampleCount - 2) % 5]
                (clampedYaw - prevYaw) / 0.1f
            }
            else -> {
                // 5-point Savitzky-Golay 1st derivative (degree 2, window 5, dt = 0.1s):
                // Coefficients [-2, -1, 0, 1, 2] / (10 * dt) = [-2, -1, 0, 1, 2] / 1.0 (no extra division)
                val y0 = yawRingBuffer[(yawSampleCount - 5) % 5]
                val y1 = yawRingBuffer[(yawSampleCount - 4) % 5]
                val y3 = yawRingBuffer[(yawSampleCount - 2) % 5]
                val y4 = yawRingBuffer[(yawSampleCount - 1) % 5]
                2.0f * y4 + 1.0f * y3 - 1.0f * y1 - 2.0f * y0
            }
        }
        val clampedYawAccel = rawYawAccel.coerceIn(CLIP_YAW_ACCEL_MIN, CLIP_YAW_ACCEL_MAX)

        // Centripetal residual: a_lat - v_prev * w_yaw
        val rawCentripetal = clampedLat - vPrev * clampedYaw
        val clampedCentripetal = rawCentripetal.coerceIn(CLIP_CENTRIPETAL_MIN, CLIP_CENTRIPETAL_MAX)

        // Accumulate 6 channels into the current bin
        binAccumulator[0] += clampedFwd
        binAccumulator[1] += clampedYaw
        binAccumulator[2] += clampedLat
        binAccumulator[3] += vPrev
        binAccumulator[4] += clampedYawAccel
        binAccumulator[5] += clampedCentripetal
        samplesInCurrentBin++

        if (samplesInCurrentBin < rawSamplesPerBin) return null

        // Close out the bin: replace the running sum with its mean and hand it to the window.
        val bin = FloatArray(6)
        val inverse = 1.0f / rawSamplesPerBin
        bin[0] = binAccumulator[0] * inverse
        bin[1] = binAccumulator[1] * inverse
        bin[2] = binAccumulator[2] * inverse
        bin[3] = binAccumulator[3] * inverse
        bin[4] = binAccumulator[4] * inverse
        bin[5] = binAccumulator[5] * inverse

        binAccumulator.fill(0f)
        samplesInCurrentBin = 0

        if (window.size == windowSize) window.removeFirst()
        window.addLast(bin)

        if (window.size < windowSize) return null
        return runInference()
    }

    private fun runInference(): PinoPrediction {
        val startNs = System.nanoTime()

        // Build normalised input tensor [1, windowSize, 6].
        val inputFlat = FloatArray(windowSize * channelCount)
        var offset = 0
        for (bin in window) {
            inputFlat[offset]     = bin[0] * S_A_FWD_SCALE       + S_A_FWD_MIN
            inputFlat[offset + 1] = bin[1] * S_W_YAW_SCALE       + S_W_YAW_MIN
            inputFlat[offset + 2] = bin[2] * S_A_LAT_SCALE       + S_A_LAT_MIN
            inputFlat[offset + 3] = bin[3] * S_V_PREV_SCALE      + S_V_PREV_MIN
            inputFlat[offset + 4] = bin[4] * S_YAW_ACCEL_SCALE   + S_YAW_ACCEL_MIN
            inputFlat[offset + 5] = bin[5] * S_CENTRIPETAL_SCALE + S_CENTRIPETAL_MIN
            offset += channelCount
        }

        val inputTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(inputFlat),
            longArrayOf(1, windowSize.toLong(), channelCount.toLong())
        )

        val outputs = session.run(mapOf("imu_input_6ch" to inputTensor))
        inputTensor.close()

        val dispTensor = outputs[0].value as Array<FloatArray>
        val oriTensor = outputs[1].value as Array<FloatArray>
        val zuptTensor = outputs[2].value as Array<FloatArray>
        val routerTensor = outputs[3].value as Array<FloatArray>

        val dPredScaled = dispTensor[0][0]
        val oPredScaled = oriTensor[0][0]
        val zuptLogit = zuptTensor[0][0]
        val routerWeights = routerTensor[0].clone()

        outputs.close()

        // Inverse-scale to physical units. Clamp so an out-of-distribution logit cannot
        // propagate a non-finite step into the EKF.
        var rawVelocity = (dPredScaled / S_Y_DISP_SCALE)
            .coerceIn(CLIP_DISP_MIN, CLIP_DISP_MAX)
        var rawYawRate = ((oPredScaled - S_Y_ORI_MIN) / S_Y_ORI_SCALE)
            .coerceIn(CLIP_YAW_MIN, CLIP_YAW_MAX)
        val pStop = 1.0f / (1.0f + exp(-zuptLogit))

        // Find dominant expert from router distribution
        var dominantIdx = 0
        var maxWeight = -1.0f
        for (i in routerWeights.indices) {
            if (routerWeights[i] > maxWeight) {
                maxWeight = routerWeights[i]
                dominantIdx = i
            }
        }
        val dominantExpert = EXPERT_NAMES.getOrElse(dominantIdx) { "Expert $dominantIdx" }

        // ZUPT hysteresis: prefer to stay in the state we are in unless the signal has
        // been decisive for a few consecutive predictions.
        if (!isStopped) {
            if (pStop > ZUPT_HIGH_THRESH) {
                zuptHighCount++
                zuptLowCount = 0
                if (zuptHighCount >= ZUPT_N_ENTER) isStopped = true
            } else {
                zuptHighCount = 0
            }
        } else {
            if (pStop < ZUPT_LOW_THRESH) {
                zuptLowCount++
                zuptHighCount = 0
                if (zuptLowCount >= ZUPT_N_EXIT) isStopped = false
            } else {
                zuptLowCount = 0
            }
        }

        if (isStopped || rawVelocity < MIN_MOVING_SPEED_MPS) {
            rawVelocity = 0.0f
            rawYawRate = 0.0f
        }

        currentVelocityMps = rawVelocity

        // One prediction represents exactly one bin of forward integration.
        val stepInterval = binSeconds
        val stepForwardMeters = rawVelocity * stepInterval
        val stepHeadingDeltaRadians = rawYawRate * stepInterval

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        return PinoPrediction(
            speedMps = rawVelocity,
            speedKmh = rawVelocity * 3.6f,
            yawRateRadPerSec = rawYawRate,
            isStationary = isStopped,
            zuptProbability = pStop,
            stepForwardMeters = stepForwardMeters,
            stepLateralMeters = 0.0f,
            stepHeadingDeltaRadians = stepHeadingDeltaRadians,
            stepIntervalSeconds = stepInterval,
            inferenceTimeMs = max(1L, elapsedMs),
            routerWeights = routerWeights,
            dominantExpert = dominantExpert
        )
    }

    fun reset() {
        window.clear()
        binAccumulator.fill(0f)
        samplesInCurrentBin = 0
        lastAcceptedTimestampNs = 0L
        currentVelocityMps = 0.0f
        isStopped = false
        zuptHighCount = 0
        zuptLowCount = 0
        yawRingBuffer.fill(0f)
        yawSampleCount = 0
    }

    override fun close() {
        session.close()
    }
}
