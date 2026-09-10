package nisargpatel.deadreckoning.domain.state

data class AIState(
    val isModelLoaded: Boolean = false,
    val isActive: Boolean = false,
    val predictedSpeedKmh: Double = 0.0,
    val speedConfidencePercentage: Int = 0,
    val motionClassification: String = "UNKNOWN",
    val motionConfidencePercentage: Int = 0,
    val anomalyDetected: String = "None",
    val inferenceTimeMs: Long = 0L,
    val modelVersion: String = "Not loaded",
    // Stage 6: the network's own log-variance heads, which were exported in the ONNX graph
    // from the start but never read. Motion-class confidence is NOT a speed confidence.
    val speedUncertaintyKmh: Double = 0.0,
    val forwardUncertaintyMeters: Double = 0.0,
    val lateralUncertaintyMeters: Double = 0.0,
    val headingUncertaintyDegrees: Double = 0.0,
    val predictionHz: Double = 0.0,
    val dominantExpert: String = "",
    val expertWeights: List<Float> = emptyList()
)
