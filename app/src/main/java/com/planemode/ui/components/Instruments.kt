package com.planemode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.planemode.model.GpsState
import com.planemode.ui.theme.LabelStyle
import com.planemode.ui.theme.LocalInstrumentColors
import com.planemode.ui.theme.Pm
import com.planemode.ui.theme.ValueStyle

fun gpsColor(s: GpsState) = when (s) {
    GpsState.LOCKED, GpsState.REACQUIRED -> Pm.Green
    GpsState.WEAK, GpsState.ESTIMATED, GpsState.SEARCHING -> Pm.Amber
    GpsState.LOST, GpsState.UNAVAILABLE -> Pm.Red
}

@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalInstrumentColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.panel)
            .border(1.dp, c.outline, RoundedCornerShape(16.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
fun Metric(
    label: String, value: String, unit: String? = null, modifier: Modifier = Modifier,
    valueColor: Color = LocalInstrumentColors.current.text, valueSize: TextUnit = 26.sp, sub: String? = null,
) {
    val c = LocalInstrumentColors.current
    Column(modifier) {
        Text(label.uppercase(), style = LabelStyle, color = c.textDim, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = ValueStyle.copy(fontSize = valueSize), color = valueColor, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, color = c.textDim, fontSize = (valueSize.value * 0.5f).sp, modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
            }
        }
        if (sub != null) Text(sub, color = c.textDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun GpsChip(state: GpsState, simulated: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val color = gpsColor(state)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(state.label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        if (simulated) {
            Spacer(Modifier.width(6.dp))
            Text("SIM", color = Pm.Amber, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun SimBadge(modifier: Modifier = Modifier) {
    Text(
        "SIMULATED DATA", color = Pm.Navy, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Pm.Amber).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = LabelStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
fun ScreenHeader(title: String, onSatellites: (() -> Unit)? = null, onSettings: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
        if (onSatellites != null) IconButton(onClick = onSatellites) { Icon(Icons.Default.SatelliteAlt, "Satellites") }
        if (onSettings != null) IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
    }
}

@Composable
fun BannerHost(text: String?, color: Color, modifier: Modifier = Modifier) {
    AnimatedVisibility(text != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            text ?: "", color = Pm.Navy, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(color).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
