package com.planemode

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.planemode.model.ThemeMode
import com.planemode.tracking.FlightTracker
import com.planemode.ui.FlightViewModel
import com.planemode.ui.PlaneModeNavHost
import com.planemode.ui.theme.PlaneModeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var tracker: FlightTracker
    private val vm: FlightViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tracker.onBackground(); tracker.onForeground() // restart the location stream with the new permission
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            permissionLauncher.launch(buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
        }
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = when (settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            LaunchedEffect(state.active, settings.keepScreenOn) {
                if (state.active && settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            PlaneModeTheme(dark) {
                androidx.compose.runtime.CompositionLocalProvider(com.planemode.ui.LocalClick provides vm.feedback::click) { PlaneModeNavHost(vm) }
            }
        }
    }

    override fun onStart() { super.onStart(); tracker.onForeground() }
    override fun onStop() { tracker.onBackground(); super.onStop() }
}
