package com.planemode.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.planemode.calc.Units
import com.planemode.model.AltitudeUnit
import com.planemode.model.SampleSource
import com.planemode.model.SilhouetteKind
import com.planemode.ui.FlightViewModel
import com.planemode.ui.Fmt
import com.planemode.ui.components.AircraftShapes
import com.planemode.ui.components.Metric
import com.planemode.ui.components.Panel
import com.planemode.ui.components.ScreenHeader
import com.planemode.ui.theme.LocalInstrumentColors
import com.planemode.ui.theme.Pm
import kotlin.math.atan2
import kotlin.math.max

private val bandColors = listOf(Pm.Cyan, Pm.Green, Color(0xFF3B82F6), Color(0xFFA855F7), Pm.Amber)

fun altitudeBandColor(altFt: Double, bands: List<Int>): Color {
    val b = bands.sorted()
    val idx = b.indexOfFirst { altFt < it }.let { if (it < 0) b.size else it }
    return bandColors[idx.coerceIn(0, bandColors.lastIndex)]
}

@Composable
fun ProfileScreen(vm: FlightViewModel, onSatellites: () -> Unit, onSettings: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val track by vm.track.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val c = LocalInstrumentColors.current
    var spanIdx by rememberSaveable { mutableIntStateOf(1) }
    val spans = listOf(100_000.0, 400_000.0, 1_200_000.0, -1.0)
    val spanLabels = listOf("100 km", "400 km", "1200 km", "All")
    val measurer = rememberTextMeasurer()
    val currentDist by animateFloatAsState(st.distanceM.toFloat(), tween(900), label = "dist")

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader("Altitude profile", onSatellites, onSettings)
        Row(Modifier.padding(horizontal = 16.dp)) {
            Metric("Ground speed", Fmt.speedValue(st.groundSpeedMps, s.speedUnit), s.speedUnit.label, Modifier.weight(1f))
            Metric("Altitude", Fmt.altitudeValue(st.altitudeM, s.altitudeUnit), s.altitudeUnit.label, Modifier.weight(1.2f))
            Metric("Track", Fmt.heading(st.courseDeg), modifier = Modifier.weight(0.8f))
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val click = com.planemode.ui.LocalClick.current
            spanLabels.forEachIndexed { i, l -> FilterChip(spanIdx == i, { click(); spanIdx = i }, { Text(l, fontSize = 12.sp) }, Modifier.padding(horizontal = 3.dp)) }
        }
        Panel(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), padding = 0.dp) {
            Canvas(Modifier.fillMaxSize()) {
                drawProfile(
                    measurer, track.points, currentDist.toDouble(), st.altitudeM, st.verticalSpeedMps, st.groundSpeedMps,
                    spans[spanIdx], s.altitudeBandsFt, s.altitudeUnit, s.speedUnit,
                    st.routeTotalM, st.aircraft?.typicalCruiseAltFt, st.aircraft?.silhouetteKind ?: SilhouetteKind.NARROWBODY,
                    c.text, c.textDim,
                )
            }
        }
        Row(Modifier.padding(16.dp)) {
            Metric("Distance", Fmt.distanceValue(st.distanceM, s.speedUnit), s.speedUnit.distanceLabel, Modifier.weight(1f))
            Metric("Elapsed", Fmt.duration(st.elapsedMs), modifier = Modifier.weight(1.1f))
            Metric("Climb rate", Fmt.vsValue(st.verticalSpeedMps, s.altitudeUnit), s.altitudeUnit.vsLabel, Modifier.weight(1f), valueColor = vsColor(st.verticalSpeedMps).let { if (it == Pm.White) c.text else it })
        }
    }
}

private fun DrawScope.drawProfile(
    tm: TextMeasurer, points: List<com.planemode.model.TrackPoint>, current: Double, altM: Double?, vs: Double?, gs: Double?,
    span: Double, bands: List<Int>, altUnit: AltitudeUnit, speedUnit: com.planemode.model.SpeedUnit,
    routeTotal: Double?, plannedCruiseFt: Int?, kind: SilhouetteKind, text: Color, dim: Color,
) {
    val left = 56.dp.toPx(); val right = 16.dp.toPx(); val top = 16.dp.toPx(); val bottom = 28.dp.toPx()
    val w = size.width - left - right; val h = size.height - top - bottom
    val maxAltFt = max(41_000.0, (points.maxOfOrNull { it.altitudeM ?: 0.0 } ?: 0.0) * Units.M_TO_FT * 1.12)
    val effSpan = if (span > 0) span else max(routeTotal ?: 0.0, current * 1.15).coerceAtLeast(50_000.0)
    val anchorFrac = if (span > 0) 0.55 else 0.0
    val x0 = if (span > 0) current - effSpan * anchorFrac else 0.0
    fun x(d: Double) = (left + (d - x0) / effSpan * w).toFloat()
    fun y(ft: Double) = (top + h - (ft / maxAltFt) * h).toFloat()
    val labelStyle = TextStyle(color = dim, fontSize = 10.sp)

    // Altitude band backgrounds and grid
    val sorted = bands.sorted()
    var lo = 0.0
    (sorted + listOf(maxAltFt.toInt())).forEachIndexed { i, hi ->
        val c = bandColors[i.coerceAtMost(bandColors.lastIndex)]
        drawRect(c.copy(alpha = 0.05f), Offset(left, y(hi.toDouble())), androidx.compose.ui.geometry.Size(w, y(lo) - y(hi.toDouble())))
        lo = hi.toDouble()
    }
    val step = if (altUnit == AltitudeUnit.FEET) 10_000.0 else Units.mToFt(3_000.0)
    var g = 0.0
    while (g <= maxAltFt) {
        drawLine(dim.copy(alpha = 0.25f), Offset(left, y(g)), Offset(left + w, y(g)), 1f)
        val label = if (altUnit == AltitudeUnit.FEET) "%,d".format(g.toInt()) else "%,d m".format(Units.ftToM(g).toInt())
        drawText(tm, label, Offset(4.dp.toPx(), y(g) - 7.dp.toPx()), labelStyle)
        g += step
    }
    // Distance ticks
    val unitM = Units.distance(1.0, speedUnit).let { 1.0 / it }
    val tickUnits = listOf(10, 25, 50, 100, 250, 500, 1000).first { it * unitM * 6 >= effSpan }.toDouble()
    var tdist = (kotlin.math.ceil(x0 / (tickUnits * unitM)) * tickUnits * unitM)
    while (tdist <= x0 + effSpan) {
        if (tdist >= 0) {
            drawLine(dim.copy(alpha = 0.15f), Offset(x(tdist), top), Offset(x(tdist), top + h), 1f)
            drawText(tm, "${(tdist / unitM).toInt()} ${speedUnit.distanceLabel}", Offset(x(tdist) - 12.dp.toPx(), top + h + 6.dp.toPx()), labelStyle)
        }
        tdist += tickUnits * unitM
    }

    // Planned profile (approximate) when a route is configured
    if (routeTotal != null && routeTotal > 0) {
        val cruise = (plannedCruiseFt ?: 35_000).toDouble()
        val climbD = cruise / 1000 * 2.6 * 1852; val descD = cruise / 1000 * 3.1 * 1852
        val path = Path().apply {
            moveTo(x(0.0), y(0.0))
            if (climbD + descD < routeTotal) {
                lineTo(x(climbD), y(cruise)); lineTo(x(routeTotal - descD), y(cruise))
            } else lineTo(x(routeTotal / 2), y(cruise * routeTotal / (climbD + descD)))
            lineTo(x(routeTotal), y(0.0))
        }
        clipRect(left, top, left + w, top + h) {
            drawPath(path, Pm.Cyan.copy(alpha = 0.45f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
            drawLine(Pm.Cyan.copy(alpha = 0.6f), Offset(x(routeTotal), top), Offset(x(routeTotal), top + h), 1.5f)
        }
    }

    // Historical trace, colour-coded by altitude band, with soft fill
    clipRect(left, top, left + w, top + h) {
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            if (b.distanceM < x0 - 1000 || a.distanceM > x0 + effSpan + 1000) continue
            val aFt = (a.altitudeM ?: continue) * Units.M_TO_FT; val bFt = (b.altitudeM ?: continue) * Units.M_TO_FT
            val est = a.source == SampleSource.ESTIMATED || b.source == SampleSource.ESTIMATED
            val col = if (est) Pm.EstimatedGrey else altitudeBandColor((aFt + bFt) / 2, bands)
            val fill = Path().apply { moveTo(x(a.distanceM), y(aFt)); lineTo(x(b.distanceM), y(bFt)); lineTo(x(b.distanceM), y(0.0)); lineTo(x(a.distanceM), y(0.0)); close() }
            drawPath(fill, col.copy(alpha = 0.16f))
            drawLine(col, Offset(x(a.distanceM), y(aFt)), Offset(x(b.distanceM), y(bFt)), 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }

    // Aircraft side silhouette at the current point
    val curFt = (altM ?: points.lastOrNull()?.altitudeM ?: 0.0) * Units.M_TO_FT
    val px = x(current); val py = y(curFt)
    val pitch = if (vs != null && gs != null && gs > 20) Math.toDegrees(atan2(vs * 12, gs)).toFloat().coerceIn(-14f, 14f) else 0f
    val aw = 64.dp.toPx(); val ah = 26.dp.toPx()
    drawLine(text.copy(alpha = 0.25f), Offset(px, py), Offset(px, top + h), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    drawCircle(Pm.Cyan, 4.dp.toPx(), Offset(px, py))
    rotate(-pitch, Offset(px, py)) {
        translate(px - aw * 0.55f, py - ah * 0.85f) {
            val p = AircraftShapes.sideView(kind, aw, ah)
            drawPath(p, text)
            drawPath(p, Pm.Cyan.copy(alpha = 0.7f), style = Stroke(1.dp.toPx()))
        }
    }
    val altLabel = Fmt.altitude(altM, altUnit)
    drawText(tm, altLabel, Offset((px + 10.dp.toPx()).coerceAtMost(size.width - 90.dp.toPx()), py - 30.dp.toPx()), TextStyle(color = text, fontSize = 12.sp))
}
