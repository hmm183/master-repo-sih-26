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
 * One PINO-DR v3 inference. Represents motion over the last one-second bin.
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
    val inferenceTimeMs: Long
)

/**
 * Deployment metadata for the packaged PINO-DR v3 artifact.
 *
 * `preprocessing_version` is asserted against [PreprocessingSpec.PINO_V3.version] at
 * engine load, so a training pipeline that produces a differently-preprocessed model
 * cannot be shipped silently. `raw_sample_rate_hz`, `bin_seconds` and `window_size` are
 * asserted against the runtime constants for the same reason.
 */
data class PinoManifest(
    val model: String = "PINO-DR v3",
    val architecture: String = "Conv1D-BiGRU-TemporalAttention",
    val deployment_status: String = "PINO-DR v3 Production",
    val preprocessing_version: String = "",
    val parameters: Int = 0,
    val raw_sample_rate_hz: Double = 0.0,
    val bin_seconds: Double = 0.0,
    val bin_sample_rate_hz: Double = 0.0,
    val window_size: Int = 0,
    val window_seconds: Double = 0.0,
    val prediction_hz: Double = 0.0,
    val zupt_threshold: Float = 0.70f,
    val sha256: String = ""
)

/**
 * Runs the PINO-DR v3 model on-device.
 *
 * ## Windowing contract, and the mismatch this class is fixing
 *
 * PINO-DR v3 was trained on IO-VNBD sequences downsampled from 10 Hz to 1 Hz by taking
 * the mean of every 10 raw samples. Every one of the model's ten timesteps is therefore
 * a *one-second average*, the full input tensor spans *ten seconds* of history, and the
 * model emits *one prediction per second*.
 *
 * The previous implementation of this class pushed raw ~10 Hz IMU samples straight into
 * a 10-slot buffer and predicted every 2 samples. That collapsed the input window from
 * 10 seconds of smoothed data to 1 second of raw pothole-and-engine-vibration noise, and
 * the model was being handed an input distribution it had never seen. On-device drift
 * numbers therefore said almost nothing about the reported benchmark.
 *
 * The corrected pipeline is:
 *
 *  1. Enforce ~10 Hz decimation on the incoming sensor stream.
 *  2. Accumulate 10 raw samples into a bin. When full, mean-reduce to a single
 *     four-channel vector representing that one second of motion.
 *  3. Push the reduced bin into a rolling deque of at most 10 bins.
 *  4. Once the deque is full (10 seconds of history) emit a prediction. Every subsequent
 *     bin advance produces one more prediction, at 1 Hz.
 *
 * Between the 1 Hz predictions, callers should propagate heading with gyro through the
 * fusion EKF (that path already exists in [nisargpatel.deadreckoning.data.LiveNavigationRepository]),
 * and coast speed on the model's last estimate.
 *
 * ## Contract check
 *
 * The manifest carries a `preprocessing_version` string. The engine refuses to load if
 * that string does not match [PreprocessingSpec.PINO_V3.version]. This mirrors the guard
 * already present in [IdrMotionEngine] and is exactly the check that would have caught
 * the windowing regression the moment it shipped.
 */
class PinoDrMotionEngine(
    context: Context,
    private val spec: PreprocessingSpec = PreprocessingSpec.PINO_V3
) : AutoCloseable {

    companion object {
        private const val TAG = "PinoDrEngine"
        private const val MODEL_ASSET = "ml/v3_pino_dr.onnx"
        private const val MANIFEST_ASSET = "ml/v3_pino_manifest.json"

        // Physical clamps applied to every raw sample before averaging. Matches the
        // training pipeline's clamping bounds.
        private const val CLIP_ACCEL_MIN = -8.0f
        private const val CLIP_ACCEL_MAX = 8.0f
        private const val CLIP_GYRO_MIN = -1.0f
        private const val CLIP_GYRO_MAX = 1.0f
        private const val CLIP_V_PREV_MIN = 0.0f
        private const val CLIP_V_PREV_MAX = 45.0f

        // Output clamps.
        private const val CLIP_DISP_MIN = 0.0f
        private const val CLIP_DISP_MAX = 45.0f
        private const val CLIP_YAW_MIN = -1.2f
        private const val CLIP_YAW_MAX = 1.2f

        // MinMax scaler constants baked from scalers_v3.pkl. Identical every timestep, so
        // one entry per channel is enough. See v3_pino_scalers.json for the full array.
        private const val S_A_FWD_SCALE = 0.076441556f
        private const val S_A_FWD_MIN = 0.52882564f

        private const val S_W_YAW_SCALE = 0.5f
        private const val S_W_YAW_MIN = 0.5f

        private const val S_A_LAT_SCALE = 0.083676726f
        private const val S_A_LAT_MIN = 0.5240971f

        private const val S_V_PREV_SCALE = 0.022222223f // 1.0 / 45.0
        private const val S_V_PREV_MIN = 0.0f

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
    }

    /** Model-facing rate: predictions are emitted at this Hz. */
    val predictionHz: Double get() = spec.predictionHz

    /** Seconds represented by one bin, i.e. one model timestep. */
    private val binSeconds: Float = 1.0f / spec.sampleRateHz.toFloat()

    /** Raw sensor samples per bin. Ten at 10 Hz becomes one 1 Hz averaged bin. */
    private val rawSamplesPerBin: Int =
        PreprocessingSpec.PINO_V3_RAW_SAMPLE_RATE_HZ / spec.sampleRateHz

    private val windowSize: Int = spec.windowSamples
    private val channelCount: Int = spec.channelCount

    /** Minimum wall clock between accepted raw samples, in nanoseconds. */
    private val rawSampleIntervalNs: Long =
        1_000_000_000L / PreprocessingSpec.PINO_V3_RAW_SAMPLE_RATE_HZ

    /** Slack applied to the raw-sample gate to tolerate a slightly early sensor callback. */
    private val rawSampleGuardNs: Long = (rawSampleIntervalNs * 4) / 5

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    val manifest: PinoManifest

    /** Accumulator for the current one-second bin. Cleared when the bin is emitted. */
    private val binAccumulator = FloatArray(4)
    private var samplesInCurrentBin: Int = 0

    /** Rolling deque of the last [windowSize] one-second bins. */
    private val window = ArrayDeque<FloatArray>(windowSize)
    private var lastAcceptedTimestampNs: Long = 0L

    // Engine persistent state.
    private var currentVelocityMps: Float = 0.0f
    private var isStopped: Boolean = false
    private var zuptHighCount: Int = 0
    private var zuptLowCount: Int = 0

    init {
        require(spec.channelCount == 4) {
            "PINO-DR v3 expects 4 channels but spec has ${spec.channelCount}"
        }
        require(rawSamplesPerBin >= 1) {
            "raw sample rate ${PreprocessingSpec.PINO_V3_RAW_SAMPLE_RATE_HZ} Hz cannot bin to " +
                "model rate ${spec.sampleRateHz} Hz"
        }

        manifest = context.assets.open(MANIFEST_ASSET).bufferedReader().use {
            Gson().fromJson(it, PinoManifest::class.java)
        }

        // The single check that would have caught the previous windowing regression the
        // moment it shipped. Divergence between training and runtime preprocessing is
        // exactly the defect that made the V8 artifact unusable; do not let it happen
        // again for PINO.
        require(manifest.preprocessing_version == spec.version) {
            "PINO manifest is for '${manifest.preprocessing_version}' but this build " +
                "expects '${spec.version}'. Refusing to load; the runtime and training " +
                "preprocessing pipelines would silently disagree."
        }
        require(manifest.window_size == windowSize) {
            "PINO manifest window_size ${manifest.window_size} does not match runtime $windowSize"
        }
        // sample_rate_hz in the manifest is the MODEL-facing rate, so it must be the same
        // as the spec's sampleRateHz. The RAW rate is checked separately.
        val binRate = if (manifest.bin_sample_rate_hz > 0.0) manifest.bin_sample_rate_hz
        else 1.0 / manifest.bin_seconds.coerceAtLeast(1e-9)
        require(kotlin.math.abs(binRate - spec.sampleRateHz.toDouble()) < 1e-6) {
            "PINO manifest bin rate $binRate Hz does not match runtime ${spec.sampleRateHz} Hz"
        }
        require(kotlin.math.abs(manifest.raw_sample_rate_hz -
            PreprocessingSpec.PINO_V3_RAW_SAMPLE_RATE_HZ.toDouble()) < 1e-6) {
            "PINO manifest raw_sample_rate_hz ${manifest.raw_sample_rate_hz} does not match " +
                "runtime ${PreprocessingSpec.PINO_V3_RAW_SAMPLE_RATE_HZ}"
        }

        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        if (manifest.sha256.isNotBlank()) {
            val digest = MessageDigest.getInstance("SHA-256")
            val computedHash = digest.digest(modelBytes).joinToString("") { "%02x".format(it) }
            require(computedHash.equals(manifest.sha256, ignoreCase = true)) {
                "PINO-DR v3 model file integrity check failed! Expected sha256: ${manifest.sha256}, computed: $computedHash"
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
     * @param aFwd vehicle forward acceleration, m/s^2. When vehicle-frame alignment is
     *   not available, the caller passes a phone-frame proxy and accepts the resulting
     *   error until [nisargpatel.deadreckoning.fusion.VehicleAlignmentCalibrator] converges.
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

        // Accumulate into the current bin instead of pushing the raw sample straight in.
        // This is the one change that most of the fix depends on.
        binAccumulator[0] += clampedFwd
        binAccumulator[1] += clampedYaw
        binAccumulator[2] += clampedLat
        binAccumulator[3] += vPrev
        samplesInCurrentBin++

        if (samplesInCurrentBin < rawSamplesPerBin) return null

        // Close out the bin: replace the running sum with its mean and hand it to the
        // window.
        val bin = FloatArray(4)
        val inverse = 1.0f / rawSamplesPerBin
        bin[0] = binAccumulator[0] * inverse
        bin[1] = binAccumulator[1] * inverse
        bin[2] = binAccumulator[2] * inverse
        bin[3] = binAccumulator[3] * inverse

        binAccumulator.fill(0f)
        samplesInCurrentBin = 0

        if (window.size == windowSize) window.removeFirst()
        window.addLast(bin)

        if (window.size < windowSize) return null
        return runInference()
    }

    private fun runInference(): PinoPrediction {
        val startNs = System.nanoTime()

        // Build normalised input tensor [1, windowSize, 4].
        val inputFlat = FloatArray(windowSize * channelCount)
        var offset = 0
        for (bin in window) {
            inputFlat[offset]     = bin[0] * S_A_FWD_SCALE  + S_A_FWD_MIN
            inputFlat[offset + 1] = bin[1] * S_W_YAW_SCALE  + S_W_YAW_MIN
            inputFlat[offset + 2] = bin[2] * S_A_LAT_SCALE  + S_A_LAT_MIN
            inputFlat[offset + 3] = bin[3] * S_V_PREV_SCALE + S_V_PREV_MIN
            offset += channelCount
        }

        val inputTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(inputFlat),
            longArrayOf(1, windowSize.toLong(), channelCount.toLong())
        )

        val outputs = session.run(mapOf("imu_window" to inputTensor))
        inputTensor.close()

        val dispTensor = outputs[0].value as Array<FloatArray>
        val oriTensor = outputs[1].value as Array<FloatArray>
        val zuptTensor = outputs[2].value as Array<FloatArray>

        val dPredScaled = dispTensor[0][0]
        val oPredScaled = oriTensor[0][0]
        val zuptLogit = zuptTensor[0][0]

        outputs.close()

        // Inverse-scale to physical units. Clamp so an out-of-distribution logit cannot
        // propagate a non-finite step into the EKF.
        var rawVelocity = (dPredScaled / S_Y_DISP_SCALE)
            .coerceIn(CLIP_DISP_MIN, CLIP_DISP_MAX)
        var rawYawRate = ((oPredScaled - S_Y_ORI_MIN) / S_Y_ORI_SCALE)
            .coerceIn(CLIP_YAW_MIN, CLIP_YAW_MAX)
        val pStop = 1.0f / (1.0f + exp(-zuptLogit))

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
            inferenceTimeMs = max(1L, elapsedMs)
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
    }

    override fun close() {
        session.close()
    }
}
