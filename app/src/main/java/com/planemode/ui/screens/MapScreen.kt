package com.planemode.ui.screens

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.planemode.ui.LocalClick
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.planemode.calc.EtaBasis
import com.planemode.map.FlightMap
import com.planemode.map.MapController
import com.planemode.model.*
import com.planemode.tracking.FlightState
import com.planemode.ui.FlightViewModel
import com.planemode.ui.Fmt
import com.planemode.ui.components.*
import com.planemode.ui.theme.Pm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val OverlayBg = Color(0xE60B1426)

@Composable
fun MapScreen(vm: FlightViewModel, onSatellites: () -> Unit, onSettings: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val track by vm.track.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val sim by vm.sim.collectAsStateWithLifecycle()
    val autoZoom by vm.autoZoomActive.collectAsStateWithLifecycle()
    val click = LocalClick.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var following by rememberSaveable { mutableStateOf(true) }
    var showLegend by rememberSaveable { mutableStateOf(false) }
    // Footer auto-collapses in landscape; in portrait it follows the saved preference.
    var expanded by rememberSaveable(landscape) { mutableStateOf(!landscape && s.infoLayout == InfoLayout.EXPANDED) }
    val controller = remember { MapController() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var overview by rememberSaveable { mutableStateOf(false) }
    // Every new flight starts following the aircraft.
    LaunchedEffect(st.sessionId) { if (st.sessionId != null) { following = true; overview = false } }
    LaunchedEffect(following) {
        if (following && overview) { overview = false; controller.restoreZoomAfterOverview() }
    }
    var banner by remember { mutableStateOf<FlightEvent?>(null) }
    LaunchedEffect(Unit) { vm.banners.collect { banner = it; delay(4_000); banner = null } }
    val focusBottom by animateFloatAsState(
        when { expanded -> if (s.simEnabled) 0.45f else 0.34f; landscape -> 0.08f; else -> 0.14f }, tween(500), label = "focus")
    val focusTop = if (landscape) 0.22f else 0.17f

    Box(Modifier.fillMaxSize().background(Pm.Navy)) {
        FlightMap(
            Modifier.fillMaxSize(), s, st, track, interactive = true, following = following && s.autoCenter,
            onUserGesture = { following = false; overview = false }, controller = controller,
            aircraftKind = st.aircraft?.silhouetteKind ?: SilhouetteKind.NARROWBODY,
            focusTop = focusTop.toDouble(), focusBottom = focusBottom.toDouble(),
            autoZoom = autoZoom,
            onManualZoom = { if (autoZoom) vm.setAutoZoomActive(false) },
        )

        Column(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp)
                .then(if (landscape) Modifier.widthIn(max = 460.dp) else Modifier.fillMaxWidth().padding(end = 0.dp)),
        ) {
            MapHeader(st, s, onSatellites = { click(); onSatellites() }, onSettings = { click(); onSettings() })
            GpsLossBanner(st)
            BannerHost(banner?.type?.label, Pm.Cyan, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
        }

        // Right-side controls
        Column(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = if (landscape) 10.dp else 150.dp, end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (landscape) 6.dp else 8.dp),
        ) {
            MapButton(Icons.Default.Add, "Zoom in") { click(); controller.zoomIn() }
            MapButton(Icons.Default.Remove, "Zoom out") { click(); controller.zoomOut() }
            MapButton(Icons.Default.ZoomOutMap, "Show full route", tint = if (overview) Pm.Cyan else Pm.White, label = "ROUTE") {
                click()
                if (overview) { following = true } // the effect above restores the previous zoom
                else {
                    overview = true
                    following = false
                    scope.launch {
                        delay(60) // let the follow loop stop before animating
                        val pts = buildList {
                            st.departure?.let { add(LatLon(it.lat, it.lon)) }
                            st.arrival?.let { add(LatLon(it.lat, it.lon)) }
                            val tp = track.points
                            val step = (tp.size / 200).coerceAtLeast(1)
                            for (i in tp.indices step step) add(LatLon(tp[i].lat, tp[i].lon))
                            tp.lastOrNull()?.let { add(LatLon(it.lat, it.lon)) }
                            if (st.lat != null && st.lon != null) add(LatLon(st.lat!!, st.lon!!))
                        }
                        with(density) {
                            controller.fitPoints(pts, 40.dp.roundToPx(), (if (landscape) 110.dp else 190.dp).roundToPx(),
                                70.dp.roundToPx(), (if (expanded) 260.dp else 90.dp).roundToPx())
                        }
                    }
                }
            }
            CompassButton(controller.bearing, s.mapOrientation) {
                click()
                vm.update { it.copy(mapOrientation = if (it.mapOrientation == MapOrientation.TRACK_UP) MapOrientation.NORTH_UP else MapOrientation.TRACK_UP) }
            }
            MapButton(Icons.Default.ZoomInMap, if (autoZoom) "Auto-zoom on" else "Auto-zoom off", tint = if (autoZoom) Pm.Cyan else Pm.Grey, label = "AUTO") {
                click()
                val enable = !autoZoom
                vm.setAutoZoomActive(enable)
                if (enable && !s.autoCenter) vm.update { it.copy(autoCenter = true) }
                if (enable) following = true
            }
            MapButton(Icons.Default.Layers, "Legend") { click(); showLegend = !showLegend }
            if (!following || !s.autoCenter) MapButton(Icons.Default.MyLocation, "Re-center", tint = Pm.Cyan) {
                click(); following = true; if (!s.autoCenter) vm.update { it.copy(autoCenter = true) }
            }
        }
        if (showLegend) Legend(s, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = if (landscape) 130.dp else 150.dp, start = 10.dp))

        Column(
            Modifier.align(Alignment.BottomStart).padding(10.dp)
                .then(if (landscape) Modifier.widthIn(max = 560.dp) else Modifier.fillMaxWidth()),
        ) {
            AnimatedVisibility(expanded && s.simEnabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column { SimControls(vm, sim, compact = true); Spacer(Modifier.height(8.dp)) }
            }
            MapFooter(st, s, expanded) {
                click()
                expanded = !expanded
                if (!landscape) vm.update { it.copy(infoLayout = if (expanded) InfoLayout.EXPANDED else InfoLayout.COMPACT) }
            }
        }
    }

    if (st.landedSuggested) {
        AlertDialog(
            onDismissRequest = vm::dismissLanding,
            title = { Text("Probable landing") },
            text = { Text("Ground speed has stayed below the taxi threshold for a sustained period. End this flight?") },
            confirmButton = { Button(onClick = { click(); vm.endFlight() }) { Text("End flight") } },
            dismissButton = { TextButton(onClick = { click(); vm.dismissLanding() }) { Text("Keep tracking") } },
        )
    }
}

@Composable
private fun MapHeader(st: FlightState, s: FlightSettings, onSatellites: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(OverlayBg).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.aircraft?.family ?: "planeMode", color = Pm.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f, false))
                Spacer(Modifier.width(8.dp))
                GpsChip(st.gpsState, st.simulated, onClick = onSatellites)
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Metric("GS", Fmt.speedValue(st.groundSpeedMps, s.speedUnit), s.speedUnit.label, valueSize = 22.sp, modifier = Modifier.weight(1f), valueColor = Pm.White)
                Metric("ALT", Fmt.altitudeValue(st.altitudeM, s.altitudeUnit), s.altitudeUnit.label, valueSize = 22.sp, modifier = Modifier.weight(1.2f), valueColor = Pm.White)
                Metric("TRK", Fmt.heading(st.courseDeg), valueSize = 22.sp, modifier = Modifier.weight(0.9f), valueColor = Pm.White)
            }
        }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Pm.Grey) }
    }
}

/** Collapsible flight information footer. Collapsed: one slim line; expanded: full readouts. */
@Composable
private fun MapFooter(st: FlightState, s: FlightSettings, expanded: Boolean, onToggle: () -> Unit) {
    val eta = st.eta
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(OverlayBg).clickable(onClick = onToggle)
            .animateContentSize(tween(250)).padding(horizontal = 12.dp, vertical = if (expanded) 10.dp else 4.dp),
    ) {
        if (!expanded) {
            // Fixed height, fixed slots and tabular digits so the strip never changes size as values update.
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(1.1f))) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.KeyboardArrowUp, "Expand flight information", tint = Pm.Grey, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    CompactValue(Fmt.duration(st.elapsedMs), null, Pm.White, Modifier.weight(1.15f))
                    CompactValue(Fmt.distanceValue(st.distanceM, s.speedUnit), s.speedUnit.distanceLabel, Pm.White, Modifier.weight(1f))
                    CompactValue(Fmt.vsValue(st.verticalSpeedMps, s.altitudeUnit), s.altitudeUnit.vsLabel, vsColor(st.verticalSpeedMps), Modifier.weight(1.15f))
                    if (st.arrival != null) CompactValue(eta?.remainingMs?.let { Fmt.hoursMinutes(it) } ?: Fmt.NONE, null, Pm.Cyan, Modifier.weight(1f))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.phase.label.uppercase(), color = Pm.Grey, fontSize = 10.sp, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.KeyboardArrowDown, "Collapse flight information", tint = Pm.Grey, modifier = Modifier.size(20.dp))
            }
            Row {
                Metric("Elapsed", Fmt.duration(st.elapsedMs), valueSize = 22.sp, modifier = Modifier.weight(1.2f), valueColor = Pm.White)
                Metric("Distance", Fmt.distanceValue(st.distanceM, s.speedUnit), s.speedUnit.distanceLabel, valueSize = 22.sp, modifier = Modifier.weight(1f), valueColor = Pm.White)
                Metric("V/S", Fmt.vsValue(st.verticalSpeedMps, s.altitudeUnit), s.altitudeUnit.vsLabel, valueSize = 22.sp, modifier = Modifier.weight(1f),
                    valueColor = vsColor(st.verticalSpeedMps))
            }
            if (st.arrival != null) {
                Spacer(Modifier.height(8.dp))
                Row {
                    Metric("To ${st.arrival.iata}", Fmt.distanceValue(st.remainingM, s.speedUnit), s.speedUnit.distanceLabel, valueSize = 20.sp, modifier = Modifier.weight(1.2f), valueColor = Pm.Cyan)
                    Metric("Remaining", eta?.remainingMs?.let { Fmt.hoursMinutes(it) } ?: Fmt.NONE, valueSize = 20.sp, modifier = Modifier.weight(1f), valueColor = Pm.Cyan)
                    Metric("ETA", eta?.remainingMs?.let { Fmt.clock(st.nowEpochMs + it) } ?: Fmt.NONE, valueSize = 20.sp, modifier = Modifier.weight(1f), valueColor = Pm.Cyan)
                }
            }
        }
    }
}

private val Tabular = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun CompactValue(value: String, unit: String?, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip, style = Tabular)
        if (unit != null) Text(" $unit", color = Pm.Grey, fontSize = 10.sp, maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip, modifier = Modifier.padding(bottom = 2.dp))
    }
}

/** Compass-style orientation toggle: the needle always points to true north on screen. */
@Composable
private fun CompassButton(mapBearing: Float, mode: MapOrientation, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(OverlayBg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(26.dp).padding(bottom = 6.dp)) {
            rotate(-mapBearing) {
                val c = center; val h = size.height / 2; val w = size.width * 0.16f
                drawPath(Path().apply { moveTo(c.x, c.y - h); lineTo(c.x - w, c.y); lineTo(c.x + w, c.y); close() }, Pm.Red)
                drawPath(Path().apply { moveTo(c.x, c.y + h); lineTo(c.x - w, c.y); lineTo(c.x + w, c.y); close() }, Pm.White)
            }
        }
        Text(if (mode == MapOrientation.NORTH_UP) "N-UP" else "TRK", color = Pm.Cyan, fontSize = 8.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
    }
}

@Composable
private fun GpsLossBanner(st: FlightState) {
    val text = when {
        st.gpsState == GpsState.ESTIMATED -> "ESTIMATED POSITION · last GPS fix ${Fmt.shortDuration(st.sinceLastFixMs)} ago · ±${((st.uncertaintyM ?: 0.0) / 1000).let { "%.1f".format(it) }} km"
        st.gpsState == GpsState.LOST && st.positionStale -> "GPS LOST · position stale (frozen) · last fix ${Fmt.shortDuration(st.sinceLastFixMs)} ago"
        st.gpsState == GpsState.LOST -> "GPS LOST · last fix ${Fmt.shortDuration(st.sinceLastFixMs)} ago"
        st.gpsState == GpsState.UNAVAILABLE -> st.gpsReason ?: "Location unavailable"
        else -> null
    }
    if (text != null) {
        Text(text, color = Pm.Navy, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if (st.gpsState == GpsState.ESTIMATED) Pm.Amber else Pm.Red).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

fun vsColor(vs: Double?) = when {
    vs == null -> Pm.White
    vs > 1.5 -> Pm.Green
    vs < -1.5 -> Pm.Amber
    else -> Pm.White
}

@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color = Pm.White, label: String? = null, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(OverlayBg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (label == null) Icon(icon, desc, tint = tint)
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp))
            Text(label, color = tint, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Legend(s: FlightSettings, modifier: Modifier) {
    val b = s.speedBandsKts.sorted()
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(OverlayBg).padding(10.dp)) {
        Text("ROUTE (GS)", color = Pm.Grey, fontSize = 10.sp, letterSpacing = 1.sp)
        listOf("< ${b[0]} kt" to Pm.Cyan, "${b[0]}–${b[1]} kt" to Pm.Green, "${b[1]}–${b[2]} kt" to Color(0xFFFACC15), "> ${b[2]} kt" to Color(0xFFFB923C),
            "Estimated" to Pm.EstimatedGrey, "Planned route" to Pm.Cyan.copy(alpha = 0.6f)).forEach { (t, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Box(Modifier.width(18.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c))
                Spacer(Modifier.width(6.dp)); Text(t, color = Pm.White, fontSize = 11.sp)
            }
        }
    }
}
