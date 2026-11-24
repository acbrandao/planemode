package com.planemode.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.planemode.model.*
import com.planemode.ui.FlightViewModel
import com.planemode.ui.Fmt
import com.planemode.ui.LocalClick
import com.planemode.ui.components.*
import com.planemode.ui.theme.LocalInstrumentColors
import com.planemode.ui.theme.Pm
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class SettingsTab(val title: String, val icon: ImageVector)

private val settingsTabs = listOf(
    SettingsTab("Flight", Icons.Default.FlightTakeoff),
    SettingsTab("Map", Icons.Default.Map),
    SettingsTab("Display", Icons.Default.Tune),
    SettingsTab("Sensors", Icons.Default.Sensors),
    SettingsTab("Simulation", Icons.Default.Science),
)

@Composable
fun SettingsScreen(vm: FlightViewModel, onBack: () -> Unit, onRegions: () -> Unit) {
    val c = LocalInstrumentColors.current
    val click = LocalClick.current
    val scope = rememberCoroutineScope()
    var savedPage by rememberSaveable { mutableIntStateOf(0) }
    val pager = rememberPagerState(initialPage = savedPage) { settingsTabs.size }
    LaunchedEffect(pager.currentPage) { savedPage = pager.currentPage }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 4.dp, containerColor = c.background, minTabWidth = 0.dp) {
            settingsTabs.forEachIndexed { i, t ->
                Tab(
                    selected = pager.currentPage == i,
                    onClick = { click(); scope.launch { pager.animateScrollToPage(i) } },
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t.title, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 0) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                when (page) {
                    0 -> FlightTab(vm)
                    1 -> MapTab(vm, onRegions)
                    2 -> DisplayTab(vm)
                    3 -> SensorsTab(vm)
                    else -> SimulationTab(vm)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ tabs

@Composable
private fun FlightTab(vm: FlightViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val aircraft by vm.aircraft.collectAsStateWithLifecycle()
    val info by vm.aircraftInfo.collectAsStateWithLifecycle()
    val c = LocalInstrumentColors.current
    val scope = rememberCoroutineScope()
    var pickDefault by remember { mutableStateOf(false) }

    Group("Automatic tracking") {
        SwitchRow("Auto-start flight", null, s.autoStart) { v -> vm.update { it.copy(autoStart = v) } }
        SwitchRow("Auto-stop after landing", null, s.autoStop) { v -> vm.update { it.copy(autoStop = v) } }
        if (st.active) SwitchRow("Prevent auto-stop (this flight)", null, st.autoStopOverride) { vm.setAutoStopOverride(it) }
        SliderRow("Auto-start speed", s.autoStartKts, 80..200, 5, "kt") { v -> vm.update { it.copy(autoStartKts = v) } }
        SliderRow("Taxi / auto-stop speed", s.autoStopKts, 10..80, 5, "kt") { v -> vm.update { it.copy(autoStopKts = v) } }
        SliderRow("Landing confirmation", s.landingConfirmSec, 30..300, 10, "s") { v -> vm.update { it.copy(landingConfirmSec = v) } }
        SliderRow("Takeoff-speed alert (not V1)", s.takeoffAlertKts, 80..200, 5, "kt") { v -> vm.update { it.copy(takeoffAlertKts = v) } }
    }
    Group("GPS") {
        SliderRow("Minimum accuracy", s.minAccuracyM, 10..200, 5, "m") { v -> vm.update { it.copy(minAccuracyM = v) } }
        SliderRow("Update interval", s.gpsIntervalMs / 1000, 1..10, 1, "s") { v -> vm.update { it.copy(gpsIntervalMs = v * 1000) } }
        SliderRow("Position prediction window", s.predictionSec, 0..120, 5, "s") { v -> vm.update { it.copy(predictionSec = v) } }
    }
    Group("Aircraft") {
        ActionRow("Default aircraft", aircraft.firstOrNull { it.id == s.defaultAircraftId }?.displayName ?: "None") { pickDefault = true }
        Expandable("Reference data · ${info.source}") {
            info.message?.let { Text(it, fontSize = 12.sp, color = Pm.Amber) }
            TextRow("Online data URL (JSON, optional)", s.aircraftDataUrl) { v -> vm.update { it.copy(aircraftDataUrl = v.trim()) } }
            Row {
                TextButton(onClick = { scope.launch { vm.refreshAircraft() } }) { Text("Refresh") }
                TextButton(onClick = vm::clearAircraftCache) { Text("Clear cache") }
            }
            aircraft.forEach { a ->
                Text("${a.displayName}: ~${a.typicalCruiseKts}/${a.maxCruiseKts} kt · FL${a.typicalCruiseAltFt / 100}/${a.maxAltFt / 100} · ~${a.rangeNm} nm",
                    fontSize = 11.sp, color = c.text, modifier = Modifier.padding(vertical = 1.dp))
            }
            Text("Approximate family-level values, not exact variant specifications.", fontSize = 11.sp, color = c.textDim)
        }
    }
    if (pickDefault) AircraftPickerDialog(aircraft, s.defaultAircraftId, { id -> vm.update { it.copy(defaultAircraftId = id) }; pickDefault = false }, { pickDefault = false })
}

@Composable
private fun MapTab(vm: FlightViewModel, onRegions: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val regions by vm.regions.collectAsStateWithLifecycle()
    val c = LocalInstrumentColors.current
    Group("View") {
        SegmentedRow("Style", MapStyleKind.entries, s.mapStyle, { if (it == MapStyleKind.AVIATION_DARK) "Dark" else "Light" }) { v -> vm.update { it.copy(mapStyle = v) } }
        SegmentedRow("Orientation", MapOrientation.entries, s.mapOrientation, { it.label }) { v -> vm.update { it.copy(mapOrientation = v) } }
        SwitchRow("Keep aircraft centered", null, s.autoCenter) { v -> vm.update { it.copy(autoCenter = v) } }
        SwitchRow("Auto-zoom with speed & altitude", "Panning or zooming turns it off", s.autoZoom) { v -> vm.update { it.copy(autoZoom = v) } }
    }
    Group("Offline maps") {
        ActionRow("Downloaded regions", "${regions.size} regions · ${Fmt.bytes(regions.sumOf { it.sizeBytes })}") { onRegions() }
        SwitchRow("Automatic region caching", "Around first fix and along planned route", s.autoRegionCache) { v -> vm.update { it.copy(autoRegionCache = v) } }
        SwitchRow("Wi-Fi only downloads", null, s.wifiOnlyDownloads) { v -> vm.update { it.copy(wifiOnlyDownloads = v) } }
        SliderRow("Maximum cache", s.maxCacheMb, 50..4000, 50, "MB") { v -> vm.update { it.copy(maxCacheMb = v) } }
    }
    Group("Vector tile source") {
        SegmentedRow(null, TileProvider.entries, s.tileProvider, { when (it) { TileProvider.OPENFREEMAP -> "OpenFreeMap"; TileProvider.CUSTOM -> "Custom"; TileProvider.NONE -> "Offline only" } }) { v -> vm.update { it.copy(tileProvider = v) } }
        if (s.tileProvider == TileProvider.CUSTOM) {
            TextRow("TileJSON URL (OpenMapTiles schema)", s.customTileJsonUrl) { v -> vm.update { it.copy(customTileJsonUrl = v.trim()) } }
            TextRow("Glyphs URL ({fontstack}/{range})", s.customGlyphsUrl) { v -> vm.update { it.copy(customGlyphsUrl = v.trim()) } }
            TextRow("Offline download style URL", s.customOfflineStyleUrl) { v -> vm.update { it.copy(customOfflineStyleUrl = v.trim()) } }
        }
        Text(when (s.tileProvider) {
            TileProvider.OPENFREEMAP -> "© OpenStreetMap contributors, OpenMapTiles; tiles by OpenFreeMap. Base vectors: Natural Earth."
            TileProvider.CUSTOM -> "Include any API key in the URLs and respect the provider's licence."
            TileProvider.NONE -> "Embedded Natural Earth vectors only – no network requests."
        }, fontSize = 11.sp, color = c.textDim)
    }
    Group("Route colours") {
        Expandable("Speed & altitude colour thresholds") {
            SpeedBandsEditor(s) { v -> vm.update { it.copy(speedBandsKts = v) } }
            AltBandsEditor(s) { v -> vm.update { it.copy(altitudeBandsFt = v) } }
        }
    }
}

@Composable
private fun DisplayTab(vm: FlightViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val click = LocalClick.current
    Group("Appearance") {
        SegmentedRow("Theme", ThemeMode.entries, s.theme, { if (it == ThemeMode.SYSTEM) "System" else it.label }) { v -> vm.update { it.copy(theme = v) } }
        SegmentedRow("Map info panel", InfoLayout.entries, s.infoLayout, { it.label }) { v -> vm.update { it.copy(infoLayout = v) } }
        SwitchRow("Keep screen on while tracking", null, s.keepScreenOn) { v -> vm.update { it.copy(keepScreenOn = v) } }
    }
    Group("Units") {
        SegmentedRow("Speed", SpeedUnit.entries, s.speedUnit, { it.label }) { v -> vm.update { it.copy(speedUnit = v) } }
        SegmentedRow("Altitude", AltitudeUnit.entries, s.altitudeUnit, { "${it.label} · ${it.vsLabel}" }) { v -> vm.update { it.copy(altitudeUnit = v) } }
    }
    Group("Sound & haptics") {
        SwitchRow("Flight event sounds", null, s.soundEnabled) { v -> vm.update { it.copy(soundEnabled = v) } }
        SwitchRow("Button click sounds", null, s.clickSound) { v -> vm.update { it.copy(clickSound = v) } }
        SwitchRow("Button vibration", null, s.clickHaptics) { v -> vm.update { it.copy(clickHaptics = v) }; if (v) click() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", fontSize = 14.sp, color = LocalInstrumentColors.current.text, modifier = Modifier.width(70.dp))
            Slider(s.soundVolume, { v -> vm.update { it.copy(soundVolume = v) } }, Modifier.weight(1f))
            Text("${(s.soundVolume * 100).roundToInt()}%", fontSize = 13.sp, color = Pm.Cyan, modifier = Modifier.width(44.dp))
        }
        Expandable("Individual event sounds") {
            Text("Tap to toggle · ▶ to preview", fontSize = 11.sp, color = LocalInstrumentColors.current.textDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FlightEventType.entries.forEach { t ->
                    val on = t.name !in s.disabledSounds
                    FilterChip(
                        selected = on, enabled = s.soundEnabled,
                        onClick = { click(); vm.update { it.copy(disabledSounds = if (on) it.disabledSounds + t.name else it.disabledSounds - t.name) } },
                        label = { Text(shortLabel(t), fontSize = 12.sp) },
                        trailingIcon = { Icon(Icons.Default.PlayArrow, "Preview", Modifier.size(18.dp).clickable { vm.previewSound(t) }) },
                    )
                }
            }
        }
    }
}

private fun shortLabel(t: FlightEventType) = when (t) {
    FlightEventType.GPS_ACQUIRED -> "GPS acquired"
    FlightEventType.GPS_LOST -> "GPS lost"
    FlightEventType.TAKEOFF_SPEED -> "Takeoff speed"
    FlightEventType.TEN_THOUSAND -> "10,000 ft"
    FlightEventType.CRUISE -> "Cruise"
    FlightEventType.MAX_CRUISE_SPEED -> "Max cruise speed"
    FlightEventType.TOP_OF_DESCENT -> "Top of descent"
    FlightEventType.LANDING -> "Landing"
    FlightEventType.FLIGHT_COMPLETE -> "Flight complete"
}

@Composable
private fun SensorsTab(vm: FlightViewModel) {
    val att by vm.attitude.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val c = LocalInstrumentColors.current
    Group("HUD attitude") {
        Text(if (att.calibrated) "Calibrated to your mounting" else "Not calibrated – shows the phone's tilt", fontSize = 13.sp, color = c.text)
        Text("Fix the phone aligned with the aircraft while it is level, then set level.", fontSize = 11.sp, color = c.textDim)
        Row {
            Button(onClick = vm::calibrateHud) { Text("Set level now") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = vm::clearHudCalibration) { Text("Reset") }
        }
        if (att.available) InfoRow("Current pitch / roll", "%+.1f° / %+.1f°".format(att.pitchDeg, att.rollDeg))
    }
    Group("Barometer") {
        if (att.pressureHpa == null) Text("No barometer data on this device.", fontSize = 13.sp, color = c.textDim)
        att.pressureHpa?.let { InfoRow("Pressure", "%.1f hPa".format(it)) }
        att.pressureAltitudeM?.let { InfoRow("Phone pressure altitude", Fmt.altitude(it.toDouble(), s.altitudeUnit)) }
        if (att.pressureHpa != null) Text("Phone-derived estimate; reflects cabin pressure in flight.", fontSize = 11.sp, color = c.textDim)
        if (att.pressureHpa != null && st.altitudeM != null)
            TextButton(onClick = { vm.sensors.calibratePressureAltitude(st.altitudeM!!, att.pressureHpa!!) }) { Text("Calibrate to GPS altitude") }
    }
    Group("Available sensors") {
        vm.sensors.availability().forEach { a -> InfoRow(a.name, if (a.present) "Available" else "Not present") }
    }
}

@Composable
private fun SimulationTab(vm: FlightViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val sim by vm.sim.collectAsStateWithLifecycle()
    Group("Mock flight") {
        SwitchRow("Enable mock flight", "Replaces real GPS; marked SIM everywhere", s.simEnabled) { v -> vm.update { it.copy(simEnabled = v) } }
        SegmentedRow("Route (EWR → MIA)", SimRoute.entries, s.simRoute, { if (it == SimRoute.PROCEDURAL) "Procedural" else "Recorded JSONL" }) { v -> vm.update { it.copy(simRoute = v) } }
        SwitchRow("Scripted 60 s GPS outage in cruise", null, s.simScriptedOutage) { v -> vm.update { it.copy(simScriptedOutage = v) } }
    }
    if (s.simEnabled) {
        Group("Playback") { SimControls(vm, sim) }
        Group("Failure scenarios") {
            SwitchRow("Weak GPS accuracy", null, sim.weakSignal) { vm.simWeak(it) }
            SwitchRow("Location inaccuracies", null, sim.inaccurate) { vm.simInaccurate(it) }
            SwitchRow("Missing satellite info", null, sim.noSatelliteData) { vm.simNoSatellites(it) }
        }
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title, Modifier.padding(start = 4.dp))
    Panel(Modifier.fillMaxWidth(), padding = 12.dp) { content() }
}

@Composable
private fun Expandable(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val click = LocalClick.current
    val c = LocalInstrumentColors.current
    Column {
        Row(Modifier.fillMaxWidth().clickable { click(); open = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, color = c.text, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = c.textDim)
        }
        AnimatedVisibility(open) { Column { content() } }
    }
}

@Composable
fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalInstrumentColors.current
    val click = LocalClick.current
    Row(Modifier.fillMaxWidth().clickable { click(); onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = c.text)
            if (sub != null) Text(sub, fontSize = 11.sp, color = c.textDim)
        }
        Switch(checked, { click(); onChange(it) }, Modifier.scale(0.85f))
    }
}


/** Compact slider: label and value on the left, slider on the same line. */
@Composable
fun SliderRow(title: String, value: Int, range: IntRange, step: Int, unit: String, sub: String? = null, onChange: (Int) -> Unit) {
    val c = LocalInstrumentColors.current
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(0.48f)) {
            Text(title, fontSize = 13.sp, color = c.text, lineHeight = 15.sp)
            Text("${"%,d".format(local.roundToInt())} $unit", fontSize = 13.sp, color = Pm.Cyan, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, fontSize = 10.sp, color = c.textDim)
        }
        Slider(local, { local = (it / step).roundToInt() * step.toFloat() }, Modifier.weight(0.52f).height(36.dp),
            valueRange = range.first.toFloat()..range.last.toFloat(), onValueChangeFinished = { onChange(local.roundToInt()) })
    }
}

/** Single-choice segmented control (compact replacement for chip groups). */
@Composable
fun <T> SegmentedRow(title: String?, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    val c = LocalInstrumentColors.current
    val click = LocalClick.current
    Column(Modifier.padding(vertical = 4.dp)) {
        if (title != null) Text(title, fontSize = 13.sp, color = c.textDim, modifier = Modifier.padding(bottom = 4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = o == selected, onClick = { click(); onPick(o) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) { Text(label(o), fontSize = 12.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) =
    SegmentedRow(title, options, selected, label, onPick)

@Composable
fun TextRow(title: String, value: String, onDone: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        text, { text = it }, label = { Text(title, fontSize = 12.sp) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        trailingIcon = { if (text != value) TextButton(onClick = { onDone(text) }) { Text("Save") } },
    )
}

@Composable
fun ActionRow(title: String, value: String, onClick: () -> Unit) {
    val c = LocalInstrumentColors.current
    val click = LocalClick.current
    Row(Modifier.fillMaxWidth().clickable { click(); onClick() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = c.text)
            Text(value, fontSize = 12.sp, color = c.textDim)
        }
        Icon(Icons.Default.ChevronRight, null, tint = c.textDim)
    }
}

@Composable
private fun SpeedBandsEditor(s: FlightSettings, onChange: (List<Int>) -> Unit) {
    val b = s.speedBandsKts.sorted()
    SliderRow("Cyan → green", b[0], 50..300, 10, "kt") { onChange(listOf(it, maxOf(it + 10, b[1]), maxOf(it + 20, b[2]))) }
    SliderRow("Green → yellow", b[1], 100..400, 10, "kt") { onChange(listOf(minOf(b[0], it - 10), it, maxOf(it + 10, b[2]))) }
    SliderRow("Yellow → orange", b[2], 150..550, 10, "kt") { onChange(listOf(minOf(b[0], it - 20), minOf(b[1], it - 10), it)) }
}

@Composable
private fun AltBandsEditor(s: FlightSettings, onChange: (List<Int>) -> Unit) {
    val b = s.altitudeBandsFt.sorted()
    val names = listOf("Cyan → green", "Green → blue", "Blue → purple", "Purple → amber")
    b.forEachIndexed { i, v ->
        SliderRow("Altitude ${names.getOrElse(i) { "band ${i + 1}" }}", v, 2_000..50_000, 1_000, "ft") { nv ->
            onChange(b.toMutableList().also { it[i] = nv }.sorted())
        }
    }
}
