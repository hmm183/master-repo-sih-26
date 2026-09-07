package nisargpatel.deadreckoning.domain.state

data class CandidateRoad(
    val roadName: String,
    val probabilityPercentage: Int,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val bearingDegrees: Double = 0.0
)

data class MapMatchingHistoryItem(
    val id: Long = System.currentTimeMillis(),
    val timestampMs: Long = System.currentTimeMillis(),
    val roadName: String,
    val distanceFromRoadMeters: Double,
    val confidencePercentage: Int,
    val latitude: Double,
    val longitude: Double
)

data class MapMatchingState(
    val rawPositionLat: Double = 0.0,
    val rawPositionLon: Double = 0.0,
    val matchedPositionLat: Double = 0.0,
    val matchedPositionLon: Double = 0.0,
    val selectedRoadName: String = "Unknown Road",
    val candidateRoads: List<CandidateRoad> = emptyList(),
    val matchConfidencePercentage: Int = 0,
    val distanceFromRoadMeters: Double = 0.0,
    val candidateCount: Int = 0,
    val matchHistory: List<MapMatchingHistoryItem> = emptyList()
)

