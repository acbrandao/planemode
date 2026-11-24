package com.planemode.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.planemode.calc.Geo
import com.planemode.calc.Units
import com.planemode.map.FlightMap
import com.planemode.model.SilhouetteKind
import com.planemode.ui.FlightViewModel
import com.planemode.ui.Fmt
import com.planemode.ui.components.*
import com.planemode.ui.theme.LocalInstrumentColors
import com.planemode.ui.theme.Pm

@Composable
fun HomeScreen(vm: FlightViewModel, onFlightStarted: () -> Unit, onSettings: () -> Unit, onSatellites: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val sim by vm.sim.collectAsStateWithLifecycle()
    val aircraftList by vm.aircraft.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val c = LocalInstrumentColors.current
    val click = com.planemode.ui.LocalClick.current
    var pickDep by remember { mutableStateOf(false) }
    var pickArr by remember { mutableStateOf(false) }
    var pickAircraft by remember { mutableStateOf(false) }
    var permissionMsg by remember { mutableStateOf<String?>(null) }

    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || s.simEnabled) { vm.startFlight(); onFlightStarted() }
        else permissionMsg = "Precise location permission is required to track a real flight. You can enable simulation in Settings to try planeMode without GPS."
    }
    fun start() {
        click()
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (granted() || s.simEnabled) { vm.startFlight(); onFlightStarted() }
        else launcher.launch(perms.toTypedArray())
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        // Slowly moving vector map background (embedded Natural Earth vectors work fully offline)
        FlightMap(Modifier.fillMaxSize(), s, null, null, interactive = false)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0.55f), c.background.copy(alpha = 0.92f), c.background))))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(buildBrand(), fontSize = 34.sp, fontWeight = FontWeight.Light, color = c.text)
                    Text("Your flight. Your instruments. Your view.", color = c.textDim, fontSize = 13.sp)
                }
                IconButton(onClick = { click(); onSatellites() }) { Icon(Icons.Default.SatelliteAlt, "Satellites", tint = c.text) }
                IconButton(onClick = { click(); onSettings() }) { Icon(Icons.Default.Settings, "Settings", tint = c.text) }
            }

            HeroAircraft(st.aircraft?.silhouetteKind ?: SilhouetteKind.WIDEBODY, Modifier.fillMaxWidth().height(170.dp))

            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GpsChip(st.gpsState, s.simEnabled, onClick = { click(); onSatellites() })
                    Spacer(Modifier.weight(1f))
                    if (s.simEnabled) SimBadge()
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Metric("Latitude", Fmt.lat(st.lat), valueSize = 17.sp, modifier = Modifier.weight(1f))
                    Metric("Longitude", Fmt.lon(st.lon), valueSize = 17.sp, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Metric("Altitude (GPS)", Fmt.altitudeValue(st.altitudeM, s.altitudeUnit), s.altitudeUnit.label, valueSize = 20.sp, modifier = Modifier.weight(1f))
                    Metric("Ground speed", Fmt.speedValue(st.groundSpeedMps, s.speedUnit), s.speedUnit.label, valueSize = 20.sp, modifier = Modifier.weight(1f))
                }
                st.gpsReason?.takeIf { st.gpsState == com.planemode.model.GpsState.UNAVAILABLE }?.let {
                    Spacer(Modifier.height(6.dp)); Text(it, color = Pm.Red, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            if (st.active) {
                Button(onClick = { click(); onFlightStarted() }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Default.Map, null); Spacer(Modifier.width(8.dp))
                    Text("FLIGHT IN PROGRESS · ${Fmt.duration(st.elapsedMs)}", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { click(); vm.endFlight() }, modifier = Modifier.fillMaxWidth()) { Text("END FLIGHT") }
            } else {
                Button(
                    onClick = ::start, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Pm.Cyan, contentColor = Pm.Navy),
                ) {
                    Icon(Icons.Default.FlightTakeoff, null); Spacer(Modifier.width(10.dp))
                    Text("START FLIGHT", fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 2.sp)
                }
                if (st.monitoring) Text("Auto flight tracking armed – the flight starts automatically above ${s.autoStartKts} kt.",
                    color = Pm.Green, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            Panel(Modifier.fillMaxWidth()) {
                PlanRow(Icons.Default.Flight, "Aircraft", st.aircraft?.displayName ?: "Not selected (optional)") { pickAircraft = true }
                HorizontalDivider(color = c.outline)
                PlanRow(Icons.Default.FlightTakeoff, "Departure", st.departure?.let { "${it.iata} · ${it.name}" } ?: "Optional") { pickDep = true }
                HorizontalDivider(color = c.outline)
                PlanRow(Icons.Default.FlightLand, "Arrival", st.arrival?.let { "${it.iata} · ${it.name}" } ?: "Optional") { pickArr = true }
                val d = st.departure; val a = st.arrival
                if (d != null && a != null) {
                    val dist = Geo.distanceM(d.lat, d.lon, a.lat, a.lon)
                    val cruise = Units.ktsToMps((st.aircraft?.typicalCruiseKts ?: 450).toDouble())
                    val durMs = (dist / cruise * 1000).toLong() + 30 * 60_000L
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Metric("Great-circle distance", Fmt.distanceValue(dist, s.speedUnit), s.speedUnit.distanceLabel, valueSize = 20.sp, modifier = Modifier.weight(1f))
                        Metric("Est. flight time", Fmt.hoursMinutes(durMs), valueSize = 20.sp, modifier = Modifier.weight(1f))
                    }
                    Text("Approximate: direct great-circle route at ${if (st.aircraft != null) "typical family cruise" else "a generic 450 kt"} plus 30 min for taxi, climb and descent. Actual airline routes vary.",
                        color = c.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (s.simEnabled) {
                Spacer(Modifier.height(12.dp))
                SimControls(vm, sim)
            }
            permissionMsg?.let { Text(it, color = Pm.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(24.dp))
            Text("planeMode uses your phone's GNSS receiver. Ground speed is GPS-derived (not airspeed). Not for navigation.",
                color = c.textDim, fontSize = 11.sp)
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }

    if (pickDep) AirportPickerDialog("Departure airport", vm::searchAirports, { vm.setDeparture(it); pickDep = false }, { pickDep = false })
    if (pickArr) AirportPickerDialog("Arrival airport", vm::searchAirports, { vm.setArrival(it); pickArr = false }, { pickArr = false })
    if (pickAircraft) AircraftPickerDialog(aircraftList, s.selectedAircraftId, { vm.selectAircraft(it); pickAircraft = false }, { pickAircraft = false })
}

private fun buildBrand() = androidx.compose.ui.text.buildAnnotatedString {
    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Light)); append("plane"); pop()
    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Pm.Cyan)); append("Mode"); pop()
}

@Composable
private fun PlanRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClick: () -> Unit) {
    val c = LocalInstrumentColors.current
    val click = com.planemode.ui.LocalClick.current
    Row(Modifier.fillMaxWidth().clickable { click(); onClick() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Pm.Cyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), fontSize = 10.sp, letterSpacing = 1.2.sp, color = c.textDim)
            Text(value, color = c.text, fontSize = 15.sp, maxLines = 1)
        }
        Icon(Icons.Default.ChevronRight, null, tint = c.textDim)
    }
}

@Composable
private fun HeroAircraft(kind: SilhouetteKind, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "hero")
    val bob by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "sweep")
    Canvas(modifier) {
        val sz = size.height * 0.95f
        val cx = size.width / 2; val cy = size.height / 2 + bob
        // range rings
        for (i in 1..3) drawCircle(Pm.Cyan.copy(alpha = 0.10f + 0.05f * (3 - i)), radius = sz * 0.22f * i, center = androidx.compose.ui.geometry.Offset(cx, size.height / 2), style = Stroke(1.dp.toPx()))
        drawCircle(Pm.Cyan.copy(alpha = 0.25f * (1 - sweep)), radius = sz * 0.7f * sweep, center = androidx.compose.ui.geometry.Offset(cx, size.height / 2), style = Stroke(2.dp.toPx()))
        rotate(-28f, androidx.compose.ui.geometry.Offset(cx, cy)) {
            translate(cx - sz / 2, cy - sz / 2) {
                val path = AircraftShapes.topView(kind, sz)
                drawPath(path, Brush.verticalGradient(listOf(Color.White, Color(0xFFB8C7DA)), 0f, sz))
                drawPath(path, Pm.Cyan.copy(alpha = 0.5f), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

@Composable
fun SimControls(vm: FlightViewModel, sim: com.planemode.sim.SimState, compact: Boolean = false) {
    val c = LocalInstrumentColors.current
    val click = com.planemode.ui.LocalClick.current
    Panel(Modifier.fillMaxWidth(), padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SimBadge()
            Spacer(Modifier.width(8.dp))
            Text("${sim.route.label} · ${sim.phase} · T+${Fmt.duration((sim.virtualSec * 1000).toLong())}", color = c.textDim, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }
        LinearProgressIndicator(progress = { sim.progress.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(2.dp)), color = Pm.Amber)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!sim.running) IconButton(onClick = { click(); if (sim.started) vm.simResume() else vm.simStart() }) { Icon(Icons.Default.PlayArrow, "Start / resume", tint = c.text) }
            else IconButton(onClick = { click(); vm.simPause() }) { Icon(Icons.Default.Pause, "Pause", tint = c.text) }
            IconButton(onClick = { click(); vm.simReset() }) { Icon(Icons.Default.RestartAlt, "Reset", tint = c.text) }
            listOf(1, 5, 20, 60).forEach { x ->
                FilterChip(selected = sim.speed == x, onClick = { click(); vm.simSpeed(x) }, label = { Text("${x}x", fontSize = 12.sp) }, modifier = Modifier.padding(horizontal = 2.dp))
            }
        }
        if (!compact) Row {
            TextButton(onClick = { click(); vm.simDropGps() }) { Text(if (sim.gpsDropped) "GPS DROPPED" else "DROP GPS 30s", color = if (sim.gpsDropped) Pm.Red else Pm.Amber, fontSize = 12.sp) }
            TextButton(onClick = { click(); vm.simRecoverGps() }) { Text("RECOVER", fontSize = 12.sp) }
            TextButton(onClick = { click(); vm.simWeak(!sim.weakSignal) }) { Text(if (sim.weakSignal) "WEAK: ON" else "WEAK", fontSize = 12.sp) }
        }
    }
}
