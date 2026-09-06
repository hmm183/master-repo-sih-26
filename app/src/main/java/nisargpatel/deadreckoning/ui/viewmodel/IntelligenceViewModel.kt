package nisargpatel.deadreckoning.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nisargpatel.deadreckoning.domain.repository.NavigationRepository
import nisargpatel.deadreckoning.domain.state.AIState
import nisargpatel.deadreckoning.domain.state.NavigationEvent
import nisargpatel.deadreckoning.domain.state.NavigationState
import nisargpatel.deadreckoning.domain.state.SensorState

class IntelligenceViewModel(
    private val repository: NavigationRepository
) : ViewModel() {
    companion object {
        private const val TAG = "IntelligenceViewModel"
        private const val POTHOLE_ALERT_DURATION_MS = 4000L
    }

    val aiState: StateFlow<AIState> = repository.aiState
    val navigationState: StateFlow<NavigationState> = repository.navigationState
    val sensorState: StateFlow<SensorState> = repository.sensorState

    private val _potholeAlert = MutableStateFlow<String?>(null)
    val potholeAlert: StateFlow<String?> = _potholeAlert.asStateFlow()

    private var potholeClearJob: Job? = null

    init {
        Log.i(TAG, "IntelligenceViewModel initialized, starting event subscription")
        // Subscribe to navigation events and handle pothole detections
        viewModelScope.launch {
            Log.i(TAG, "Event collection coroutine launched")
            repository.navigationEvents.collect { event ->
                Log.i(TAG, "Event received: $event")
                if (event is NavigationEvent.PotholeDetected) {
                    Log.i(TAG, "Received PotholeDetected event: ${event.severity}")
                    showPotholeAlert(event.severity)
                } else {
                    Log.d(TAG, "Ignoring non-pothole event: $event")
                }
            }
        }
    }

    private fun showPotholeAlert(severity: String) {
        Log.d(TAG, "Displaying pothole alert: $severity for ${POTHOLE_ALERT_DURATION_MS}ms")
        
        // Cancel any existing clear job
        potholeClearJob?.cancel()
        
        // Show the alert
        _potholeAlert.value = severity
        
        // Schedule clearing after duration
        potholeClearJob = viewModelScope.launch {
            delay(POTHOLE_ALERT_DURATION_MS)
            Log.d(TAG, "Clearing pothole alert after timeout")
            _potholeAlert.value = null
        }
    }
}
