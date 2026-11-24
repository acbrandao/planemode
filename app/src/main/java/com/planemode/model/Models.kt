package com.planemode.model

import kotlinx.serialization.Serializable

data class LatLon(val lat: Double, val lon: Double)

/** A single position report from a location source (real GNSS or simulator). */
data class GpsFix(
    val epochMs: Long,
    /** Monotonic timestamp (elapsedRealtime for real GPS, virtual clock for simulation). */
    val monoMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
    val hAccM: Double?,
    val vAccM: Double?,
    val speedAccMps: Double? = null,
    val satellitesUsed: Int? = null,
    val simulated: Boolean = false,
)

enum class SampleSource { GPS, ESTIMATED }

data class TrackPoint(
    val epochMs: Long,
    val monoMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val speedMps: Double?,
    val source: SampleSource,
    val hAccM: Double? = null,
    val distanceM: Double = 0.0,
)

enum class GpsState(val label: String) {
    UNAVAILABLE("NO GPS ACCESS"),
    SEARCHING("SEARCHING"),
    LOCKED("GPS LOCKED"),
    WEAK("GPS WEAK"),
    LOST("GPS LOST"),
    ESTIMATED("ESTIMATED POSITION"),
    REACQUIRED("GPS REACQUIRED");

    val hasValidFix get() = this == LOCKED || this == WEAK || this == REACQUIRED
}

enum class Constellation(val label: String, val color: Long) {
    GPS("GPS", 0xFF4FC3F7), GLONASS("GLONASS", 0xFFEF5350), GALILEO("Galileo", 0xFF66BB6A),
    BEIDOU("BeiDou", 0xFFFFCA28), QZSS("QZSS", 0xFFAB47BC), SBAS("SBAS", 0xFFBDBDBD),
    IRNSS("NavIC", 0xFFFF8A65), UNKNOWN("Other", 0xFF90A4AE)
}

data class SatelliteInfo(
    val svid: Int,
    val constellation: Constellation,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
)

data class GnssSnapshot(
    /** False when the device/source cannot provide per-satellite data. */
    val supported: Boolean,
    val satellites: List<SatelliteInfo>,
    val monoMs: Long,
    val simulated: Boolean = false,
) {
    val inView get() = satellites.size
    val used get() = satellites.count { it.usedInFix }
    val averageCn0 get() = satellites.filter { it.cn0DbHz > 0 }.map { it.cn0DbHz }.average().takeIf { !it.isNaN() }
}

data class SensorAttitude(
    val available: Boolean = false,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    /** Magnetic azimuth of the phone itself – NOT the aircraft heading. */
    val deviceAzimuthDeg: Float? = null,
    val calibrated: Boolean = false,
    val pressureHpa: Float? = null,
    val pressureAltitudeM: Float? = null,
)

enum class SilhouetteKind { NARROWBODY, WIDEBODY, REGIONAL }

@Serializable
data class AircraftProfile(
    val id: String,
    val manufacturer: String,
    val family: String,
    val typicalCruiseKts: Int,
    val maxCruiseKts: Int,
    val typicalCruiseAltFt: Int,
    val maxAltFt: Int,
    val rangeNm: Int,
    val silhouette: String = "NARROWBODY",
    val note: String = "",
) {
    val displayName get() = "$manufacturer $family"
    val silhouetteKind get() = runCatching { SilhouetteKind.valueOf(silhouette) }.getOrDefault(SilhouetteKind.NARROWBODY)
}

data class Airport(
    val iata: String,
    val icao: String,
    val name: String,
    val city: String,
    val country: String,
    val lat: Double,
    val lon: Double,
    val elevationFt: Int,
) {
    val label get() = "$iata · $city"
}

enum class FlightPhase(val label: String, val airborne: Boolean) {
    IDLE("Idle", false), ARMED("Armed", false), TAKEOFF("Takeoff", true), INITIAL_CLIMB("Initial climb", true),
    CLIMB("Climb", true), CRUISE("Cruise", true), TOP_OF_DESCENT("Top of descent", true),
    DESCENT("Descent", true), LANDING("Landing", false), COMPLETED("Completed", false)
}

enum class FlightEventType(val label: String) {
    GPS_ACQUIRED("GPS acquired"),
    GPS_LOST("GPS lost"),
    TAKEOFF_SPEED("Takeoff-speed threshold (approx., not V1)"),
    TEN_THOUSAND("Climbing through 10,000 ft"),
    CRUISE("Cruise altitude reached"),
    MAX_CRUISE_SPEED("Reference max cruise speed reached"),
    TOP_OF_DESCENT("Top of descent"),
    LANDING("Probable landing"),
    FLIGHT_COMPLETE("Flight complete"),
}

data class FlightEvent(
    val type: FlightEventType,
    val epochMs: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitudeM: Double? = null,
)

data class MapRegion(
    val id: Long,
    val name: String,
    val kind: String,
    val north: Double, val east: Double, val south: Double, val west: Double,
    val minZoom: Double,
    val maxZoom: Double,
    val complete: Boolean,
    val downloading: Boolean,
    val completedResources: Long,
    val requiredResources: Long,
    val sizeBytes: Long,
    val error: String? = null,
) {
    val progress get() = if (requiredResources > 0) completedResources.toFloat() / requiredResources else 0f
}
