package nisargpatel.deadreckoning.support

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import java.nio.FloatBuffer
import java.util.ArrayDeque
import kotlin.math.exp
import kotlin.math.max
import nisargpatel.deadreckoning.core.spec.PreprocessingSpec

/**
 * JVM-side PINO-DR v3 runner. Mirrors [nisargpatel.deadreckoning.ml.PinoDrMotionEngine]
 * but loads the shipped ONNX artifact through the desktop `onnxruntime` binary so it
 * can be exercised in a plain unit test.
 *
 * The point of duplicating the windowing logic here rather than instantiating the real
 * engine is exactly the point of [IdrOnnxModel]: the shipped artifact is loaded from
 * the packaged `src/main/assets/ml` directory, so the test cannot drift away from what
 * actually ships. If the two windowing implementations disagree, either the test or
 * the runtime is wrong, and either is worth catching.
 *
 * The engine and this runner share:
 *  - The manifest asset (`v3_pino_manifest.json`), including its `preprocessing_version`
 *    guard against training/runtime drift.
 *  - The clamping bounds and MinMax scaler constants.
 *  - The bin-averaging schedule: 10 raw samples become one 1 Hz bin, and 10 bins fill
 *    a 10-second rolling window before the first prediction is emitted.
 *
 * They deliberately do NOT share the ZUPT hysteresis: the ablation cares about raw
 * open-loop drift, so speed and yaw are trusted as reported without hysteresis
 * masking. If the trained model wants to emit a low speed it does; the outer ablation
 * loop is what plays the role of the on-device ZUPT gate.
 */
class PinoOnnxRunner(
    private val spec: PreprocessingSpec = PreprocessingSpec.PINO_V3
) : AutoCloseable {

    private companion object {
        // Physical clamps applied to every raw sample before averaging. Matches the
        // training pipeline.
        const val CLIP_ACCEL_MIN = -8.0f
        const val CLIP_ACCEL_MAX = 8.0f
        const val CLIP_GYRO_MIN = -1.0f
        const val CLIP_GYRO_MAX = 1.0f
        const val CLIP_V_PREV_MIN = 0.0f
        const val CLIP_V_PREV_MAX = 45.0f
        const val CLIP_DISP_MIN = 0.0f
        const val CLIP_DISP_MAX = 45.0f
        const val CLIP_YAW_MIN = -1.2f
        const val CLIP_YAW_MAX = 1.2f

        const val S_A_FWD_SCALE = 0.076441556f
        const val S_A_FWD_MIN = 0.52882564f
        const val S_W_YAW_SCALE = 0.5f
        const val S_W_YAW_MIN = 0.5f
        const val S_A_LAT_SCALE = 0.083676726f
        const val S_A_LAT_MIN = 0.5240971f
        const val S_V_PREV_SCALE = 0.022222223f
        const val S_V_PREV_MIN = 0.0f
        const val S_Y_DISP_SCALE = 0.022222223f
        const val S_Y_ORI_SCALE = 0.49350372f
        const val S_Y_ORI_MIN = 0.504565f
    }

    data class Manifest(
        val model: String = "",
        val architecture: String = "",
        val deployment_status: String = "",
        val preprocessing_version: String = "",
        val parameters: Int = 0,
        val raw_sample_rate_hz: Double = 0.0,
        val bin_seconds: Double = 0.0,
        val bin_sample_rate_hz: Double = 0.0,
        val window_size: Int = 0,
        val window_seconds: Double = 0.0,
        val prediction_hz: Double = 0.0,
        val zupt_threshold: Float = 0.70f
    )

    /**
     * Result of one PINO inference over the last one-second bin.
     */
    data class Prediction(
        val speedMps: Float,
        val yawRateRadPerSec: Float,
        val zuptProbability: Float,
        val stepForwardMeters: Float,
        val stepHeadingDeltaRadians: Float,
        val stepIntervalSeconds: Float,
        val inferenceTimeMs: Long
    )

    val manifest: Manifest
    val preprocessingVersion: String get() = manifest.preprocessing_version

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val rawSamplesPerBin: Int =
        PreprocessingSpec.PINO_V3_RAW_SAMPLE_RATE_HZ / spec.sampleRateHz
    private val binSeconds: Float = 1.0f / spec.sampleRateHz.toFloat()

    private val binAccumulator = FloatArray(4)
    private var samplesInCurrentBin = 0
    private val window = ArrayDeque<FloatArray>(spec.windowSamples)

    private var currentVelocityMps = 0.0f

    init {
        require(spec.channelCount == 4) { "PINO expects 4 channels, got ${spec.channelCount}" }
        manifest = Gson().fromJson(
            locateAsset("src/main/assets/ml/v3_pino_manifest.json").readText(),
            Manifest::class.java
        )

        // The same guard PinoDrMotionEngine applies at construction. Duplicating it
        // here is intentional: a manifest change that skipped one side would still be
        // caught by the other.
        require(manifest.preprocessing_version == spec.version) {
            "PINO manifest is for '${manifest.preprocessing_version}' but this test " +
                "expects '${spec.version}'"
        }
        require(manifest.window_size == spec.windowSamples) {
            "manifest window_size ${manifest.window_size} != spec ${spec.windowSamples}"
        }
        val binRate =
            if (manifest.bin_sample_rate_hz > 0.0) manifest.bin_sample_rate_hz
            else 1.0 / manifest.bin_seconds.coerceAtLeast(1e-9)
        require(kotlin.math.abs(binRate - spec.sampleRateHz.toDouble()) < 1e-6) {
            "manifest bin rate $binRate Hz != spec ${spec.sampleRateHz} Hz"
        }
        require(kotlin.math.abs(manifest.raw_sample_rate_hz -
            PreprocessingSpec.PINO_V3_RAW_SAMPLE_RATE_HZ.toDouble()) < 1e-6) {
            "manifest raw_sample_rate_hz ${manifest.raw_sample_rate_hz} != runtime " +
                "${PreprocessingSpec.PINO_V3_RAW_SAMPLE_RATE_HZ}"
        }

        session = environment.createSession(
            locateAsset("src/main/assets/ml/v3_pino_dr.onnx").readBytes(),
            OrtSession.SessionOptions()
        )
    }

    /**
     * Feed one raw 10 Hz sample. Returns a prediction once every `rawSamplesPerBin`
     * samples land AND the 10-bin window has filled.
     *
     * @param seedVelocityMps prior velocity hint. Passing `-1f` means "use the engine's
     *   own last output", which is what runtime does when GNSS is silent.
     */
    fun addSample(
        aFwd: Float,
        wYaw: Float,
        aLat: Float,
        seedVelocityMps: Float
    ): Prediction? {
        val clampedFwd = aFwd.coerceIn(CLIP_ACCEL_MIN, CLIP_ACCEL_MAX)
        val clampedYaw = wYaw.coerceIn(CLIP_GYRO_MIN, CLIP_GYRO_MAX)
        val clampedLat = aLat.coerceIn(CLIP_ACCEL_MIN, CLIP_ACCEL_MAX)
        val vPrev = (if (seedVelocityMps >= 0f) seedVelocityMps else currentVelocityMps)
            .coerceIn(CLIP_V_PREV_MIN, CLIP_V_PREV_MAX)

        binAccumulator[0] += clampedFwd
        binAccumulator[1] += clampedYaw
        binAccumulator[2] += clampedLat
        binAccumulator[3] += vPrev
        samplesInCurrentBin++

        if (samplesInCurrentBin < rawSamplesPerBin) return null

        val inverse = 1.0f / rawSamplesPerBin
        val bin = FloatArray(4)
        bin[0] = binAccumulator[0] * inverse
        bin[1] = binAccumulator[1] * inverse
        bin[2] = binAccumulator[2] * inverse
        bin[3] = binAccumulator[3] * inverse

        binAccumulator.fill(0f)
        samplesInCurrentBin = 0

        if (window.size == spec.windowSamples) window.removeFirst()
        window.addLast(bin)
        if (window.size < spec.windowSamples) return null

        return infer()
    }

    private fun infer(): Prediction {
        val startNs = System.nanoTime()
        val input = FloatArray(spec.windowSamples * spec.channelCount)
        var offset = 0
        for (bin in window) {
            input[offset]     = bin[0] * S_A_FWD_SCALE  + S_A_FWD_MIN
            input[offset + 1] = bin[1] * S_W_YAW_SCALE  + S_W_YAW_MIN
            input[offset + 2] = bin[2] * S_A_LAT_SCALE  + S_A_LAT_MIN
            input[offset + 3] = bin[3] * S_V_PREV_SCALE + S_V_PREV_MIN
            offset += spec.channelCount
        }

        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, spec.windowSamples.toLong(), spec.channelCount.toLong())
        )

        val (dispScaled, oriScaled, zuptLogit) = tensor.use { imu ->
            session.run(mapOf("imu_window" to imu)).use { outputs ->
                val disp = (outputs[0].value as Array<FloatArray>)[0][0]
                val ori = (outputs[1].value as Array<FloatArray>)[0][0]
                val zupt = (outputs[2].value as Array<FloatArray>)[0][0]
                Triple(disp, ori, zupt)
            }
        }

        val velocity = (dispScaled / S_Y_DISP_SCALE)
            .coerceIn(CLIP_DISP_MIN, CLIP_DISP_MAX)
        val yawRate = ((oriScaled - S_Y_ORI_MIN) / S_Y_ORI_SCALE)
            .coerceIn(CLIP_YAW_MIN, CLIP_YAW_MAX)
        val pStop = 1.0f / (1.0f + exp(-zuptLogit))

        currentVelocityMps = velocity

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        return Prediction(
            speedMps = velocity,
            yawRateRadPerSec = yawRate,
            zuptProbability = pStop,
            stepForwardMeters = velocity * binSeconds,
            stepHeadingDeltaRadians = yawRate * binSeconds,
            stepIntervalSeconds = binSeconds,
            inferenceTimeMs = max(1L, elapsedMs)
        )
    }

    /** Seed the engine's own velocity hint before beginning a blackout. */
    fun seedVelocity(velocityMps: Float) {
        currentVelocityMps = velocityMps.coerceIn(CLIP_V_PREV_MIN, CLIP_V_PREV_MAX)
    }

    fun reset() {
        window.clear()
        binAccumulator.fill(0f)
        samplesInCurrentBin = 0
        currentVelocityMps = 0.0f
    }

    override fun close() {
        session.close()
    }
}
