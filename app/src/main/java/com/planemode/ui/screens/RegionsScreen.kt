package com.planemode.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.planemode.map.FlightMap
import com.planemode.map.MapController
import com.planemode.map.OfflineMapManager
import com.planemode.ui.FlightViewModel
import com.planemode.ui.Fmt
import com.planemode.ui.components.Panel
import com.planemode.ui.components.SectionTitle
import com.planemode.ui.theme.LocalInstrumentColors
import com.planemode.ui.theme.Pm
import kotlin.math.roundToInt

@Composable
fun RegionsScreen(vm: FlightViewModel, onBack: () -> Unit) {
    val regions by vm.regions.collectAsStateWithLifecycle()
    val msg by vm.offlineMessage.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val c = LocalInstrumentColors.current
    val controller = remember { MapController() }
    var maxZoom by remember { mutableFloatStateOf(9f) }
    var confirmClear by remember { mutableStateOf(false) }
    var boundsTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); boundsTick++ } }
    val bounds = remember(boundsTick) { controller.visibleBounds() }
    val estTiles = bounds?.let { OfflineMapManager.estimateTiles(it.latitudeNorth, it.longitudeEast, it.latitudeSouth, it.longitudeWest, 2, maxZoom.roundToInt()) }
    val total = regions.sumOf { it.sizeBytes }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Offline map regions", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.padding(horizontal = 12.dp)) {
            item {
                Panel(Modifier.fillMaxWidth(), padding = 0.dp) {
                    FlightMap(Modifier.fillMaxWidth().height(220.dp), s, st, null, interactive = true, following = false, controller = controller)
                }
                Spacer(Modifier.height(8.dp))
                Text("Pan/zoom the map to the area to download.", fontSize = 12.sp, color = c.textDim)
                Text("Max detail zoom: ${maxZoom.roundToInt()}", fontSize = 13.sp, color = c.text)
                Slider(maxZoom, { maxZoom = it }, valueRange = 5f..11f, steps = 5)
                Text("Estimated: ${estTiles ?: "--"} tiles · ~${Fmt.bytes((estTiles ?: 0) * OfflineMapManager.AVG_TILE_BYTES)}", fontSize = 12.sp, color = c.textDim)
                Button(onClick = {
                    bounds?.let { b -> vm.offline.download("Manual ${java.text.SimpleDateFormat("MMM d HH:mm").format(java.util.Date())}", OfflineMapManager.KIND_MANUAL,
                        b.latitudeNorth, b.longitudeEast, b.latitudeSouth, b.longitudeWest, 2, maxZoom.roundToInt()) }
                }, enabled = bounds != null, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Download visible area") }
                if (st.departure != null && st.arrival != null) OutlinedButton(onClick = { vm.offline.cacheRoute(st.departure!!, st.arrival!!) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Pre-cache route corridor ${st.departure!!.iata}–${st.arrival!!.iata}")
                }
                msg?.let { Text(it, color = Pm.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                SectionTitle("Downloaded regions · ${Fmt.bytes(total)} of ${s.maxCacheMb} MB")
                if (regions.isEmpty()) Text("No offline regions yet. Recently viewed areas are cached automatically.", color = c.textDim, fontSize = 13.sp)
            }
            items(regions, key = { it.id }) { r ->
                Panel(Modifier.fillMaxWidth().padding(vertical = 4.dp), padding = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontSize = 14.sp, color = c.text)
                            Text("${r.kind} · z${r.minZoom.roundToInt()}–${r.maxZoom.roundToInt()} · ${Fmt.bytes(r.sizeBytes)} · " +
                                when { r.complete -> "complete"; r.downloading -> "downloading ${(r.progress * 100).roundToInt()}%"; else -> "paused ${(r.progress * 100).roundToInt()}%" },
                                fontSize = 11.sp, color = c.textDim)
                            r.error?.let { Text(it, fontSize = 11.sp, color = Pm.Red) }
                        }
                        IconButton(onClick = { vm.offline.delete(r.id) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                    if (!r.complete) LinearProgressIndicator(progress = { r.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear map cache", color = Pm.Red) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear map cache?") },
        text = { Text("Deletes all downloaded regions and the automatic cache. The embedded world base map remains available.") },
        confirmButton = { Button(onClick = { vm.offline.clearAll(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
