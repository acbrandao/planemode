package com.planemode.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.planemode.model.AircraftProfile
import com.planemode.model.Airport
import com.planemode.model.SilhouetteKind
import com.planemode.ui.theme.Pm

@Composable
fun AircraftIcon(kind: SilhouetteKind, color: Color, modifier: Modifier = Modifier.size(36.dp)) {
    Canvas(modifier) { drawPath(AircraftShapes.topView(kind, size.minDimension), color) }
}

@Composable
fun AirportPickerDialog(title: String, search: suspend (String) -> List<Airport>, onPick: (Airport?) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Airport>()) }
    LaunchedEffect(q) { results = search(q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, label = { Text("IATA / ICAO code, city or name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(results, key = { it.iata + it.icao }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(a.iata, fontWeight = FontWeight.Bold, color = Pm.Cyan, modifier = Modifier.width(48.dp), fontSize = 16.sp)
                            Column(Modifier.weight(1f)) {
                                Text(a.name, maxLines = 1, fontSize = 14.sp)
                                Text("${a.icao} · ${a.city}, ${a.country}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
                if (q.isNotBlank() && results.isEmpty()) Text("No airports found in the local database.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text("Clear") } },
    )
}

@Composable
fun AircraftPickerDialog(list: List<AircraftProfile>, selected: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aircraft (optional)") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 10.dp)) {
                        Text("No aircraft selected", fontWeight = if (selected == null) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                items(list, key = { it.id }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(a.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AircraftIcon(a.silhouetteKind, if (a.id == selected) Pm.Cyan else MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(a.displayName, fontWeight = if (a.id == selected) FontWeight.Bold else FontWeight.Normal)
                            Text("~${a.typicalCruiseKts} kt cruise · FL${a.typicalCruiseAltFt / 100} · ~${a.rangeNm} nm", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Text("Approximate family-level reference values, not specifications for an exact variant.", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
