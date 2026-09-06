package nisargpatel.deadreckoning.domain.model

import org.osmdroid.util.GeoPoint

data class RouteInfo(
    val sourceName: String = "Current Location",
    val destinationName: String = "Select a destination",
    val sourcePoint: GeoPoint = GeoPoint(0.0, 0.0),
    val destinationPoint: GeoPoint = GeoPoint(0.0, 0.0),
    val routePoints: List<GeoPoint> = emptyList(),
    val totalDistanceKm: Double = 0.0,
    val estimatedTimeMinutes: Int = 0,
    val nextManeuver: String = "Choose a destination after location is available",
    val distanceToNextManeuverMeters: Int = 0,
    val maneuverIconType: ManeuverIconType = ManeuverIconType.STRAIGHT,
    val alternatives: List<RouteAlternative> = emptyList(),
    val selectedAlternativeId: String = "primary"
)

data class RouteAlternative(
    val id: String,
    val title: String,
    val summary: String,
    val routePoints: List<GeoPoint>,
    val totalDistanceKm: Double,
    val estimatedTimeMinutes: Int,
    val isSelected: Boolean = false
)

enum class ManeuverIconType {
    STRAIGHT,
    LEFT,
    RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    UTURN,
    ARRIVED
}
