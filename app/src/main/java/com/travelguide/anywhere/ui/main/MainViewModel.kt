package com.travelguide.anywhere.ui.main

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.travelguide.anywhere.data.local.MentionedPlacesStore
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.service.TourGuideService
import com.travelguide.anywhere.service.TourState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val mentionedPlacesStore: MentionedPlacesStore
) : AndroidViewModel(application) {

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

    fun startTour(radiusMiles: Float, apiKey: String) {
        TourGuideService.tourState.value = TourState.LOCATING
        TourGuideService.currentTopic.value = ""
        TourGuideService.currentPois.value = emptyList()
        TourGuideService.errorMessage.value = null

        val intent = Intent(getApplication(), TourGuideService::class.java).apply {
            action = TourGuideService.ACTION_START
            putExtra(TourGuideService.EXTRA_RADIUS_MILES, radiusMiles)
            putExtra(TourGuideService.EXTRA_API_KEY, apiKey)
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

    fun clearHistory() {
        viewModelScope.launch {
            mentionedPlacesStore.clear()
            TourGuideService.mentionedPlaces.value = emptyList()
        }
    }
}
