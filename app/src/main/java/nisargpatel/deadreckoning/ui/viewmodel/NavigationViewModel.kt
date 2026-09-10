package nisargpatel.deadreckoning.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nisargpatel.deadreckoning.domain.model.RouteInfo
import nisargpatel.deadreckoning.domain.repository.NavigationRepository
import nisargpatel.deadreckoning.domain.state.*
import nisargpatel.deadreckoning.util.OSRMRouteFetcher
import org.osmdroid.util.GeoPoint

class NavigationViewModel(
    private val repository: NavigationRepository
) : ViewModel() {

    val navigationState: StateFlow<NavigationState> = repository.navigationState
    val gnssState: StateFlow<GNSSState> = repository.gnssState
    val aiState: StateFlow<AIState> = repository.aiState
    val sensorState: StateFlow<SensorState> = repository.sensorState
    val mapState: StateFlow<MapState> = repository.mapState
    val mapMatchingState: StateFlow<MapMatchingState> = repository.mapMatchingState
    val events: SharedFlow<NavigationEvent> = repository.navigationEvents

    val selectedRoute: StateFlow<RouteInfo> = repository.activeRouteInfo
    private val _isRerouting = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRerouting: StateFlow<Boolean> = _isRerouting.asStateFlow()

    fun startNavigation() = repository.startNavigation()
    fun stopNavigation() = repository.stopNavigation()
    fun startGnssMonitoring() = repository.startGnssMonitoring()
    fun hasFreshGnss(): Boolean = repository.hasFreshGnss()

    fun selectAlternativeRoute(alternativeId: String) {
        val current = selectedRoute.value
        val chosen = current.alternatives.firstOrNull { it.id == alternativeId } ?: return

        // Swap chosen alternative into primary, and move current primary into alternatives
        val updatedAlternatives = mutableListOf<nisargpatel.deadreckoning.domain.model.RouteAlternative>()
        updatedAlternatives.add(
            nisargpatel.deadreckoning.domain.model.RouteAlternative(
                id = "route_prev_primary_${System.currentTimeMillis() % 1000}",
                title = current.destinationName,
                summary = "Alternative Route",
                routePoints = current.routePoints,
                totalDistanceKm = current.totalDistanceKm,
                estimatedTimeMinutes = current.estimatedTimeMinutes,
                isSelected = false
            )
        )
        updatedAlternatives.addAll(current.alternatives.filter { it.id != alternativeId })

        val newRoute = current.copy(
            routePoints = chosen.routePoints,
            totalDistanceKm = chosen.totalDistanceKm,
            estimatedTimeMinutes = chosen.estimatedTimeMinutes,
            alternatives = updatedAlternatives,
            selectedAlternativeId = alternativeId
        )
        repository.setActiveRoute(newRoute)
    }

    fun recalculateRoute(currentPosition: GeoPoint, destinationPoint: GeoPoint, destinationName: String) {
        if (_isRerouting.value) return
        if (!nisargpatel.deadreckoning.util.RouteRerouteGating.isGnssTrustworthyForReroute(gnssState.value, navigationState.value, hasFreshGnss())) {
            android.util.Log.w("NavigationViewModel", "Suppressing route recalculation: GNSS is untrusted/blackout; holding route manifold.")
            return
        }
        _isRerouting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var route: RouteInfo? = null
                // 1. Attempt online dynamic route
                try {
                    val onlineRoute = OSRMRouteFetcher.fetchRoute(currentPosition, destinationPoint, destinationName)
                    if (onlineRoute.routePoints.size > 1) {
                        route = onlineRoute
                    }
                } catch (e: Exception) {
                    android.util.Log.w("NavigationViewModel", "Online dynamic reroute error: ${e.message}")
                }

                // 2. If online route unavailable (e.g. offline or GNSS outage), query offline road network
                if (route == null) {
                    val offline = repository.findOfflineRoute(currentPosition, destinationPoint, destinationName)
                    if (offline != null && offline.routePoints.size > 1) {
                        route = offline
                    }
                }

                // 3. Fallback to realistic street grid router so rerouting NEVER fails
                val finalRoute = route ?: OSRMRouteFetcher.generateStreetGridRoute(currentPosition, destinationPoint, destinationName)
                if (finalRoute.routePoints.size > 1) {
                    repository.setActiveRoute(finalRoute)
                }
            } catch (e: Exception) {
                android.util.Log.w("NavigationViewModel", "Dynamic rerouting error: ${e.message}")
            } finally {
                _isRerouting.value = false
            }
        }
    }

    fun selectDestination(name: String, destinationPoint: GeoPoint) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentNav = navigationState.value
            val currentGnss = gnssState.value
            val sourcePoint = when {
                currentNav.latitude != 0.0 && currentNav.longitude != 0.0 ->
                    GeoPoint(currentNav.latitude, currentNav.longitude)
                currentGnss.latitude != 0.0 && currentGnss.longitude != 0.0 ->
                    GeoPoint(currentGnss.latitude, currentGnss.longitude)
                else -> null
            }
            if (sourcePoint == null) {
                android.util.Log.w("NavigationViewModel", "Cannot compute route: No GPS fix acquired yet.")
                return@launch
            }

            // 1. Fetch real street road routing first (OSRM / OpenStreetMap online for Google Maps-grade route)
            var route: RouteInfo? = null
            try {
                val onlineRoute = OSRMRouteFetcher.fetchRoute(sourcePoint, destinationPoint, name)
                if (onlineRoute.routePoints.size > 1) {
                    route = onlineRoute
                }
            } catch (e: Exception) {
                android.util.Log.w("NavigationViewModel", "Online route fetch error: ${e.message}")
            }

            // 2. If online route unavailable, fall back to offline regional road network
            if (route == null) {
                val offline = repository.findOfflineRoute(sourcePoint, destinationPoint, name)
                if (offline != null && offline.routePoints.size > 1) {
                    route = offline
                }
            }

            // 3. Fallback to realistic street grid route if needed
            val finalRoute = route ?: OSRMRouteFetcher.generateStreetGridRoute(sourcePoint, destinationPoint, name)
            repository.setActiveRoute(finalRoute)
        }
    }
}
