package com.planemode.location

import com.planemode.data.SettingsRepository
import com.planemode.sim.FlightSimulator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Switches between the real GNSS source and the simulator according to settings. */
@Singleton
class LocationHub @Inject constructor(
    private val real: AndroidPositionSource,
    private val simulator: FlightSimulator,
    private val settings: SettingsRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun events(): Flow<SourceEvent> = settings.settings
        .map { it.simEnabled to it.gpsIntervalMs.toLong() }
        .distinctUntilChanged()
        .flatMapLatest { (sim, interval) -> if (sim) simulator.events(interval) else real.events(interval) }

    val simulationEnabled get() = settings.settings.value.simEnabled
}
