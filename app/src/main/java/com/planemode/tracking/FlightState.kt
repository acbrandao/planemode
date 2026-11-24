package com.planemode.tracking

import com.planemode.calc.Eta
import com.planemode.model.*

data class GpsTimers(val validMs: Long = 0, val estimatedMs: Long = 0, val noPositionMs: Long = 0)

/** Immutable snapshot of everything the UI needs. All values in SI units. */
data class FlightState(
    val sessionId: Long? = null,
    val active: Boolean = false,
    val monitoring: Boolean = false,
    val simulated: Boolean = false,
    val aircraft: AircraftProfile? = null,
    val departure: Airport? = null,
    val arrival: Airport? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitudeM: Double? = null,
    val groundSpeedMps: Double? = null,
    val courseDeg: Double? = null,
    val verticalSpeedMps: Double? = null,
    val positionEstimated: Boolean = false,
    val positionStale: Boolean = false,
    val uncertaintyM: Double? = null,
    val gpsState: GpsState = GpsState.SEARCHING,
    val gpsReason: String? = null,
    val hAccM: Double? = null,
    val vAccM: Double? = null,
    val satellitesUsed: Int? = null,
    val lastFixEpochMs: Long? = null,
    val sinceLastFixMs: Long? = null,
    val currentLockMs: Long? = null,
    val currentLossMs: Long? = null,
    val elapsedMs: Long = 0,
    val startedEpochMs: Long? = null,
    val distanceM: Double = 0.0,
    val remainingM: Double? = null,
    val routeTotalM: Double? = null,
    val eta: Eta? = null,
    val phase: FlightPhase = FlightPhase.IDLE,
    val timers: GpsTimers = GpsTimers(),
    val maxAltitudeM: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val autoStopOverride: Boolean = false,
    val landedSuggested: Boolean = false,
    val nowEpochMs: Long = System.currentTimeMillis(),
) {
    val hasPosition get() = lat != null && lon != null
}

data class TrackSnapshot(val points: List<TrackPoint> = emptyList(), val version: Int = 0)
