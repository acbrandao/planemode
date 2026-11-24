package com.planemode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.planemode.ui.screens.*

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("map", "Map", Icons.Default.Map),
    Tab("profile", "Profile", Icons.Default.Timeline),
    Tab("hud", "HUD", Icons.Default.Speed),
    Tab("info", "Info", Icons.Default.Dashboard),
)

@Composable
fun PlaneModeNavHost(vm: FlightViewModel) {
    val nav = rememberNavController()
    val click = LocalClick.current
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route in tabs.map { it.route }
    fun go(r: String) = click().also { if (route != r) nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true; restoreState = true
    } }
    val toSettings = { click(); nav.navigate("settings") }
    val toSats = { click(); nav.navigate("satellites") }
    val back: () -> Unit = { click(); nav.popBackStack() }

    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Scaffold(
        bottomBar = {
            if (showBar && !landscape) NavigationBar(tonalElevation = NavigationBarDefaults.Elevation) {
                tabs.forEach { t ->
                    NavigationBarItem(selected = route == t.route, onClick = { go(t.route) }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label, fontSize = 11.sp) })
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
      Row(Modifier.padding(pad)) {
        if (showBar && landscape) NavigationRail(Modifier.fillMaxHeight().displayCutoutPadding()) {
            Spacer(Modifier.weight(1f))
            tabs.forEach { t -> NavigationRailItem(selected = route == t.route, onClick = { go(t.route) }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label, fontSize = 11.sp) }) }
            Spacer(Modifier.weight(1f))
        }
        NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
            composable("home") { HomeScreen(vm, onFlightStarted = { go("map") }, onSettings = toSettings, onSatellites = toSats) }
            composable("map") { MapScreen(vm, onSatellites = toSats, onSettings = toSettings) }
            composable("profile") { Box(Modifier.statusBarsPadding()) { ProfileScreen(vm, toSats, toSettings) } }
            composable("hud") { Box(Modifier.statusBarsPadding()) { HudScreen(vm, toSats, toSettings) } }
            composable("info") { Box(Modifier.statusBarsPadding()) { InfoScreen(vm, toSats, toSettings) } }
            composable("satellites") { Box(Modifier.statusBarsPadding().navigationBarsPadding()) { SatellitesScreen(vm, back) } }
            composable("settings") { Box(Modifier.statusBarsPadding().navigationBarsPadding()) { SettingsScreen(vm, back, { click(); nav.navigate("regions") }) } }
            composable("regions") { Box(Modifier.statusBarsPadding().navigationBarsPadding()) { RegionsScreen(vm, back) } }
        }
      }
    }
}
