package com.planemode.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.planemode.model.Constellation
import com.planemode.ui.FlightViewModel
import com.planemode.ui.Fmt
import com.planemode.ui.components.*
import com.planemode.ui.theme.LocalInstrumentColors
import com.planemode.ui.theme.Pm
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SatellitesScreen(vm: FlightViewModel, onBack: () -> Unit) {
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val c = LocalInstrumentColors.current
    val tm = rememberTextMeasurer()
    val sats = gnss?.satellites.orEmpty()

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("GPS satellite monitor", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (gnss?.simulated == true) SimBadge(Modifier.padding(end = 12.dp))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            GpsChip(st.gpsState, st.simulated)
            Spacer(Modifier.height(8.dp))
            Panel(Modifier.fillMaxWidth()) {
                if (gnss == null || gnss?.supported == false) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                gnss?.supported == false -> "Detailed satellite information is unsupported or unavailable on this source."
                                else -> "Waiting for GNSS satellite data…"
                            }, color = c.textDim, fontSize = 14.sp,
                        )
                    }
                } else {
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        val r = size.minDimension / 2 * 0.86f
                        val ctr = Offset(size.width / 2, size.height / 2)
                        val dim = c.textDim
                        listOf(0f, 30f, 60f).forEach { el ->
                            drawCircle(dim.copy(alpha = 0.35f), r * (90 - el) / 90, ctr, style = Stroke(1.dp.toPx()))
                        }
                        drawLine(dim.copy(alpha = 0.3f), Offset(ctr.x - r, ctr.y), Offset(ctr.x + r, ctr.y))
                        drawLine(dim.copy(alpha = 0.3f), Offset(ctr.x, ctr.y - r), Offset(ctr.x, ctr.y + r))
                        listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (l, a) ->
                            val rad = Math.toRadians((a - 90).toDouble())
                            val m = tm.measure(l, TextStyle(color = Pm.Cyan, fontSize = 13.sp))
                            drawText(m, topLeft = Offset(ctr.x + ((r + 14.dp.toPx()) * cos(rad)).toFloat() - m.size.width / 2, ctr.y + ((r + 14.dp.toPx()) * sin(rad)).toFloat() - m.size.height / 2))
                        }
                        sats.forEach { s ->
                            val rr = r * (90 - s.elevationDeg.coerceIn(0f, 90f)) / 90
                            val rad = Math.toRadians((s.azimuthDeg - 90).toDouble())
                            val p = Offset(ctr.x + (rr * cos(rad)).toFloat(), ctr.y + (rr * sin(rad)).toFloat())
                            val col = Color(s.constellation.color)
                            val weak = s.cn0DbHz < 20
                            val radius = (4 + s.cn0DbHz / 6).dp.toPx()
                            if (s.usedInFix) drawCircle(col, radius, p) else drawCircle(col.copy(alpha = if (weak) 0.35f else 0.8f), radius, p, style = Stroke(2.dp.toPx()))
                            drawText(tm, "${s.svid}", p + Offset(radius + 2, -radius), TextStyle(color = dim, fontSize = 9.sp))
                        }
                    }
                    Text("Filled = used in fix · ring = in view · faded = weak signal · size = C/N0", color = c.textDim, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                        Constellation.entries.filter { k -> sats.any { it.constellation == k } }.forEach { k ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(k.color)))
                                Spacer(Modifier.width(4.dp))
                                Text("${k.label} ${sats.count { it.constellation == k && it.usedInFix }}/${sats.count { it.constellation == k }}", fontSize = 12.sp, color = c.text)
                            }
                        }
                    }
                }
            }
            if (sats.isNotEmpty()) {
                SectionTitle("Signal strength (C/N0 dB-Hz)")
                Panel(Modifier.fillMaxWidth()) {
                    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                        val sorted = sats.sortedByDescending { it.cn0DbHz }.take(32)
                        val bw = size.width / sorted.size
                        sorted.forEachIndexed { i, s ->
                            val h = (s.cn0DbHz / 50f).coerceIn(0f, 1f) * size.height
                            val col = Color(s.constellation.color)
                            drawRect(if (s.usedInFix) col else col.copy(alpha = 0.35f), Offset(i * bw + bw * 0.15f, size.height - h), androidx.compose.ui.geometry.Size(bw * 0.7f, h))
                        }
                    }
                }
            }
            SectionTitle("GPS statistics")
            Panel(Modifier.fillMaxWidth()) {
                InfoRow("Satellites in view", gnss?.takeIf { it.supported }?.inView?.toString() ?: "Unavailable")
                InfoRow("Used for positioning", gnss?.takeIf { it.supported }?.used?.toString() ?: (st.satellitesUsed?.toString() ?: "Unavailable"))
                InfoRow("Average signal", gnss?.averageCn0?.let { "%.1f dB-Hz".format(it) } ?: Fmt.NONE)
                InfoRow("Horizontal accuracy", Fmt.meters(st.hAccM))
                InfoRow("Altitude accuracy", Fmt.meters(st.vAccM))
                InfoRow("Last successful fix", st.lastFixEpochMs?.let { "${Fmt.clockSeconds(it)} (${Fmt.shortDuration(st.sinceLastFixMs)} ago)" } ?: Fmt.NONE)
                InfoRow("Current lock duration", Fmt.shortDuration(st.currentLockMs))
                InfoRow("Current loss duration", st.currentLossMs?.let { Fmt.shortDuration(it) } ?: "No loss")
            }
            SectionTitle("Tracking time (this flight)")
            Panel(Modifier.fillMaxWidth()) {
                InfoRow("Valid GPS fix", Fmt.duration(st.timers.validMs))
                InfoRow("Estimated position", Fmt.duration(st.timers.estimatedMs))
                InfoRow("No usable position", Fmt.duration(st.timers.noPositionMs))
                InfoRow("Total tracked", Fmt.duration(st.timers.validMs + st.timers.estimatedMs + st.timers.noPositionMs))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
