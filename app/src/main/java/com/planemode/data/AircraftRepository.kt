package com.planemode.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.planemode.model.AircraftProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class AircraftDataset(val version: Int = 1, val note: String = "", val aircraft: List<AircraftProfile>)

data class AircraftDataInfo(val source: String, val updatedEpochMs: Long?, val message: String? = null)

/**
 * Approximate, family-level aircraft reference data. The embedded dataset is the immediate
 * fallback; an optional online JSON source (same schema) refreshes the on-device cache.
 */
@Singleton
class AircraftRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile get() = File(context.filesDir, "aircraft_cache.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _profiles = MutableStateFlow(loadInitial())
    val profiles: StateFlow<List<AircraftProfile>> = _profiles.asStateFlow()
    private val _info = MutableStateFlow(
        if (cacheFile.exists()) AircraftDataInfo("Cached online data", cacheFile.lastModified()) else AircraftDataInfo("Embedded dataset", null)
    )
    val info: StateFlow<AircraftDataInfo> = _info.asStateFlow()

    private fun parse(text: String) = json.decodeFromString(AircraftDataset.serializer(), text).aircraft

    private fun embedded() = context.assets.open("aircraft.json").bufferedReader().use { parse(it.readText()) }

    private fun loadInitial(): List<AircraftProfile> {
        val base = embedded()
        val cached = runCatching { if (cacheFile.exists()) parse(cacheFile.readText()) else null }.getOrNull() ?: return base
        val byId = cached.associateBy { it.id }
        return base.map { byId[it.id] ?: it } + cached.filter { c -> base.none { it.id == c.id } }
    }

    fun byId(id: String?) = id?.let { i -> _profiles.value.firstOrNull { it.id == i } }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Called when the user selects an aircraft: refresh the cache if a source is configured and data is stale. */
    fun onAircraftSelected() {
        val url = settings.current.aircraftDataUrl
        if (url.isBlank() || !online()) return
        val age = System.currentTimeMillis() - (if (cacheFile.exists()) cacheFile.lastModified() else 0L)
        if (age > 7L * 24 * 3600 * 1000) scope.launch { refresh() }
    }

    suspend fun refresh(): Result<Int> = withContext(Dispatchers.IO) {
        val url = settings.current.aircraftDataUrl
        if (url.isBlank()) {
            _info.value = _info.value.copy(message = "No online aircraft data source configured – using embedded data.")
            return@withContext Result.failure(IllegalStateException("No source configured"))
        }
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("Accept", "application/json")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val list = parse(text)
            require(list.isNotEmpty() && list.all { it.typicalCruiseKts in 100..700 && it.maxAltFt in 10_000..60_000 }) { "Dataset failed validation" }
            cacheFile.writeText(text)
            _profiles.value = loadInitial()
            _info.value = AircraftDataInfo("Cached online data", System.currentTimeMillis(), "Updated ${list.size} aircraft families")
            list.size
        }.onFailure { _info.value = _info.value.copy(message = "Refresh failed: ${it.message}") }
    }

    fun clearCache() {
        cacheFile.delete()
        _profiles.value = loadInitial()
        _info.value = AircraftDataInfo("Embedded dataset", null, "Cache cleared")
    }
}
