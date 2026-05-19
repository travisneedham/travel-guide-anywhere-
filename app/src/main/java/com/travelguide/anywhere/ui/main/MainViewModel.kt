package com.travelguide.anywhere.ui.main

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.travelguide.anywhere.data.local.MentionedPlacesStore
import com.travelguide.anywhere.data.local.NarrationHistoryStore
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.data.model.RouteData
import com.travelguide.anywhere.repository.RouteRepository
import com.travelguide.anywhere.service.TourGuideService
import com.travelguide.anywhere.service.TourState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val mentionedPlacesStore: MentionedPlacesStore,
    private val narrationHistoryStore: NarrationHistoryStore,
    private val routeRepository: RouteRepository,
) : AndroidViewModel(application) {

    sealed class RouteParseState {
        object Idle : RouteParseState()
        object Loading : RouteParseState()
        data class Ready(val route: RouteData) : RouteParseState()
        data class Error(val message: String) : RouteParseState()
    }

    val tourState: StateFlow<TourState> = TourGuideService.tourState
        .stateIn(viewModelScope, SharingStarted.Eagerly, TourState.IDLE)

    val currentTopic: StateFlow<String> = TourGuideService.currentTopic
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val currentPois: StateFlow<List<PlaceOfInterest>> = TourGuideService.currentPois
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mentionedPlaces: StateFlow<List<MentionedPlacesStore.Entry>> = TourGuideService.mentionedPlaces
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val errorMessage: StateFlow<String?> = TourGuideService.errorMessage
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentPoiImage: StateFlow<String?> = TourGuideService.currentPoiImage
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val loadingProgress: StateFlow<Float> = TourGuideService.loadingProgress
        .stateIn(viewModelScope, SharingStarted.Eagerly, -1f)

    private val _routeParseState = MutableStateFlow<RouteParseState>(RouteParseState.Idle)
    val routeParseState: StateFlow<RouteParseState> = _routeParseState.asStateFlow()

    private var parseJob: Job? = null

    fun parseRoute(url: String) {
        parseJob?.cancel()
        if (url.isBlank()) {
            _routeParseState.value = RouteParseState.Idle
            return
        }
        _routeParseState.value = RouteParseState.Loading
        parseJob = viewModelScope.launch {
            _routeParseState.value = when (val result = routeRepository.resolveRoute(url)) {
                is RouteRepository.Result.Success -> RouteParseState.Ready(result.route)
                is RouteRepository.Result.Failure -> RouteParseState.Error(result.message)
            }
        }
    }

    fun startRouteTour(radiusMiles: Float, apiKey: String) {
        val state = _routeParseState.value
        if (state !is RouteParseState.Ready) return
        TourGuideService.pendingRoute = state.route
        startTour(radiusMiles, apiKey, famousMode = false)
    }

    fun resetRoute() {
        parseJob?.cancel()
        _routeParseState.value = RouteParseState.Idle
    }

    fun startTour(radiusMiles: Float, apiKey: String, famousMode: Boolean = false) {
        TourGuideService.tourState.value = TourState.LOCATING
        TourGuideService.currentTopic.value = ""
        TourGuideService.currentPois.value = emptyList()
        TourGuideService.errorMessage.value = null
        TourGuideService.currentPoiImage.value = null

        val intent = Intent(getApplication(), TourGuideService::class.java).apply {
            action = TourGuideService.ACTION_START
            putExtra(TourGuideService.EXTRA_RADIUS_MILES, radiusMiles)
            putExtra(TourGuideService.EXTRA_API_KEY, apiKey)
            putExtra(TourGuideService.EXTRA_FAMOUS_MODE, famousMode)
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun stopTour() {
        val intent = Intent(getApplication(), TourGuideService::class.java).apply {
            action = TourGuideService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    fun pauseOrResume() {
        val action = if (tourState.value == TourState.PAUSED)
            TourGuideService.ACTION_RESUME else TourGuideService.ACTION_PAUSE
        val intent = Intent(getApplication(), TourGuideService::class.java).apply {
            this.action = action
        }
        getApplication<Application>().startService(intent)
    }

    fun skip() {
        val intent = Intent(getApplication(), TourGuideService::class.java).apply {
            action = TourGuideService.ACTION_SKIP
        }
        getApplication<Application>().startService(intent)
    }

    fun setPlaybackSpeed(rate: Float) {
        val intent = Intent(getApplication(), TourGuideService::class.java).apply {
            action = TourGuideService.ACTION_SET_SPEED
            putExtra(TourGuideService.EXTRA_SPEECH_RATE, rate)
        }
        getApplication<Application>().startService(intent)
    }

    fun clearHistory() {
        viewModelScope.launch {
            mentionedPlacesStore.clear()
            narrationHistoryStore.clear()
            TourGuideService.mentionedPlaces.value = emptyList()
        }
    }
}
