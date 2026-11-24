package com.planemode.ui.screens

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.planemode.calc.Units
import com.planemode.model.GpsState
import com.planemode.ui.FlightViewModel
import com.planemode.ui.Fmt
import com.planemode.ui.components.GpsChip
import com.planemode.ui.components.Metric
import com.planemode.ui.components.ScreenHeader
import com.planemode.ui.theme.Pm
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val HudBg = Color(0xFF060B16)
private val HudLine = Color(0xFFE8F0FA)

/** Keeps an angle continuous (no 359→0 spin) for smooth animation. */
@Composable
private fun rememberUnwrapped(target: Float): Float {
    var acc by remember { mutableFloatStateOf(target) }
    val d = ((target - acc) % 360f + 540f) % 360f - 180f
    acc += d
    return acc
}

@Composable
fun HudScreen(vm: FlightViewModel, onSatellites: () -> Unit, onSettings: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val att by vm.attitude.collectAsStateWithLifecycle()
    val tm = rememberTextMeasurer()
    var showCal by remember { mutableStateOf(false) }
    val click = com.planemode.ui.LocalClick.current

    val gnssHeading = st.courseDeg?.takeIf { (st.groundSpeedMps ?: 0.0) > 3 && st.gpsState != GpsState.LOST }
    val heading = gnssHeading ?: att.deviceAzimuthDeg?.toDouble()
    val headingSource = if (gnssHeading != null) "GNSS TRACK" else if (heading != null) "DEVICE (MAG)" else "NO HEADING"
    val hdgAnim by animateFloatAsState(rememberUnwrapped((heading ?: 0.0).toFloat()), tween(600, easing = LinearEasing), label = "hdg")
    val spd by animateFloatAsState(Units.speed(st.groundSpeedMps ?: 0.0, s.speedUnit).toFloat(), tween(900, easing = LinearEasing), label = "spd")
    val alt by animateFloatAsState(Units.altitude(st.altitudeM ?: 0.0, s.altitudeUnit).toFloat(), tween(900, easing = LinearEasing), label = "alt")
    val vs = st.verticalSpeedMps?.let { Units.verticalSpeed(it, s.altitudeUnit) }?.toFloat() ?: 0f
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val instrument: @Composable (Modifier) -> Unit = { mod ->
        Canvas(mod) {
            val compassH = size.height * (if (landscape) 0.2f else 0.17f)
            val tapeW = size.width * (if (landscape) 0.16f else 0.2f)
            val adiRect = Rect(tapeW + 8.dp.toPx(), compassH + 8.dp.toPx(), size.width - tapeW - 8.dp.toPx(), size.height - 4.dp.toPx())
            drawCompass(tm, hdgAnim, heading != null, Rect(0f, 0f, size.width, compassH), headingSource)
            drawAdi(tm, att.pitchDeg, att.rollDeg, att.available, adiRect)
            drawTape(tm, Rect(0f, adiRect.top, tapeW, adiRect.bottom), spd, unitsPerTick = 10f, labelEvery = 2, pxPerUnit = 2.6.dp.toPx(), label = s.speedUnit.label.uppercase(), left = true, valid = st.groundSpeedMps != null)
            drawTape(tm, Rect(size.width - tapeW, adiRect.top, size.width, adiRect.bottom), alt, unitsPerTick = if (s.altitudeUnit.name == "FEET") 100f else 50f, labelEvery = 5,
                pxPerUnit = if (s.altitudeUnit.name == "FEET") 0.12.dp.toPx() else 0.4.dp.toPx(), label = s.altitudeUnit.label.uppercase(), left = false, valid = st.altitudeM != null, trend = vs)
        }
    }
    val vsMetric = @Composable { m: Modifier -> Metric("V/S", Fmt.vsValue(st.verticalSpeedMps, s.altitudeUnit), s.altitudeUnit.vsLabel, m, valueColor = vsColor(st.verticalSpeedMps), valueSize = 20.sp) }
    val hdgMetric = @Composable { m: Modifier -> Metric("Heading", Fmt.heading(heading), modifier = m, valueColor = HudLine, valueSize = 20.sp, sub = headingSource) }
    val attMetric = @Composable { m: Modifier -> Metric("Pitch / Roll", if (att.available) "%+.0f° / %+.0f°".format(att.pitchDeg, att.rollDeg) else "--", modifier = m, valueColor = HudLine, valueSize = 20.sp) }
    val disclaimer = if (att.calibrated) "Device-derived attitude estimate (calibrated to your mounting). Not the aircraft's measured attitude."
        else "Device-derived attitude estimate – shows the PHONE's tilt, not the aircraft's. Calibrate with the phone fixed and the aircraft level."

    if (landscape) {
        Row(Modifier.fillMaxSize().background(HudBg).padding(8.dp)) {
            instrument(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(210.dp).fillMaxHeight().padding(start = 12.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                GpsChip(st.gpsState, st.simulated)
                vsMetric(Modifier); hdgMetric(Modifier); attMetric(Modifier)
                Text(disclaimer, color = Pm.Grey, fontSize = 9.sp)
                TextButton(onClick = { click(); showCal = true }) { Text(if (att.calibrated) "RECALIBRATE" else "CALIBRATE", color = Pm.Cyan) }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(HudBg)) {
            ScreenHeader("Cockpit HUD", onSatellites, onSettings) { GpsChip(st.gpsState, st.simulated) }
            instrument(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                vsMetric(Modifier.weight(1f)); hdgMetric(Modifier.weight(1f)); attMetric(Modifier.weight(1.2f))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(disclaimer, color = Pm.Grey, fontSize = 10.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { click(); showCal = true }) { Text(if (att.calibrated) "RECALIBRATE" else "CALIBRATE", color = Pm.Cyan) }
            }
        }
    }

    if (showCal) AlertDialog(
        onDismissRequest = { showCal = false },
        title = { Text("HUD calibration") },
        text = { Text("Fix the phone in a stable position aligned with the aircraft (for example upright in the seat-back pocket, screen facing you, or flat on the tray table pointing forward) while the aircraft is level – e.g. in cruise or at the gate. Then tap \"Set level\". The current phone orientation becomes zero pitch and roll.") },
        confirmButton = { Button(onClick = { vm.calibrateHud(); showCal = false }) { Text("Set level") } },
        dismissButton = { TextButton(onClick = { vm.clearHudCalibration(); showCal = false }) { Text("Reset") } },
    )
}

private fun DrawScope.drawAdi(tm: TextMeasurer, pitch: Float, roll: Float, available: Boolean, r: Rect) {
    val corner = CornerRadius(24.dp.toPx())
    val c = r.center
    val pxPerDeg = r.height / 50f
    val clip = Path().apply { addRoundRect(RoundRect(r, corner)) }
    clipPath(clip) {
        rotate(-roll, c) {
            translate(0f, pitch * pxPerDeg) {
                val big = r.maxDimension * 2
                drawRect(Brush.verticalGradient(listOf(Pm.SkyTop, Pm.Sky), c.y - big, c.y), Offset(c.x - big, c.y - big), Size(big * 2, big))
                drawRect(Brush.verticalGradient(listOf(Pm.Ground, Pm.GroundDeep), c.y, c.y + big), Offset(c.x - big, c.y), Size(big * 2, big))
                drawLine(HudLine, Offset(c.x - big, c.y), Offset(c.x + big, c.y), 2.dp.toPx())
                // Pitch ladder
                for (p in -60..60 step 5) {
                    if (p == 0) continue
                    val y = c.y - p * pxPerDeg
                    val half = if (p % 10 == 0) r.width * 0.16f else r.width * 0.07f
                    drawLine(HudLine.copy(alpha = 0.9f), Offset(c.x - half, y), Offset(c.x + half, y), 1.5.dp.toPx())
                    if (p % 10 == 0) {
                        val st = TextStyle(color = HudLine, fontSize = 10.sp)
                        drawText(tm, "${abs(p)}", Offset(c.x + half + 4.dp.toPx(), y - 7.dp.toPx()), st)
                        drawText(tm, "${abs(p)}", Offset(c.x - half - 18.dp.toPx(), y - 7.dp.toPx()), st)
                    }
                }
            }
        }
        // Bank scale (fixed) with roll pointer
        val radius = r.height * 0.40f
        val arcTop = Offset(c.x, c.y)
        for (a in listOf(-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60)) {
            val len = if (a % 30 == 0) 14.dp.toPx() else 8.dp.toPx()
            val rad = Math.toRadians((a - 90).toDouble())
            val p1 = Offset(arcTop.x + (radius * cos(rad)).toFloat(), arcTop.y + (radius * sin(rad)).toFloat())
            val p2 = Offset(arcTop.x + ((radius + len) * cos(rad)).toFloat(), arcTop.y + ((radius + len) * sin(rad)).toFloat())
            drawLine(HudLine, p1, p2, 2.dp.toPx())
        }
        drawArc(HudLine, -150f, 120f, false, Offset(c.x - radius, c.y - radius), Size(radius * 2, radius * 2), style = Stroke(1.5.dp.toPx()))
        rotate(-roll, c) {
            val tip = Offset(c.x, c.y - radius + 2.dp.toPx())
            drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(tip.x - 8.dp.toPx(), tip.y + 14.dp.toPx()); lineTo(tip.x + 8.dp.toPx(), tip.y + 14.dp.toPx()); close() }, Pm.Amber)
        }
        // Fixed aircraft reference symbol
        val wing = r.width * 0.2f; val t = 4.dp.toPx()
        listOf(-1f, 1f).forEach { d ->
            drawPath(Path().apply {
                moveTo(c.x + d * wing * 0.35f, c.y); lineTo(c.x + d * wing * 1.4f, c.y); lineTo(c.x + d * wing * 1.4f, c.y + t)
                lineTo(c.x + d * (wing * 0.35f + t), c.y + t); lineTo(c.x + d * (wing * 0.35f + t), c.y + t * 4); lineTo(c.x + d * wing * 0.35f, c.y + t * 4); close()
            }, Pm.Amber)
        }
        drawCircle(Pm.Amber, t, c)
        if (!available) {
            drawRect(Color.Black.copy(alpha = 0.55f), r.topLeft, r.size)
            drawText(tm, "ATTITUDE SENSORS UNAVAILABLE", Offset(r.left + 16.dp.toPx(), c.y - 8.dp.toPx()), TextStyle(color = Pm.Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }
    }
    drawRoundRect(HudLine.copy(alpha = 0.3f), r.topLeft, r.size, corner, style = Stroke(1.dp.toPx()))
    val txt = TextStyle(color = HudLine, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    drawText(tm, "P %+d°".format(pitch.roundToInt()), Offset(r.left + 12.dp.toPx(), r.bottom - 24.dp.toPx()), txt)
    drawText(tm, "R %+d°".format(roll.roundToInt()), Offset(r.right - 60.dp.toPx(), r.bottom - 24.dp.toPx()), txt)
}

private fun DrawScope.drawTape(
    tm: TextMeasurer, r: Rect, value: Float, unitsPerTick: Float, labelEvery: Int, pxPerUnit: Float,
    label: String, left: Boolean, valid: Boolean, trend: Float? = null,
) {
    val corner = CornerRadius(12.dp.toPx())
    drawRoundRect(Color(0xFF111A2B), r.topLeft, r.size, corner)
    val cy = r.center.y
    val st = TextStyle(color = HudLine.copy(alpha = 0.85f), fontSize = 11.sp)
    clipRect(r.left, r.top, r.right, r.bottom) {
        val span = r.height / 2 / pxPerUnit
        val first = (((value - span) / unitsPerTick).toInt() - 1)
        val last = (((value + span) / unitsPerTick).toInt() + 1)
        for (i in first..last) {
            val v = i * unitsPerTick
            if (v < 0 && label != "FT" && label != "M") continue
            val y = cy - (v - value) * pxPerUnit
            val major = i % labelEvery == 0
            val len = if (major) 12.dp.toPx() else 6.dp.toPx()
            val x0 = if (left) r.right - len else r.left
            drawLine(HudLine.copy(alpha = 0.7f), Offset(x0, y), Offset(x0 + len, y), 1.5.dp.toPx())
            if (major) {
                val text = "%,d".format(v.roundToInt())
                val m = tm.measure(text, st)
                val tx = if (left) r.right - len - 4.dp.toPx() - m.size.width else r.left + len + 4.dp.toPx()
                drawText(m, topLeft = Offset(tx, y - m.size.height / 2))
            }
        }
        // Trend vector (6-second projection) on the altitude tape
        if (trend != null && abs(trend) > 1) {
            val projected = trend / 60f * 6f
            val x = r.left + 3.dp.toPx()
            drawLine(Pm.Green, Offset(x, cy), Offset(x, cy - projected * pxPerUnit), 4.dp.toPx())
        }
    }
    // Readout box
    val boxH = 34.dp.toPx()
    val box = Rect(r.left + 2.dp.toPx(), cy - boxH / 2, r.right - 2.dp.toPx(), cy + boxH / 2)
    drawRoundRect(Color.Black, box.topLeft, box.size, CornerRadius(6.dp.toPx()))
    drawRoundRect(Pm.Cyan, box.topLeft, box.size, CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
    val txt = if (valid) "%,d".format(value.roundToInt()) else "---"
    val m = tm.measure(txt, TextStyle(color = Pm.White, fontSize = 17.sp, fontWeight = FontWeight.Bold))
    drawText(m, topLeft = Offset(box.center.x - m.size.width / 2, box.center.y - m.size.height / 2))
    val lm = tm.measure(label, TextStyle(color = Pm.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold))
    drawText(lm, topLeft = Offset(r.center.x - lm.size.width / 2, r.top + 4.dp.toPx()))
}

private fun DrawScope.drawCompass(tm: TextMeasurer, heading: Float, valid: Boolean, r: Rect, source: String) {
    val radius = r.width * 0.75f
    val center = Offset(r.center.x, r.top + radius + 22.dp.toPx())
    clipRect(r.left, r.top, r.right, r.bottom) {
        drawCircle(Color(0xFF111A2B), radius, center)
        rotate(-heading, center) {
            for (d in 0 until 360 step 5) {
                val rad = Math.toRadians((d - 90).toDouble())
                val major = d % 30 == 0
                val len = if (d % 10 == 0) 12.dp.toPx() else 6.dp.toPx()
                val p1 = Offset(center.x + (radius * cos(rad)).toFloat(), center.y + (radius * sin(rad)).toFloat())
                val p2 = Offset(center.x + ((radius - len) * cos(rad)).toFloat(), center.y + ((radius - len) * sin(rad)).toFloat())
                drawLine(HudLine.copy(alpha = 0.8f), p1, p2, if (major) 2.dp.toPx() else 1.dp.toPx())
                if (major) {
                    val label = when (d) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> "${d / 10}" }
                    val st = TextStyle(color = if (d % 90 == 0) Pm.Cyan else HudLine, fontSize = if (d % 90 == 0) 15.sp else 12.sp, fontWeight = FontWeight.Bold)
                    val m = tm.measure(label, st)
                    val lp = Offset(center.x + ((radius - len - 14.dp.toPx()) * cos(rad)).toFloat(), center.y + ((radius - len - 14.dp.toPx()) * sin(rad)).toFloat())
                    rotate(d.toFloat(), lp) { drawText(m, topLeft = Offset(lp.x - m.size.width / 2, lp.y - m.size.height / 2)) }
                }
            }
        }
    }
    // Lubber line and readout
    val top = r.top + 2.dp.toPx()
    drawPath(Path().apply { moveTo(center.x, top + 22.dp.toPx()); lineTo(center.x - 7.dp.toPx(), top + 10.dp.toPx()); lineTo(center.x + 7.dp.toPx(), top + 10.dp.toPx()); close() }, Pm.Amber)
    val txt = if (valid) "%03d°".format(((heading.roundToInt() % 360) + 360) % 360) else "---"
    val m = tm.measure(txt, TextStyle(color = Pm.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
    val box = Rect(center.x - m.size.width / 2 - 8.dp.toPx(), top - 2.dp.toPx(), center.x + m.size.width / 2 + 8.dp.toPx(), top + 8.dp.toPx())
    drawText(m, topLeft = Offset(r.left + 12.dp.toPx(), r.top + 4.dp.toPx()))
    drawText(tm, source, Offset(r.left + 12.dp.toPx(), r.top + 4.dp.toPx() + m.size.height), TextStyle(color = Pm.Grey, fontSize = 9.sp))
}
