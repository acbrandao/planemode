package com.planemode.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.planemode.data.AircraftRepository
import com.planemode.data.AirportRepository
import com.planemode.data.SettingsRepository
import com.planemode.map.OfflineMapManager
import com.planemode.model.Airport
import com.planemode.model.FlightEventType
import com.planemode.model.FlightSettings
import com.planemode.sensors.SensorRepository
import com.planemode.sim.FlightSimulator
import com.planemode.sound.SoundPlayer
import com.planemode.tracking.FlightTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Thin presentation facade: all state lives in app-scoped engines so tracking survives UI changes. */
@HiltViewModel
class FlightViewModel @Inject constructor(
    private val tracker: FlightTracker,
    private val settingsRepo: SettingsRepository,
    private val simulator: FlightSimulator,
    val sensors: SensorRepository,
    private val airports: AirportRepository,
    private val aircraftRepo: AircraftRepository,
    val offline: OfflineMapManager,
    private val sound: SoundPlayer,
    val feedback: UiFeedback,
) : ViewModel() {
    val state = tracker.state
    val track = tracker.track
    val gnss = tracker.gnss
    val events = tracker.events
    val banners = tracker.banners
    val settings = settingsRepo.settings
    val sim = simulator.state
    val attitude = sensors.attitude
    val aircraft = aircraftRepo.profiles
    val aircraftInfo = aircraftRepo.info
    val regions = offline.regions
    val offlineMessage = offline.message

    /**
     * Auto-zoom currently in effect on the map. The Settings preference is the default; a pan/zoom
     * gesture only pauses it for the current flight. It is re-armed at every new flight and app launch.
     */
    private val _autoZoomActive = MutableStateFlow(settingsRepo.current.autoZoom)
    val autoZoomActive: StateFlow<Boolean> = _autoZoomActive
    fun setAutoZoomActive(on: Boolean) { _autoZoomActive.value = on }

    init {
        viewModelScope.launch {
            settingsRepo.settings.map { it.autoZoom }.distinctUntilChanged().collect { _autoZoomActive.value = it }
        }
        viewModelScope.launch {
            tracker.state.map { it.sessionId }.distinctUntilChanged().collect { id ->
                if (id != null) _autoZoomActive.value = settingsRepo.current.autoZoom
            }
        }
    }

    fun startFlight() = tracker.startFlight()
    fun endFlight() = tracker.endFlight()
    fun setAutoStopOverride(on: Boolean) = tracker.setAutoStopOverride(on)
    fun dismissLanding() = tracker.dismissLandingSuggestion()

    fun update(transform: (FlightSettings) -> FlightSettings) = settingsRepo.update(transform)

    fun selectAircraft(id: String?) {
        update { it.copy(selectedAircraftId = id) }
        if (id != null) aircraftRepo.onAircraftSelected()
    }
    fun setDeparture(a: Airport?) = update { it.copy(departureCode = a?.iata) }
    fun setArrival(a: Airport?) = update { it.copy(arrivalCode = a?.iata) }
    suspend fun searchAirports(q: String) = airports.search(q)
    suspend fun refreshAircraft() = aircraftRepo.refresh()
    fun clearAircraftCache() = aircraftRepo.clearCache()

    fun previewSound(t: FlightEventType) = sound.preview(t)

    // Simulation controls
    fun simStart() = simulator.start()
    fun simPause() = simulator.pause()
    fun simResume() = simulator.resume()
    fun simReset() = simulator.reset()
    fun simSpeed(x: Int) { simulator.setSpeed(x); update { it.copy(simSpeed = x) } }
    fun simDropGps() = simulator.dropGps(30)
    fun simRecoverGps() = simulator.recoverGps()
    fun simWeak(on: Boolean) = simulator.setWeak(on)
    fun simNoSatellites(on: Boolean) = simulator.setNoSatelliteData(on)
    fun simInaccurate(on: Boolean) = simulator.setInaccurate(on)

    fun calibrateHud() = sensors.calibrateLevel()
    fun clearHudCalibration() = sensors.clearCalibration()
}
