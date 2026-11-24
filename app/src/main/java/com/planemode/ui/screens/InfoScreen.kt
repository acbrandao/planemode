package com.planemode.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.planemode.calc.EtaBasis
import com.planemode.ui.FlightViewModel
import com.planemode.ui.Fmt
import com.planemode.ui.components.*
import com.planemode.ui.theme.LocalInstrumentColors
import com.planemode.ui.theme.Pm

@Composable
fun InfoScreen(vm: FlightViewModel, onSatellites: () -> Unit, onSettings: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val att by vm.attitude.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val c = LocalInstrumentColors.current

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader("Flight information", onSatellites, onSettings) { if (st.simulated) SimBadge(Modifier.padding(end = 8.dp)) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigPanel("Ground speed", Fmt.speedValue(st.groundSpeedMps, s.speedUnit), s.speedUnit.label, "GPS-derived, not airspeed", Modifier.weight(1f))
                BigPanel("Altitude", Fmt.altitudeValue(st.altitudeM, s.altitudeUnit), s.altitudeUnit.label, "GNSS altitude", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigPanel("Track", Fmt.heading(st.courseDeg), Fmt.cardinal(st.courseDeg), "GNSS course over ground", Modifier.weight(1f))
                BigPanel("Climb / descent", Fmt.vsValue(st.verticalSpeedMps, s.altitudeUnit), s.altitudeUnit.vsLabel, null, Modifier.weight(1f), vsColor(st.verticalSpeedMps).let { if (it == Pm.White) c.text else it })
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigPanel("Distance traveled", Fmt.distanceValue(st.distanceM, s.speedUnit), s.speedUnit.distanceLabel, null, Modifier.weight(1f))
                BigPanel("Elapsed", Fmt.duration(st.elapsedMs), null, st.startedEpochMs?.let { "Started ${Fmt.clock(it)}" }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val eta = st.eta
                BigPanel("Remaining", if (st.arrival != null) Fmt.distanceValue(st.remainingM, s.speedUnit) else Fmt.NONE, s.speedUnit.distanceLabel,
                    st.arrival?.let { "Straight line to ${it.iata}" } ?: "Set an arrival airport", Modifier.weight(1f), Pm.Cyan)
                BigPanel("Time remaining", eta?.remainingMs?.let { Fmt.hoursMinutes(it) } ?: Fmt.NONE, null,
                    when (eta?.basis) {
                        EtaBasis.GPS_GROUND_SPEED -> "ETA ${Fmt.clock(st.nowEpochMs + eta.remainingMs!!)} · smoothed GPS speed"
                        EtaBasis.TYPICAL_CRUISE -> "ETA ${Fmt.clock(st.nowEpochMs + eta.remainingMs!!)} · est. at typical cruise"
                        else -> if (st.arrival != null) "Position or speed unreliable" else "Set an arrival airport"
                    }, Modifier.weight(1f), Pm.Cyan)
            }
            Text("Estimates only – not official airline flight information.", color = c.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))

            SectionTitle("Flight")
            Panel(Modifier.fillMaxWidth()) {
                InfoRow("Aircraft", st.aircraft?.displayName ?: "Not selected")
                InfoRow("Departure", st.departure?.let { "${it.iata} / ${it.icao} · ${it.name}" } ?: Fmt.NONE)
                InfoRow("Arrival", st.arrival?.let { "${it.iata} / ${it.icao} · ${it.name}" } ?: Fmt.NONE)
                InfoRow("Route distance", st.routeTotalM?.let { Fmt.distance(it, s.speedUnit) + " (great circle)" } ?: Fmt.NONE)
                InfoRow("Flight phase", st.phase.label)
                InfoRow("Max altitude / speed", "${Fmt.altitude(st.maxAltitudeM.takeIf { it > 0 }, s.altitudeUnit)} · ${Fmt.speed(st.maxSpeedMps.takeIf { it > 0 }, s.speedUnit)}")
                st.aircraft?.let { a ->
                    InfoRow("Reference cruise", "~${a.typicalCruiseKts} kt typical · ~${a.maxCruiseKts} kt max (approx.)")
                    InfoRow("Reference altitude", "FL${a.typicalCruiseAltFt / 100} typical · ${"%,d".format(a.maxAltFt)} ft max (approx.)")
                }
            }
            SectionTitle("Attitude (device-derived estimate)")
            Panel(Modifier.fillMaxWidth()) {
                InfoRow("Estimated pitch", if (att.available) "%+.1f°".format(att.pitchDeg) else "Unavailable")
                InfoRow("Estimated roll", if (att.available) "%+.1f°".format(att.rollDeg) else "Unavailable")
                InfoRow("Calibration", if (att.calibrated) "Calibrated to mounting" else "Not calibrated – reflects phone tilt")
                InfoRow("Phone compass", att.deviceAzimuthDeg?.let { "${Fmt.heading(it.toDouble())} (phone orientation, magnetic)" } ?: "Unavailable")
                att.pressureAltitudeM?.let { InfoRow("Cabin pressure altitude", "${Fmt.altitude(it.toDouble(), s.altitudeUnit)} (phone barometer; cabin is pressurised)") }
            }
            SectionTitle("Positioning")
            Panel(Modifier.fillMaxWidth()) {
                InfoRow("GPS status", st.gpsState.label + if (st.simulated) " (simulated)" else "")
                InfoRow("Horizontal accuracy", Fmt.meters(st.hAccM))
                InfoRow("Vertical accuracy", Fmt.meters(st.vAccM))
                InfoRow("Last valid GPS update", st.sinceLastFixMs?.let { "${Fmt.shortDuration(it)} ago (${Fmt.clockSeconds(st.lastFixEpochMs)})" } ?: Fmt.NONE)
                InfoRow("Latitude", Fmt.lat(st.lat) + if (st.positionEstimated) " (estimated)" else "")
                InfoRow("Longitude", Fmt.lon(st.lon) + if (st.positionEstimated) " (estimated)" else "")
                if (st.positionEstimated) InfoRow("Position uncertainty", Fmt.meters(st.uncertaintyM))
            }
            if (events.isNotEmpty()) {
                SectionTitle("Flight events")
                Panel(Modifier.fillMaxWidth()) {
                    events.takeLast(20).reversed().forEach { e -> InfoRow(Fmt.clockSeconds(e.epochMs), e.type.label) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BigPanel(label: String, value: String, unit: String?, sub: String?, modifier: Modifier, color: androidx.compose.ui.graphics.Color = LocalInstrumentColors.current.text) {
    Panel(modifier) { Metric(label, value, unit, valueSize = 30.sp, valueColor = color, sub = sub) }
}

@Composable
fun InfoRow(label: String, value: String) {
    val c = LocalInstrumentColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = c.textDim, fontSize = 13.sp, modifier = Modifier.weight(0.42f))
        Text(value, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(0.58f))
    }
}
