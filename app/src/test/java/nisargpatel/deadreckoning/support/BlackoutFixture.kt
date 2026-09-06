package nisargpatel.deadreckoning.support

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import nisargpatel.deadreckoning.data.RoadCandidate
import org.osmdroid.util.GeoPoint

/**
 * Held-out-session fixture for the GNSS blackout ablation.
 *
 * The data is produced by `.codex-ml-codes-review/export_test_predictions.py` from session
 * Y1 of IO-VNBD, which is an unseen driver in an unseen vehicle. Python owns ground truth
 * and the gyro calibration; this side owns inference and the estimator, so the measurement
 * exercises the real [nisargpatel.deadreckoning.fusion.VehicleFusionEkf] rather than a
 * re-implementation of it. A re-implementation is exactly how the V8 artifact came to have
 * training and runtime preprocessing that disagreed without anyone noticing.
 *
 * Raw IMU samples are shipped rather than finished predictions because the model consumes
 * its own previous speed estimate. Precomputing predictions would mean seeding every window
 * from ground-truth speed, which leaks truth into every step of a blackout.
 */
object BlackoutFixture {

    const val RESOURCE_JSON = "/blackout/predictions.json"
    const val RESOURCE_SAMPLES = "/blackout/samples.bin"

    /** Channels in samples.bin. */
    private const val SAMPLE_CHANNELS = 9
    private const val CONTRACT_CHANNELS = 6

    data class ModelPrediction(
        val forward: Double,
        val lateral: Double,
        val headingDelta: Double,
        val speed: Double,
        val speedSigma: Double? = null,
        val headingSigma: Double? = null
    )

    data class Window(
        val start: Int = 0,
        val end: Int = 0,
        val startLat: Double = 0.0,
        val startLon: Double = 0.0,
        val startHeadingDeg: Double = 0.0,
        val startSpeedMps: Double = 0.0,
        val endLat: Double = 0.0,
        val endLon: Double = 0.0,
        val endHeadingDeg: Double = 0.0,
        val endSpeedMps: Double = 0.0,
        val pathMeters: Double = 0.0,
        val gyroRates: List<Double> = emptyList(),
        /** Python-side reference prediction. Parity checking only, never the ablation. */
        val idrRef: ModelPrediction? = null,
        val v8Ref: ModelPrediction? = null
    )

    data class Contract(
        val preprocessing_version: String = "",
        val sample_rate_hz: Int = 10,
        val window_samples: Int = 20,
        val chain_stride: Int = 19,
        val window_span_seconds: Double = 1.9,
        val lag_samples: Int = 0,
        val correspondence: Double = 0.0
    )

    data class GyroCalibration(
        val channel: Int = 0,
        val channelName: String = "",
        val scale: Double = 1.0,
        val correlation: Double = 0.0,
        val medianHeadingError10sDeg: Double = 0.0
    )

    data class Way(
        val way_id: Long = 0,
        val name: String = "",
        /** `[latitude, longitude]` pairs. */
        val points: List<List<Double>> = emptyList()
    )

    data class Road(
        val note: String = "",
        val spacingMeters: Double = 0.0,
        val noiseSigmaMeters: Double = 0.0,
        val seed: Long = 0,
        val ways: List<Way> = emptyList()
    )

    data class SampleLayout(
        val file: String = "",
        val count: Int = 0,
        val channels: List<String> = emptyList()
    )

    data class Payload(
        val session: String = "",
        val contract: Contract = Contract(),
        val limitations: List<String> = emptyList(),
        val gyroCalibration: GyroCalibration = GyroCalibration(),
        val road: Road = Road(),
        val samples: SampleLayout = SampleLayout(),
        val windows: List<Window> = emptyList()
    )

    /** Parsed fixture plus the raw sample block, with window slicing per model contract. */
    class Data(val payload: Payload, private val samples: FloatArray) {

        val windows: List<Window> get() = payload.windows
        val windowSamples: Int get() = payload.contract.window_samples
        val spanSeconds: Double get() = payload.contract.window_span_seconds
        val sampleIntervalSeconds: Double get() = 1.0 / payload.contract.sample_rate_hz
        val totalRawSamples: Int get() = samples.size / SAMPLE_CHANNELS

        /**
         * Build one model input window.
         *
         * IDR-V1 wants linear acceleration, so channels 0..5 pass straight through. V8 was
         * trained with gravity present, so gravity is added back onto the accelerometer
         * channels. Feeding V8 the IDR-V1 input instead would hand it a distribution it
         * never saw and make the baseline comparison meaningless in V8's favour.
         */
        fun window(startSample: Int, contract: ModelContract): FloatArray {
            val out = FloatArray(windowSamples * CONTRACT_CHANNELS)
            for (step in 0 until windowSamples) {
                val base = (startSample + step) * SAMPLE_CHANNELS
                val target = step * CONTRACT_CHANNELS
                val gravityCompensation = if (contract == ModelContract.LEGACY_V8) 6 else -1
                for (channel in 0 until 3) {
                    val linear = samples[base + channel]
                    out[target + channel] =
                        if (gravityCompensation >= 0) linear + samples[base + gravityCompensation + channel]
                        else linear
                }
                for (channel in 3 until 6) {
                    out[target + channel] = samples[base + channel]
                }
            }
            return out
        }

        /**
         * Phone-frame proxies fed to PINO-DR v3 when vehicle-frame alignment is not
         * available at runtime, in the same order runtime picks them:
         * `[a_fwd = accelY, w_yaw = gyroZ, a_lat = accelX]`.
         *
         * This is deliberately a proxy rather than an oracle. The training pipeline used
         * CAN/ECU-derived vehicle-frame channels this side of the review cannot supply,
         * so the ablation faithfully reproduces what the phone would feed the model in
         * the field while alignment has not converged. Callers wanting a calibrated
         * comparison can substitute [rawGyro] with the yaw-rate channel identified by
         * [Payload.gyroCalibration].
         *
         * Linear acceleration channels have gravity removed by the exporter, matching
         * the runtime that strips gravity through the platform TYPE_GRAVITY vector.
         */
        fun pinoProxySample(sampleIndex: Int): FloatArray {
            require(sampleIndex in 0 until totalRawSamples) {
                "sample index $sampleIndex out of range [0, $totalRawSamples)"
            }
            val base = sampleIndex * SAMPLE_CHANNELS
            return floatArrayOf(
                samples[base + 1], // a_fwd  proxy: accelY (linear, gravity removed)
                samples[base + 5], // w_yaw  proxy: gyroZ
                samples[base + 0]  // a_lat  proxy: accelX (linear, gravity removed)
            )
        }

        /** Yaw-rate channel at `sampleIndex`, using the fixture's calibrated gyro column. */
        fun calibratedYawRate(sampleIndex: Int): Float {
            val calibration = payload.gyroCalibration
            val base = sampleIndex * SAMPLE_CHANNELS + 3 + calibration.channel
            return (samples[base] * calibration.scale).toFloat()
        }

        /** Yaw rate at every raw sample from `startSample`, inclusive, for `count` samples. */
        fun gyroYawRatesRaw(startSample: Int, count: Int, useCalibration: Boolean = true): DoubleArray {
            val out = DoubleArray(count)
            for (i in 0 until count) {
                out[i] = if (useCalibration) {
                    calibratedYawRate(startSample + i).toDouble()
                } else {
                    samples[(startSample + i) * SAMPLE_CHANNELS + 5].toDouble()
                }
            }
            return out
        }
    }

    fun load(): Data {
        val json = requireNotNull(BlackoutFixture::class.java.getResourceAsStream(RESOURCE_JSON)) {
            "Missing $RESOURCE_JSON. Regenerate it with " +
                "'python export_test_predictions.py' in .codex-ml-codes-review."
        }.reader().use { it.readText() }
        val payload = Gson().fromJson(json, Payload::class.java)

        val bytes = requireNotNull(BlackoutFixture::class.java.getResourceAsStream(RESOURCE_SAMPLES)) {
            "Missing $RESOURCE_SAMPLES. Regenerate it with " +
                "'python export_test_predictions.py' in .codex-ml-codes-review."
        }.use { it.readBytes() }

        // Big-endian float32, which is the JVM's native ByteBuffer order.
        val floats = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).asFloatBuffer().get(floats)

        val expected = payload.samples.count * SAMPLE_CHANNELS
        require(floats.size == expected) {
            "samples.bin holds ${floats.size} floats but the manifest declares $expected"
        }
        require(payload.windows.isNotEmpty()) { "fixture contains no windows" }
        return Data(payload, floats)
    }

    /**
     * Great-circle distance, used so position error never depends on either side's
     * flat-earth constant. The estimator uses 111,111 m per degree and the Python export
     * uses 6,371,000 * pi / 180; comparing in either frame would fold a 0.075 percent scale
     * discrepancy into the headline drift figure.
     */
    fun haversineMeters(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val radius = 6_371_008.8
        val deltaLat = Math.toRadians(toLat - fromLat)
        val deltaLon = Math.toRadians(toLon - fromLon)
        val a = sin(deltaLat / 2).let { it * it } +
            cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) * sin(deltaLon / 2).let { it * it }
        return 2 * radius * asin(min(1.0, sqrt(a)))
    }

    fun haversineMeters(from: GeoPoint, toLat: Double, toLon: Double): Double =
        haversineMeters(from.latitude, from.longitude, toLat, toLon)
}

/** Which preprocessing contract a model expects. */
enum class ModelContract { IDR_V1, LEGACY_V8 }

/** A source of per-window motion predictions. */
interface MotionModel : AutoCloseable {
    val label: String
    val contract: ModelContract
    fun predict(window: FloatArray, initialSpeedMps: Double): BlackoutFixture.ModelPrediction
    override fun close() {}
}

/** Resolves a shipped asset regardless of the working directory the test runner picked. */
internal fun locateAsset(relativePath: String): File {
    val roots = listOf("", "app/", "../app/", "../../app/")
    val found = roots.asSequence()
        .map { File("$it$relativePath") }
        .firstOrNull { it.isFile }
    return requireNotNull(found) {
        "Could not find $relativePath from working directory ${File(".").absolutePath}"
    }
}

/**
 * IDR-V1 running the artifact that is actually packaged in the APK.
 *
 * Loading `src/main/assets/ml/idr_v1.onnx` directly, rather than a copy under the training
 * tree, means this measurement cannot drift away from what ships.
 */
class IdrOnnxModel : MotionModel {
    override val label = "IDR-V1"
    override val contract = ModelContract.IDR_V1

    private data class Normalization(
        val preprocessing_version: String = "",
        val imu_mean: List<Float> = emptyList(),
        val imu_std: List<Float> = emptyList(),
        val speed_mean: Float = 0f,
        val speed_std: Float = 1f,
        val acceleration_mean: Float = 0f,
        val acceleration_std: Float = 1f,
        val position_scale: List<Float> = emptyList()
    )

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val normalization: Normalization

    private companion object {
        val OUTPUTS = setOf(
            "speed", "speed_log_variance", "position", "position_log_variance",
            "heading_delta", "heading_delta_log_variance"
        )
    }

    init {
        normalization = Gson().fromJson(
            locateAsset("src/main/assets/ml/idr_v1_normalization.json").readText(),
            Normalization::class.java
        )
        session = environment.createSession(
            locateAsset("src/main/assets/ml/idr_v1.onnx").readBytes(),
            OrtSession.SessionOptions()
        )
    }

    val preprocessingVersion: String get() = normalization.preprocessing_version

    override fun predict(window: FloatArray, initialSpeedMps: Double): BlackoutFixture.ModelPrediction {
        val normalized = FloatArray(window.size) { index ->
            val channel = index % 6
            (window[index] - normalization.imu_mean[channel]) / normalization.imu_std[channel]
        }
        val speedInput =
            (initialSpeedMps.toFloat() - normalization.speed_mean) / normalization.speed_std

        OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(normalized), longArrayOf(1, (window.size / 6).toLong(), 6)
        ).use { imu ->
            OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(floatArrayOf(speedInput)), longArrayOf(1)
            ).use { speed ->
                session.run(mapOf("imu" to imu, "initial_speed_normalized" to speed)).use { result ->
                    val position = (result["position"].get().value as Array<FloatArray>)[0]
                    val speedOut = (result["speed"].get().value as FloatArray)[0]
                    val speedLogVar = (result["speed_log_variance"].get().value as FloatArray)[0]
                    val heading = (result["heading_delta"].get().value as FloatArray)[0]
                    val headingLogVar =
                        (result["heading_delta_log_variance"].get().value as FloatArray)[0]

                    return BlackoutFixture.ModelPrediction(
                        // Scale only, no mean offset: zero displacement must mean zero motion.
                        forward = (position[0] * normalization.position_scale[0]).toDouble(),
                        lateral = (position[1] * normalization.position_scale[1]).toDouble(),
                        headingDelta = heading.toDouble(),
                        speed = (speedOut * normalization.speed_std + normalization.speed_mean)
                            .coerceAtLeast(0f).toDouble(),
                        speedSigma = (sigma(speedLogVar) * normalization.speed_std).toDouble(),
                        headingSigma = sigma(headingLogVar).toDouble()
                    )
                }
            }
        }
    }

    /** Identical clamp and conversion to IdrMotionEngine.sigma, so the phone agrees. */
    private fun sigma(logVariance: Float): Float {
        if (!logVariance.isFinite()) return 0f
        return exp(0.5 * logVariance.coerceIn(-20f, 10f).toDouble()).toFloat()
    }

    fun outputsPresent(): Boolean = session.outputNames.containsAll(OUTPUTS)

    override fun close() = session.close()
}

/**
 * The shipped V8 artifact, fed its own contract.
 *
 * V8 has no trained variance heads, so it reports no sigma. Its normalisation is used
 * verbatim, gravity included, so the baseline is genuinely V8-as-deployed rather than a
 * handicapped version of it.
 */
class V8OnnxModel : MotionModel {
    override val label = "V8"
    override val contract = ModelContract.LEGACY_V8

    private data class Normalization(
        val imu_mean: List<Float> = emptyList(),
        val imu_std: List<Float> = emptyList(),
        val speed_mean: Float = 0f,
        val speed_std: Float = 1f,
        val position_mean: List<Float> = emptyList(),
        val position_std: List<Float> = emptyList()
    )

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val normalization: Normalization

    init {
        normalization = Gson().fromJson(
            locateAsset("src/main/assets/ml/v8_normalization.json").readText(),
            Normalization::class.java
        )
        session = environment.createSession(
            locateAsset("src/main/assets/ml/v8_dead_reckoning.onnx").readBytes(),
            OrtSession.SessionOptions()
        )
    }

    override fun predict(window: FloatArray, initialSpeedMps: Double): BlackoutFixture.ModelPrediction {
        val normalized = FloatArray(window.size) { index ->
            val channel = index % 6
            (window[index] - normalization.imu_mean[channel]) / normalization.imu_std[channel]
        }
        val speedInput =
            (initialSpeedMps.toFloat() - normalization.speed_mean) / normalization.speed_std

        OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(normalized), longArrayOf(1, (window.size / 6).toLong(), 6)
        ).use { imu ->
            OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(floatArrayOf(speedInput)), longArrayOf(1)
            ).use { speed ->
                session.run(mapOf("imu" to imu, "initial_speed_normalized" to speed)).use { result ->
                    val position = (result["position"].get().value as Array<FloatArray>)[0]
                    val speedOut = (result["speed"].get().value as FloatArray)[0]
                    val heading = (result["heading_delta"].get().value as FloatArray)[0]
                    return BlackoutFixture.ModelPrediction(
                        forward = (position[0] * normalization.position_std[0] +
                            normalization.position_mean[0]).toDouble(),
                        lateral = (position[1] * normalization.position_std[1] +
                            normalization.position_mean[1]).toDouble(),
                        headingDelta = heading.toDouble(),
                        speed = (speedOut * normalization.speed_std + normalization.speed_mean)
                            .coerceAtLeast(0f).toDouble()
                    )
                }
            }
        }
    }

    override fun close() = session.close()
}

/**
 * Constant speed, no turning.
 *
 * This is the bar. It is what a phone achieves with no network and no model at all, so any
 * learned model that fails to beat it is not merely useless but actively harmful, which is
 * precisely what V8 turned out to be.
 */
class PersistenceModel(private val spanSeconds: Double) : MotionModel {
    override val label = "persistence"
    override val contract = ModelContract.IDR_V1

    override fun predict(window: FloatArray, initialSpeedMps: Double) =
        BlackoutFixture.ModelPrediction(
            forward = initialSpeedMps * spanSeconds,
            lateral = 0.0,
            headingDelta = 0.0,
            speed = initialSpeedMps
        )
}

/**
 * Road candidates from a polyline, using the same projection the production
 * [nisargpatel.deadreckoning.data.OfflineRoadNetwork] uses.
 *
 * The production class is reused where possible, but it is constructed from an Android
 * `Context` and an imported OSM PBF, so it cannot be instantiated in a JVM unit test. Only
 * the geometry lookup is reproduced here; the matching decision itself is made by the real
 * [nisargpatel.deadreckoning.matching.HiddenMarkovRoadMatcher] and the state correction by
 * the real estimator.
 *
 * The polyline itself comes from the test session's own ground truth, decimated and noised.
 * That is an OPTIMISTIC proxy: real OSM geometry error is uncorrelated with the drive, lanes
 * offset the vehicle from the centreline, and parallel roads create ambiguity this cannot
 * reproduce. Treat map-matching gains measured here as an upper bound.
 */
class PolylineRoadNetwork(ways: List<BlackoutFixture.Way>) {

    private class Segment(val wayId: Long, val name: String, val points: List<GeoPoint>)

    private val segments: List<Segment> = ways.map { way ->
        Segment(way.way_id, way.name, way.points.map { GeoPoint(it[0], it[1]) })
    }.filter { it.points.size >= 2 }

    /** Same 0.01-degree cell scheme as the production spatial index. */
    private val index: Map<String, List<Segment>> = segments
        .flatMap { segment -> segment.points.map { cellId(it) to segment } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, items) -> items.distinctBy { it.wayId } }

    fun candidates(point: GeoPoint): List<RoadCandidate> = nearby(point).mapNotNull { segment ->
        nearestOnSegment(point, segment)?.let { (projected, distance, bearing) ->
            RoadCandidate(segment.name, projected, distance, segment.wayId, bearing, false)
        }
    }.sortedBy { it.distanceMeters }.take(8)

    private fun nearby(point: GeoPoint): List<Segment> {
        val latitudeCell = (point.latitude * 100).toInt()
        val longitudeCell = (point.longitude * 100).toInt()
        val local = buildList {
            for (lat in latitudeCell - 1..latitudeCell + 1) {
                for (lon in longitudeCell - 1..longitudeCell + 1) {
                    addAll(index["$lat:$lon"].orEmpty())
                }
            }
        }.distinctBy { it.wayId }
        return local.ifEmpty { segments }
    }

    private fun cellId(point: GeoPoint) =
        "${(point.latitude * 100).toInt()}:${(point.longitude * 100).toInt()}"

    private fun nearestOnSegment(point: GeoPoint, segment: Segment): Triple<GeoPoint, Double, Double>? {
        var nearest: GeoPoint? = null
        var distance = Double.MAX_VALUE
        var bearing = 0.0
        segment.points.zipWithNext().forEach { (start, end) ->
            val candidate = project(point, start, end)
            val candidateDistance = point.distanceToAsDouble(candidate)
            if (candidateDistance < distance) {
                nearest = candidate
                distance = candidateDistance
                bearing = start.bearingTo(end).toDouble()
            }
        }
        return nearest?.let { Triple(it, distance, bearing) }
    }

    private fun project(point: GeoPoint, start: GeoPoint, end: GeoPoint): GeoPoint {
        val latitudeScale = 111_111.0
        val longitudeScale = latitudeScale * cos(Math.toRadians(point.latitude))
        val dx = (end.longitude - start.longitude) * longitudeScale
        val dy = (end.latitude - start.latitude) * latitudeScale
        val px = (point.longitude - start.longitude) * longitudeScale
        val py = (point.latitude - start.latitude) * latitudeScale
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return start
        val factor = ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return GeoPoint(
            start.latitude + dy * factor / latitudeScale,
            start.longitude + dx * factor / longitudeScale
        )
    }

    val wayCount: Int get() = segments.size
    val nodeCount: Int get() = segments.sumOf { it.points.size }
}

/** Median of a copy, so the caller's list is left alone. */
internal fun List<Double>.median(): Double {
    if (isEmpty()) return Double.NaN
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
}

internal fun List<Double>.percentile(fraction: Double): Double {
    if (isEmpty()) return Double.NaN
    val sorted = sorted()
    val position = ((sorted.size - 1) * fraction).coerceIn(0.0, (sorted.size - 1).toDouble())
    val lower = position.toInt()
    val upper = min(lower + 1, sorted.size - 1)
    val weight = position - lower
    return sorted[lower] * (1 - weight) + sorted[upper] * weight
}

internal fun approximately(first: Double, second: Double, tolerance: Double) =
    abs(first - second) <= tolerance
