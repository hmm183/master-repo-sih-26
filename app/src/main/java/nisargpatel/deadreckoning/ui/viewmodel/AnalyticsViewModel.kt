package nisargpatel.deadreckoning.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import nisargpatel.deadreckoning.domain.repository.NavigationRepository
import nisargpatel.deadreckoning.domain.state.AnalyticsState

class AnalyticsViewModel(
    private val repository: NavigationRepository
) : ViewModel() {

    val analyticsState: StateFlow<AnalyticsState> = repository.analyticsState
    val navigationState: StateFlow<nisargpatel.deadreckoning.domain.state.NavigationState> = repository.navigationState
    val gnssState: StateFlow<nisargpatel.deadreckoning.domain.state.GNSSState> = repository.gnssState
}
