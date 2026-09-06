package nisargpatel.deadreckoning.domain.repository

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import nisargpatel.deadreckoning.domain.model.RouteInfo
import org.osmdroid.util.GeoPoint
import nisargpatel.deadreckoning.domain.state.*

interface NavigationRepository {
    val navigationState: StateFlow<NavigationState>
    val sensorState: StateFlow<SensorState>
    val gnssState: StateFlow<GNSSState>
    val aiState: StateFlow<AIState>
    val mapState: StateFlow<MapState>
    val mapMatchingState: StateFlow<MapMatchingState>
    val analyticsState: StateFlow<AnalyticsState>
    val sessionState: StateFlow<SessionState>
    val navigationEvents: SharedFlow<NavigationEvent>
    val activeRouteInfo: StateFlow<RouteInfo>

    fun startNavigation()
    fun stopNavigation()
    fun startGnssMonitoring()
    fun setActiveRoute(route: RouteInfo)
    fun clearActiveRoute()
    suspend fun findOfflineRoute(start: GeoPoint, end: GeoPoint, destinationName: String): RouteInfo?

}
