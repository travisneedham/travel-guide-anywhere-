package com.travelguide.anywhere.ui.main

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.travelguide.anywhere.data.local.MentionedPlaceDao
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
    private val mentionedPlaceDao: MentionedPlaceDao
) : AndroidViewModel(application) {

    val tourState: StateFlow<TourState> = TourGuideService.tourState
        .stateIn(viewModelScope, SharingStarted.Eagerly, TourState.IDLE)

    val currentTopic: StateFlow<String> = TourGuideService.currentTopic
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val currentPois: StateFlow<List<PlaceOfInterest>> = TourGuideService.currentPois
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mentionedPlaces: StateFlow<List<PlaceOfInterest>> = TourGuideService.mentionedPlaces
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val errorMessage: StateFlow<String?> = TourGuideService.errorMessage
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun startTour(radiusMiles: Float, apiKey: String) {
        // Reset shared state
        TourGuideService.tourState.value = TourState.LOCATING
        TourGuideService.currentTopic.value = ""
        TourGuideService.currentPois.value = emptyList()
        TourGuideService.mentionedPlaces.value = emptyList()
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

    fun clearHistory() {
        viewModelScope.launch {
            mentionedPlaceDao.deleteAll()
            TourGuideService.mentionedPlaces.value = emptyList()
        }
    }
}
