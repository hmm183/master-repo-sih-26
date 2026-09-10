package nisargpatel.deadreckoning

import com.google.gson.Gson
import nisargpatel.deadreckoning.core.spec.PreprocessingSpec
import nisargpatel.deadreckoning.support.PinoOnnxRunner
import nisargpatel.deadreckoning.support.locateAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the PINO-DR v3 preprocessing contract. This is the check that would have
 * caught the windowing regression the moment it shipped.
 *
 * The equivalent guard for IDR-V1 already exists inside [BlackoutAblationTest] as an
 * `assertEquals` on the fixture and shipped-artifact preprocessing versions. PINO had
 * no such check, so a discrepancy between the training pipeline (10 samples of 1 Hz
 * averaged data, 10-second window, 1 Hz predictions) and the runtime (10 raw 10 Hz
 * samples, 1-second window, 5 Hz predictions) shipped silently for months.
 *
 * ## What this test asserts
 *
 * 1. `preprocessing_version` in the manifest matches
 *    [PreprocessingSpec.PINO_V3.version]. Any manifest that lacks this field or has a
 *    different value refuses to load, exactly as [PinoDrMotionEngine][nisargpatel.deadreckoning.ml.PinoDrMotionEngine]
 *    does at construction.
 * 2. The manifest's declared windowing (window_size, bin_seconds, raw_sample_rate_hz,
 *    prediction_hz) matches the runtime constants. If a retrained model wants to
 *    change any of these, the training pipeline, the manifest, and both engines have
 *    to change together, or this test fails.
 * 3. Loading through [PinoOnnxRunner] succeeds, which requires the same guards to
 *    pass a second time on the artifact that actually ships in the APK.
 *
 * Deliberately kept in a small file separate from the ablation, so a change that
 * damages the contract is called out with a targeted failure rather than getting
 * mixed in with drift regressions.
 */
class PinoPreprocessingContractTest {

    private data class RawManifest(
        val preprocessing_version: String? = null,
        val window_size: Int? = null,
        val bin_seconds: Double? = null,
        val bin_sample_rate_hz: Double? = null,
        val raw_sample_rate_hz: Double? = null,
        val prediction_hz: Double? = null,
        val window_seconds: Double? = null,
        val sample_rate_hz: Double? = null
    )

    @Test
    fun `manifest declares a preprocessing version and matches runtime spec`() {
        val json = locateAsset("src/main/assets/ml/v3_pino_manifest.json").readText()
        val manifest = Gson().fromJson(json, RawManifest::class.java)

        val version = manifest.preprocessing_version
        assertNotNull(
            "PINO manifest is missing 'preprocessing_version'. This is the field the " +
                "engine uses to refuse a model whose training preprocessing does not " +
                "match runtime, so it MUST be present.",
            version
        )
        assertEquals(
            "PINO manifest preprocessing_version does not match runtime spec. If the " +
                "training pipeline changed, PreprocessingSpec.PINO_V3.version must be " +
                "updated to match, and everything else in the contract re-checked.",
            PreprocessingSpec.PINO_V3.version,
            version
        )
    }

    @Test
    fun `manifest windowing fields agree with runtime constants`() {
        val manifest = Gson().fromJson(
            locateAsset("src/main/assets/ml/v3_pino_manifest.json").readText(),
            RawManifest::class.java
        )
        val spec = PreprocessingSpec.PINO_V3

        assertEquals(
            "window_size mismatch: manifest ${manifest.window_size} vs runtime ${spec.windowSamples}. " +
                "Either the model was retrained on a different history depth or the runtime buffer " +
                "was resized without touching the manifest. Both are dangerous.",
            spec.windowSamples, manifest.window_size
        )

        val raw = manifest.raw_sample_rate_hz
        assertNotNull("raw_sample_rate_hz is required in the manifest", raw)
        assertEquals(
            "raw_sample_rate_hz mismatch: manifest $raw vs runtime " +
                "${PreprocessingSpec.PINO_V3_RAW_SAMPLE_RATE_HZ}. The raw sensor rate " +
                "IS the number the training pipeline averaged from.",
            PreprocessingSpec.PINO_V3_RAW_SAMPLE_RATE_HZ.toDouble(),
            raw!!,
            1e-6
        )

        val binRate = manifest.bin_sample_rate_hz ?: manifest.bin_seconds?.let { 1.0 / it }
        assertNotNull(
            "either bin_sample_rate_hz or bin_seconds is required in the manifest",
            binRate
        )
        assertEquals(
            "bin rate mismatch: manifest $binRate Hz vs runtime ${spec.sampleRateHz} Hz. " +
                "Every model timestep must correspond to one bin of raw averaging.",
            spec.sampleRateHz.toDouble(),
            binRate!!,
            1e-6
        )

        val predictionHz = manifest.prediction_hz
        assertNotNull("prediction_hz is required in the manifest", predictionHz)
        assertEquals(
            "prediction_hz mismatch: manifest $predictionHz vs spec ${spec.predictionHz}",
            spec.predictionHz,
            predictionHz!!,
            1e-6
        )

        // Sanity: 10 samples at 1 Hz should span 10 seconds. This is the field that read
        // as "10 samples at 10 Hz = 1 second window" and confused the runtime port.
        val windowSeconds = manifest.window_seconds
        if (windowSeconds != null) {
            assertEquals(
                "window_seconds mismatch: manifest $windowSeconds vs spec ${spec.windowSeconds}. " +
                    "The runtime should carry ${spec.windowSeconds} s of history, not ${spec.windowSamples} " +
                    "raw samples' worth.",
                spec.windowSeconds,
                windowSeconds,
                1e-6
            )
        }
    }

    @Test
    fun `runner constructs against the shipped artifact`() {
        // Actually loading the runner is what enforces the guards end-to-end: it re-runs
        // exactly the same manifest checks the on-device engine applies, but against the
        // JVM-side ONNX loader. If the manifest and the shipped .onnx have gone out of
        // sync, this constructor throws.
        PinoOnnxRunner().use { runner ->
            val expectedVersion = PreprocessingSpec.PINO_V3.version
            assertEquals(
                "runner reports a preprocessing_version different from the manifest, " +
                    "which is only possible if the runner was pointed at a different file",
                expectedVersion,
                runner.preprocessingVersion
            )
            assertTrue(
                "manifest.zupt_threshold should be in (0, 1)",
                runner.manifest.zupt_threshold in 0.01f..0.99f
            )
        }
    }

    @Test
    fun `v7 manifest declares pino-v7-moe preprocessing version and matches runtime spec`() {
        val json = locateAsset("src/main/assets/ml/v7_pino_manifest.json").readText()
        val manifest = Gson().fromJson(json, RawManifest::class.java)

        val version = manifest.preprocessing_version
        assertNotNull("PINO v7 manifest is missing 'preprocessing_version'", version)
        assertEquals(
            "PINO v7 manifest preprocessing_version does not match runtime spec",
            PreprocessingSpec.PINO_V7.version,
            version
        )
    }

    @Test
    fun `v7 manifest windowing fields agree with runtime constants`() {
        val manifest = Gson().fromJson(
            locateAsset("src/main/assets/ml/v7_pino_manifest.json").readText(),
            RawManifest::class.java
        )
        val spec = PreprocessingSpec.PINO_V7

        assertEquals(spec.windowSamples, manifest.window_size)
        assertEquals(
            PreprocessingSpec.PINO_V7_RAW_SAMPLE_RATE_HZ.toDouble(),
            manifest.raw_sample_rate_hz!!,
            1e-6
        )
        val binRate = manifest.bin_sample_rate_hz ?: manifest.bin_seconds?.let { 1.0 / it }
        assertEquals(spec.sampleRateHz.toDouble(), binRate!!, 1e-6)
        assertEquals(spec.predictionHz, manifest.prediction_hz!!, 1e-6)
    }
}
