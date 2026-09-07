package nisargpatel.deadreckoning.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.nio.FloatBuffer
import java.util.ArrayDeque
import kotlin.math.exp
import nisargpatel.deadreckoning.core.spec.PreprocessingSpec

/**
 * One IDR-V1 prediction over a window of IMU samples.
 *
 * Uncertainties come from the network's own log-variance heads, which are trained here
 * rather than exported untrained as the previous model's were.
 */
data class IdrPrediction(
    val speedMps: Float,
    val accelerationMps2: Float,
    val forwardMeters: Float,
    val lateralMeters: Float,
    val headingDeltaRadians: Float,
    val motionClass: MotionClass,
    val motionConfidencePercentage: Int,
    val inferenceTimeMs: Long,
    val speedUncertaintyMps: Float,
    val accelerationUncertaintyMps2: Float,
    val forwardUncertaintyMeters: Float,
    val lateralUncertaintyMeters: Float,
    val headingUncertaintyRadians: Float
)

/**
 * Normalisation for the IDR-V1 contract.
 *
 * Note `position_scale` with no mean offset. Displacement is scaled but never centred: a
 * centred displacement target is what let the previous artifact imply a negative mean
 * forward travel, so that a model regressing toward its target mean emitted a systematic
 * backwards-and-sideways bias every window. Zero must mean zero motion.
 */
private data class IdrNormalization(
    val preprocessing_version: String,
    val imu_mean: List<Float>,
    val imu_std: List<Float>,
    val speed_mean: Float,
    val speed_std: Float,
    val acceleration_mean: Float,
    val acceleration_std: Float,
    val position_scale: List<Float>
)

/** Deployment metadata for the packaged IDR-V1 artifact. */
data class IdrManifest(
    val model: String,
    val preprocessing_version: String,
    val parameters: Int,
    val sha256: String = ""
)

/**
 * Runs the IDR-V1 motion model.
 *
 * ## Contract
 *
 * Six channels: linear acceleration in device axes with gravity removed, then the three
 * gyro channels in dataset order. Gravity is removed with the platform TYPE_GRAVITY vector
 * because that is what the training data used, rather than a locally filtered estimate.
 *
 * ## Why this replaced V8
 *
 * Measured on a held-out session from an unseen driver and vehicle, integrating each model
 * open loop through simulated GNSS blackouts:
 *
 *     blackout   IDR-V1        V8          persistence
 *     10 s        14.4 m       84.5 m       21.5 m
 *     30 s        92.9 m      536.6 m      159.4 m
 *     60 s       260.3 m     1092.6 m      435.9 m
 *
 * V8 was worse than assuming constant speed with no turning, so it was actively harmful.
 * IDR-V1 beats it by roughly five times and also beats the no-network baseline, in a model
 * of 81,581 parameters and 328 KiB rather than 985,195 and 4.06 MB.
 */
class IdrMotionEngine(
    context: Context,
    private val spec: PreprocessingSpec = PreprocessingSpec.IDR_V1
) : AutoCloseable {
    companion object {
        private const val TAG = "IdrMotion"
        private const val CHANNEL_COUNT = 6
        private const val MODEL_ASSET = "ml/idr_v1.onnx"
        private const val NORMALIZATION_ASSET = "ml/idr_v1_normalization.json"
        private const val MANIFEST_ASSET = "ml/idr_v1_manifest.json"
    }

    private val windowSize = spec.windowSamples
    private val strideSamples = spec.strideSamples
    private val sampleIntervalNs = 1_000_000_000L / spec.sampleRateHz

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val normalization: IdrNormalization
    val manifest: IdrManifest

    private val window = ArrayDeque<FloatArray>(windowSize)
    private var lastAcceptedTimestampNs = 0L
    private var samplesSincePrediction = 0

    init {
        val gson = Gson()
        normalization = context.assets.open(NORMALIZATION_ASSET).reader().use {
            gson.fromJson(it, IdrNormalization::class.java)
        }
        manifest = context.assets.open(MANIFEST_ASSET).reader().use {
            gson.fromJson(it, IdrManifest::class.java)
        }

        // Refuse to run a model whose preprocessing does not match this build's contract.
        // Silent divergence between training and runtime preprocessing is what made the
        // previous artifact unusable.
        require(normalization.preprocessing_version == spec.version) {
            "Normalisation is for '${normalization.preprocessing_version}' but this build " +
                "expects '${spec.version}'"
        }
        require(normalization.imu_mean.size == CHANNEL_COUNT) {
            "Expected $CHANNEL_COUNT channel means, got ${normalization.imu_mean.size}"
        }

        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        if (manifest.sha256.isNotBlank()) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val computedHash = digest.digest(model).joinToString("") { "%02x".format(it) }
            require(computedHash.equals(manifest.sha256, ignoreCase = true)) {
                "IDR-V1 model file integrity check failed! Expected sha256: ${manifest.sha256}, computed: $computedHash"
            }
        }
        session = environment.createSession(model, OrtSession.SessionOptions())
        Log.i(TAG, "Loaded ${manifest.model} (${manifest.parameters} params)")
        Log.i(TAG, "Contract: ${spec.describe()}")
    }

    /**
     * Feed one IMU sample.
     *
     * @param timestampNs sensor event clock, the single timebase used across the pipeline.
     * @param gravity platform gravity in device axes. Required: without it the contract's
     *   gravity removal cannot be reproduced and the model would see a distribution it was
     *   never trained on, so the sample is refused instead.
     * @return a prediction when a full window has advanced by the contract's stride.
     */
    fun addSample(
        timestampNs: Long,
        accelX: Float,
        accelY: Float,
        accelZ: Float,
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float,
        gravity: FloatArray?,
        initialSpeedMps: Float
    ): IdrPrediction? {
        if (gravity == null || gravity.size < 3) return null
        if (lastAcceptedTimestampNs != 0L && timestampNs - lastAcceptedTimestampNs < sampleIntervalNs) {
            return null
        }
        lastAcceptedTimestampNs = timestampNs

        val raw = floatArrayOf(
            accelX - gravity[0],
            accelY - gravity[1],
            accelZ - gravity[2],
            gyroX,
            gyroY,
            gyroZ
        )
        val normalized = FloatArray(CHANNEL_COUNT) { index ->
            (raw[index] - normalization.imu_mean[index]) / normalization.imu_std[index]
        }

        if (window.size == windowSize) window.removeFirst()
        window.addLast(normalized)
        samplesSincePrediction++

        if (window.size < windowSize || samplesSincePrediction < strideSamples) return null
        samplesSincePrediction = 0
        return infer(initialSpeedMps)
    }

    private fun infer(initialSpeedMps: Float): IdrPrediction {
        val startedAt = System.nanoTime()

        val imu = FloatArray(windowSize * CHANNEL_COUNT)
        window.forEachIndexed { index, sample -> sample.copyInto(imu, index * CHANNEL_COUNT) }

        val normalizedSpeed =
            (initialSpeedMps - normalization.speed_mean) / normalization.speed_std

        val imuTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(imu),
            longArrayOf(1, windowSize.toLong(), CHANNEL_COUNT.toLong())
        )
        val speedTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(floatArrayOf(normalizedSpeed)),
            longArrayOf(1)
        )

        imuTensor.use { input ->
            speedTensor.use { state ->
                session.run(mapOf("imu" to input, "initial_speed_normalized" to state)).use { output ->
                    val speedNormalized = scalar(output, "speed")
                    val accelerationNormalized = scalar(output, "acceleration")
                    val position = pair(output, "position")
                    val headingDelta = scalar(output, "heading_delta")
                    val logits = (output["motion_logits"]!!.get().value as Array<FloatArray>)[0]

                    val probabilities = softmax(logits)
                    val motionIndex = probabilities.indices.maxBy { probabilities[it] }

                    val speedSigma = sigma(output, "speed_log_variance") * normalization.speed_std
                    val accelerationSigma =
                        sigma(output, "acceleration_log_variance") * normalization.acceleration_std
                    val positionSigma = sigmaPair(output, "position_log_variance")

                    val prediction = IdrPrediction(
                        speedMps = (speedNormalized * normalization.speed_std + normalization.speed_mean)
                            .coerceAtLeast(0f),
                        accelerationMps2 = accelerationNormalized * normalization.acceleration_std +
                            normalization.acceleration_mean,
                        // Scale only, no mean offset, by contract.
                        forwardMeters = position[0] * normalization.position_scale[0],
                        lateralMeters = position[1] * normalization.position_scale[1],
                        headingDeltaRadians = headingDelta,
                        motionClass = MotionClass.fromIndex(motionIndex),
                        motionConfidencePercentage = (probabilities[motionIndex] * 100).toInt().coerceIn(0, 100),
                        inferenceTimeMs = (System.nanoTime() - startedAt) / 1_000_000L,
                        speedUncertaintyMps = speedSigma,
                        accelerationUncertaintyMps2 = accelerationSigma,
                        forwardUncertaintyMeters = positionSigma.first * normalization.position_scale[0],
                        lateralUncertaintyMeters = positionSigma.second * normalization.position_scale[1],
                        headingUncertaintyRadians = sigma(output, "heading_delta_log_variance")
                    )

                    Log.d(
                        TAG,
                        "speed=${prediction.speedMps * 3.6f} km/h +/-${prediction.speedUncertaintyMps * 3.6f} " +
                            "accel=${prediction.accelerationMps2} fwd=${prediction.forwardMeters} " +
                            "hdg=${prediction.headingDeltaRadians} ${prediction.inferenceTimeMs} ms"
                    )
                    return prediction
                }
            }
        }
    }

    private fun scalar(output: OrtSession.Result, name: String): Float =
        (output[name]!!.get().value as FloatArray)[0]

    private fun pair(output: OrtSession.Result, name: String): FloatArray =
        (output[name]!!.get().value as Array<FloatArray>)[0]

    /** Standard deviation from a log-variance head, clamped so an outlier cannot overflow. */
    private fun sigma(output: OrtSession.Result, name: String): Float {
        val value = runCatching { scalar(output, name) }.getOrNull() ?: return 0f
        if (!value.isFinite()) return 0f
        return exp(0.5 * value.coerceIn(-20f, 10f).toDouble()).toFloat()
    }

    private fun sigmaPair(output: OrtSession.Result, name: String): Pair<Float, Float> {
        val values = runCatching { pair(output, name) }.getOrNull() ?: return 0f to 0f
        fun convert(value: Float): Float {
            if (!value.isFinite()) return 0f
            return exp(0.5 * value.coerceIn(-20f, 10f).toDouble()).toFloat()
        }
        return convert(values.getOrElse(0) { 0f }) to convert(values.getOrElse(1) { 0f })
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val highest = logits.maxOrNull() ?: 0f
        val unnormalized = FloatArray(logits.size) { exp((logits[it] - highest).toDouble()).toFloat() }
        val total = unnormalized.sum().coerceAtLeast(0.0001f)
        return FloatArray(logits.size) { unnormalized[it] / total }
    }

    override fun close() = session.close()
}
