package com.planemode.model

import kotlinx.serialization.Serializable

enum class SpeedUnit(val label: String, val distanceLabel: String) { KNOTS("kt", "nm"), MPH("mph", "mi"), KMH("km/h", "km") }
enum class AltitudeUnit(val label: String, val vsLabel: String) { FEET("ft", "fpm"), METERS("m", "m/s") }
enum class MapStyleKind(val label: String) { AVIATION_LIGHT("Aviation light"), AVIATION_DARK("Aviation dark") }
enum class MapOrientation(val label: String) { TRACK_UP("Track up"), NORTH_UP("North up") }
enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }
enum class InfoLayout(val label: String) { COMPACT("Compact"), EXPANDED("Expanded") }
enum class TileProvider(val label: String) { OPENFREEMAP("OpenFreeMap (no key)"), CUSTOM("Custom OpenMapTiles source"), NONE("Embedded only (offline)") }
@Serializable
enum class SimRoute(val label: String) {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.json.JsonNames("PROCEDURAL_EWR_LAX") PROCEDURAL("EWR → MIA (procedural)"),
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.json.JsonNames("RECORDED_EWR_LAX") RECORDED("EWR → MIA (recorded JSONL)"),
}

@Serializable
data class FlightSettings(
    // Flight tracking
    val autoStart: Boolean = false,
    val autoStop: Boolean = true,
    val autoStartKts: Int = 140,
    val autoStopKts: Int = 35,
    val landingConfirmSec: Int = 90,
    val minAccuracyM: Int = 50,
    val gpsIntervalMs: Int = 1000,
    val predictionSec: Int = 30,
    val takeoffAlertKts: Int = 140,
    // Flight plan
    val selectedAircraftId: String? = null,
    val defaultAircraftId: String? = null,
    val departureCode: String? = null,
    val arrivalCode: String? = null,
    val aircraftDataUrl: String = "",
    // Sound
    val soundEnabled: Boolean = true,
    val soundVolume: Float = 0.8f,
    val disabledSounds: Set<String> = emptySet(),
    val clickSound: Boolean = true,
    val clickHaptics: Boolean = true,
    // Units
    val speedUnit: SpeedUnit = SpeedUnit.KNOTS,
    val altitudeUnit: AltitudeUnit = AltitudeUnit.FEET,
    // Mapping
    val mapStyle: MapStyleKind = MapStyleKind.AVIATION_DARK,
    val mapOrientation: MapOrientation = MapOrientation.NORTH_UP,
    val autoCenter: Boolean = true,
    val autoZoom: Boolean = true,
    val autoRegionCache: Boolean = true,
    val maxCacheMb: Int = 500,
    val wifiOnlyDownloads: Boolean = true,
    val tileProvider: TileProvider = TileProvider.OPENFREEMAP,
    val customTileJsonUrl: String = "",
    val customGlyphsUrl: String = "",
    val customOfflineStyleUrl: String = "",
    val speedBandsKts: List<Int> = listOf(150, 250, 350),
    val altitudeBandsFt: List<Int> = listOf(10000, 20000, 30000, 40000),
    // Display
    val theme: ThemeMode = ThemeMode.DARK,
    val infoLayout: InfoLayout = InfoLayout.EXPANDED,
    val keepScreenOn: Boolean = true,
    // Simulation
    val simEnabled: Boolean = false,
    val simRoute: SimRoute = SimRoute.PROCEDURAL,
    val simSpeed: Int = 20,
    val simScriptedOutage: Boolean = true,
    /** Settings schema version for one-time migrations of stored values. */
    val schemaVersion: Int = 0,
)
