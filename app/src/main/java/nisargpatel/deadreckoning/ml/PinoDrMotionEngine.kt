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
import kotlin.math.max
import kotlin.math.min

/**
 * Result from a PINO-DR v3 inference step.
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
    val inferenceTimeMs: Long
)

data class PinoManifest(
    val model: String = "PINO-DR v3",
    val architecture: String = "Conv1D-BiGRU-TemporalAttention",
    val deployment_status: String = "PINO-DR v3 Production",
    val parameters: Int = 21667,
    val window_size: Int = 10,
    val zupt_threshold: Float = 0.70f
)

/**
 * Production inference engine for PINO-DR v3 (Physics-Informed Neural Operator for Dead Reckoning).
 *
 * Implements:
 * 1. 4-Channel temporal sequence: [a_fwd, w_yaw, a_lat, v_prev]
 * 2. MinMax per-channel normalization matching trained weights in checkpoints_v3
 * 3. Multi-task output heads: Displacement (velocity delta with residual skip), Orientation (yaw rate), ZUPT (stop logit)
 * 4. ZUPT hysteresis gate: eliminates standstill straight-line drift and speed inflation
 */
class PinoDrMotionEngine(
    context: Context,
    private val windowSize: Int = 10,
    private val strideSamples: Int = 2 // 5 Hz update rate from 10 Hz sensor stream
) : AutoCloseable {

    companion object {
        private const val TAG = "PinoDrEngine"
        private const val MODEL_ASSET = "ml/v3_pino_dr.onnx"
        private const val MANIFEST_ASSET = "ml/v3_pino_manifest.json"

        // Clamping bounds from v3 metadata
        private const val CLIP_ACCEL_MIN = -8.0f
        private const val CLIP_ACCEL_MAX = 8.0f
        private const val CLIP_GYRO_MIN = -1.0f
        private const val CLIP_GYRO_MAX = 1.0f
        private const val CLIP_DISP_MIN = 0.0f
        private const val CLIP_DISP_MAX = 45.0f
        private const val CLIP_YAW_MIN = -1.2f
        private const val CLIP_YAW_MAX = 1.2f

        // Scaler constants extracted from trained scalers_v3.pkl
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

        // ZUPT hysteresis parameters
        private const val ZUPT_HIGH_THRESH = 0.70f
        private const val ZUPT_LOW_THRESH = 0.30f
        private const val ZUPT_N_ENTER = 3
        private const val ZUPT_N_EXIT = 2
    }

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    val manifest: PinoManifest

    private val window = ArrayDeque<FloatArray>(windowSize)
    private var samplesSincePrediction = 0
    private var lastAcceptedTimestampNs = 0L

    // Engine persistent state
    private var currentVelocityMps = 0.0f
    private var isStopped = false
    private var zuptHighCount = 0
    private var zuptLowCount = 0

    init {
        manifest = runCatching {
            val json = context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
            Gson().fromJson(json, PinoManifest::class.java)
        }.getOrElse { PinoManifest() }

        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        session = environment.createSession(modelBytes, options)
        Log.i(TAG, "PINO-DR v3 ONNX engine loaded successfully (${manifest.model}, ${manifest.parameters} params)")
    }

    /**
     * Feeds a single IMU sample (aligned to vehicle frame: forward accel, yaw gyro, lateral accel).
     *
     * @param timestampNs sample timestamp
     * @param aFwd vehicle forward acceleration (m/s²)
     * @param wYaw vehicle yaw rate around vertical (rad/s)
     * @param aLat vehicle lateral acceleration (m/s²)
     * @param seedVelocityMps current velocity hint or GNSS velocity (m/s)
     */
    fun addSample(
        timestampNs: Long,
        aFwd: Float,
        wYaw: Float,
        aLat: Float,
        seedVelocityMps: Float
    ): PinoPrediction? {
        // Enforce 10 Hz decimation (100 ms between samples)
        if (lastAcceptedTimestampNs != 0L && (timestampNs - lastAcceptedTimestampNs) < 80_000_000L) {
            return null
        }
        lastAcceptedTimestampNs = timestampNs

        // Physical clamping
        val clampedFwd = aFwd.coerceIn(CLIP_ACCEL_MIN, CLIP_ACCEL_MAX)
        val clampedYaw = wYaw.coerceIn(CLIP_GYRO_MIN, CLIP_GYRO_MAX)
        val clampedLat = aLat.coerceIn(CLIP_ACCEL_MIN, CLIP_ACCEL_MAX)
        val vPrev = if (seedVelocityMps > 0f) seedVelocityMps else currentVelocityMps

        if (window.size == windowSize) {
            window.removeFirst()
        }
        window.addLast(floatArrayOf(clampedFwd, clampedYaw, clampedLat, vPrev))

        samplesSincePrediction++
        if (window.size < windowSize || samplesSincePrediction < strideSamples) {
            return null
        }
        samplesSincePrediction = 0

        return runInference()
    }

    private fun runInference(): PinoPrediction {
        val startNs = System.nanoTime()

        // Build normalized input tensor [1, 10, 4]
        val inputFlat = FloatArray(windowSize * 4)
        var offset = 0
        for (sample in window) {
            inputFlat[offset] = sample[0] * S_A_FWD_SCALE + S_A_FWD_MIN
            inputFlat[offset + 1] = sample[1] * S_W_YAW_SCALE + S_W_YAW_MIN
            inputFlat[offset + 2] = sample[2] * S_A_LAT_SCALE + S_A_LAT_MIN
            inputFlat[offset + 3] = sample[3] * S_V_PREV_SCALE + S_V_PREV_MIN
            offset += 4
        }

        val inputTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(inputFlat),
            longArrayOf(1, windowSize.toLong(), 4)
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

        // Inverse scaling
        var rawVelocity = (dPredScaled / S_Y_DISP_SCALE).coerceIn(CLIP_DISP_MIN, CLIP_DISP_MAX)
        var rawYawRate = ((oPredScaled - S_Y_ORI_MIN) / S_Y_ORI_SCALE).coerceIn(CLIP_YAW_MIN, CLIP_YAW_MAX)
        val pStop = 1.0f / (1.0f + exp(-zuptLogit))

        // ZUPT Hysteresis Gate
        if (!isStopped) {
            if (pStop > ZUPT_HIGH_THRESH) {
                zuptHighCount++
                zuptLowCount = 0
                if (zuptHighCount >= ZUPT_N_ENTER) {
                    isStopped = true
                }
            } else {
                zuptHighCount = 0
            }
        } else {
            if (pStop < ZUPT_LOW_THRESH) {
                zuptLowCount++
                zuptHighCount = 0
                if (zuptLowCount >= ZUPT_N_EXIT) {
                    isStopped = false
                }
            } else {
                zuptLowCount = 0
            }
        }

        if (isStopped || rawVelocity < 0.4f) {
            rawVelocity = 0.0f
            rawYawRate = 0.0f
        }

        currentVelocityMps = rawVelocity

        // Stride displacement for this integration step (strideSamples * 0.1s = 0.2s)
        val stepIntervalSec = (strideSamples * 0.1f)
        val stepForwardMeters = rawVelocity * stepIntervalSec
        val stepHeadingDeltaRadians = rawYawRate * stepIntervalSec

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
            inferenceTimeMs = max(1L, elapsedMs)
        )
    }

    fun reset() {
        window.clear()
        samplesSincePrediction = 0
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
