package nisargpatel.deadreckoning

import nisargpatel.deadreckoning.fusion.VehicleAlignmentCalibrator
import nisargpatel.deadreckoning.fusion.VehicleFusionEkf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class VehicleFusionEkfTest {
    @Test
    fun `gnss update bounds propagated position error`() {
        val fusion = VehicleFusionEkf()
        val origin = GeoPoint(16.5, 80.6)
        fusion.reset(origin, 0.0, 0.0, 5.0)
        fusion.predict(forwardMeters = 80.0, lateralMeters = 0.0, headingDeltaRadians = 0.0, intervalSeconds = 2.0)
        val corrected = fusion.updateGnss(origin, 0.0, 0.0, 5.0)
        assertTrue(corrected.position.distanceToAsDouble(origin) < 50.0)
        assertTrue(corrected.horizontalUncertaintyMeters < 20.0)
    }

    @Test
    fun `alignment learns gnss course relative to phone yaw`() {
        val calibrator = VehicleAlignmentCalibrator()
        repeat(12) {
            calibrator.addObservation(
                phoneYawDegrees = 10f,
                phonePitchDegrees = 2f,
                phoneRollDegrees = -1f,
                gnssBearingDegrees = 100.0,
                speedKmh = 30.0,
                accuracyMeters = 5.0
            )
        }
        val alignment = calibrator.alignment()
        assertTrue(alignment.confidencePercentage > 90)
        assertTrue(kotlin.math.abs(alignment.yawOffsetDegrees - 90.0) < 1.0)
    }

    @Test
    fun `stationary floor clamps sub-centimeter open-loop sensor jitter`() {
        val fusion = VehicleFusionEkf()
        val origin = GeoPoint(16.5, 80.6)
        fusion.reset(origin, 0.0, 0.0, 5.0)

        val predicted = fusion.predict(
            forwardMeters = 0.02,
            lateralMeters = 0.01,
            headingDeltaRadians = 0.001,
            intervalSeconds = 0.2
        )
        assertTrue(predicted != null)
        assertTrue(predicted!!.position.distanceToAsDouble(origin) < 0.0001)
        assertTrue(predicted.speedMps == 0.0)
    }
}
