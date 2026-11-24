package com.planemode.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Pm {
    val Navy = Color(0xFF0B1426)
    val NavyDeep = Color(0xFF070D1A)
    val Charcoal = Color(0xFF161E2C)
    val Panel = Color(0xFF1B2433)
    val PanelHigh = Color(0xFF243044)
    val AviationBlue = Color(0xFF2F7DE1)
    val Cyan = Color(0xFF22D3EE)
    val White = Color(0xFFF4F7FB)
    val Grey = Color(0xFF8A96A8)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFF5B942)
    val Red = Color(0xFFF05252)
    val Sky = Color(0xFF2E6FC2)
    val SkyTop = Color(0xFF1B4F94)
    val Ground = Color(0xFF7A4E2A)
    val GroundDeep = Color(0xFF5A3618)
    val EstimatedGrey = Color(0xFF3C4250)
}

@Immutable
data class InstrumentColors(
    val background: Color, val panel: Color, val panelHigh: Color, val text: Color, val textDim: Color, val outline: Color,
)

val LocalInstrumentColors = staticCompositionLocalOf {
    InstrumentColors(Pm.Navy, Pm.Panel, Pm.PanelHigh, Pm.White, Pm.Grey, Color(0x332F7DE1))
}

private val darkScheme = darkColorScheme(
    primary = Pm.Cyan, onPrimary = Pm.Navy, secondary = Pm.AviationBlue, onSecondary = Pm.White,
    background = Pm.Navy, onBackground = Pm.White, surface = Pm.Charcoal, onSurface = Pm.White,
    surfaceVariant = Pm.Panel, onSurfaceVariant = Pm.Grey, surfaceContainer = Pm.Charcoal,
    surfaceContainerHigh = Pm.Panel, surfaceContainerHighest = Pm.PanelHigh, outline = Color(0xFF34425A),
    error = Pm.Red, tertiary = Pm.Amber,
)

private val lightScheme = lightColorScheme(
    primary = Color(0xFF0B7FA0), onPrimary = Color.White, secondary = Pm.AviationBlue, onSecondary = Color.White,
    background = Color(0xFFF1F4F8), onBackground = Color(0xFF0B1426), surface = Color.White, onSurface = Color(0xFF0B1426),
    surfaceVariant = Color(0xFFE6ECF3), onSurfaceVariant = Color(0xFF526073), surfaceContainer = Color(0xFFF7F9FC),
    surfaceContainerHigh = Color(0xFFEDF1F6), surfaceContainerHighest = Color(0xFFE3E9F0), outline = Color(0xFFC3CCD8),
    error = Color(0xFFC53030), tertiary = Color(0xFFB7791F),
)

val Mono = FontFamily.Monospace

private val typography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelSmall = t.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium),
    )
}

val ValueStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = 0.sp)
val LabelStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.4.sp)

@Composable
fun PlaneModeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkScheme else lightScheme
    val inst = if (dark) InstrumentColors(Pm.Navy, Pm.Panel, Pm.PanelHigh, Pm.White, Pm.Grey, Color(0x332F7DE1))
    else InstrumentColors(Color(0xFFF1F4F8), Color.White, Color(0xFFE9EEF5), Color(0xFF0B1426), Color(0xFF5B6A7E), Color(0x220B1426))
    CompositionLocalProvider(LocalInstrumentColors provides inst) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
