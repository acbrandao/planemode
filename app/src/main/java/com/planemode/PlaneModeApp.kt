package com.planemode

import android.app.Application
import com.planemode.map.OfflineMapManager
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre
import javax.inject.Inject

@HiltAndroidApp
class PlaneModeApp : Application() {
    @Inject lateinit var offlineMaps: OfflineMapManager

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        offlineMaps.start()
    }
}
