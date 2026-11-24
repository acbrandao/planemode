package com.planemode.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.planemode.calc.Geo
import com.planemode.data.SettingsRepository
import com.planemode.model.Airport
import com.planemode.model.GpsState
import com.planemode.model.MapRegion
import com.planemode.tracking.FlightTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Serializable
private data class RegionMeta(val name: String, val kind: String)

/**
 * Offline vector map management on top of MapLibre offline regions:
 * initial region around the first fix, automatic ambient caching of viewed areas,
 * manual downloads, route-corridor pre-caching, Wi-Fi policy, limits and deletion.
 */
@Singleton
class OfflineMapManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val tracker: FlightTracker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { OfflineManager.getInstance(context) }
    private val json = Json { ignoreUnknownKeys = true }

    private val _regions = MutableStateFlow<List<MapRegion>>(emptyList())
    val regions: StateFlow<List<MapRegion>> = _regions.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _unmetered = MutableStateFlow(true)
    val unmetered: StateFlow<Boolean> = _unmetered.asStateFlow()
    private val _online = MutableStateFlow(false)

    private val live = HashMap<Long, OfflineRegion>()
    private val status = HashMap<Long, OfflineRegionStatus>()
    private val errors = HashMap<Long, String>()
    private var started = false
    private val listed = MutableStateFlow(false)

    companion object {
        const val AVG_TILE_BYTES = 30_000L
        const val KIND_INITIAL = "initial"
        const val KIND_WORLD = "world"
        const val KIND_MANUAL = "manual"
        const val KIND_ROUTE = "route"

        /** Number of tiles needed to cover [bounds] across zoom levels. */
        fun estimateTiles(north: Double, east: Double, south: Double, west: Double, minZ: Int, maxZ: Int): Long {
            var total = 0L
            for (z in minZ..maxZ) {
                val n = 1 shl z
                fun tx(lon: Double) = floor((lon + 180) / 360 * n).toInt().coerceIn(0, n - 1)
                fun ty(lat: Double): Int {
                    val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
                    return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
                }
                val w = if (east >= west) tx(east) - tx(west) + 1 else (n - tx(west)) + tx(east) + 1
                val h = ty(south) - ty(north) + 1
                total += w.toLong() * h
            }
            return total
        }
    }

    fun start() {
        if (started) return
        started = true
        observeNetwork()
        scope.launch {
            settingsRepo.settings.map { it.maxCacheMb }.distinctUntilChanged().collect { mb ->
                manager.setMaximumAmbientCacheSize(mb * 1024L * 1024L, object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {}
                    override fun onError(message: String) {}
                })
            }
        }
        refresh()
        // Initial region around the first valid GPS fix.
        scope.launch {
            listed.first { it }
            tracker.state.filter { it.gpsState.hasValidFix && it.lat != null && !it.positionEstimated }.first().let { st ->
                if (settingsRepo.current.autoRegionCache) ensureInitialRegion(st.lat!!, st.lon!!)
            }
        }
        // Route corridor pre-caching when a departure/arrival pair is configured.
        scope.launch {
            listed.first { it }
            tracker.state.map { it.departure to it.arrival }.distinctUntilChanged().collect { (d, a) ->
                if (d != null && a != null && settingsRepo.current.autoRegionCache) cacheRoute(d, a)
            }
        }
        // Resume or pause downloads based on network policy.
        scope.launch {
            combine(_unmetered, _online, settingsRepo.settings.map { it.wifiOnlyDownloads }) { u, o, w -> o && (u || !w) }
                .distinctUntilChanged().collect { allowed -> live.values.forEach { r ->
                    val st = status[r.id]
                    if (st != null && !st.isComplete) r.setDownloadState(if (allowed) OfflineRegion.STATE_ACTIVE else OfflineRegion.STATE_INACTIVE)
                } ; publish() }
        }
    }

    private fun observeNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        fun update(caps: NetworkCapabilities?) {
            _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            _unmetered.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        }
        update(cm.getNetworkCapabilities(cm.activeNetwork))
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { update(caps) }
                override fun onLost(network: Network) { _online.value = false }
            })
        }
    }

    private fun downloadsAllowed() = _online.value && (_unmetered.value || !settingsRepo.current.wifiOnlyDownloads)

    private fun styleUrl() = MapStyle.tileSource(settingsRepo.current).offlineStyleUrl

    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                live.clear()
                offlineRegions?.forEach { r ->
                    live[r.id] = r
                    attachObserver(r)
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(s: OfflineRegionStatus?) {
                            if (s != null) status[r.id] = s
                            if (s != null && !s.isComplete && downloadsAllowed()) r.setDownloadState(OfflineRegion.STATE_ACTIVE)
                            publish()
                        }
                        override fun onError(error: String?) {}
                    })
                }
                publish()
                listed.value = true
            }
            override fun onError(error: String) { _message.value = "Offline maps unavailable: $error" }
        })
    }

    private fun meta(r: OfflineRegion) = runCatching { json.decodeFromString(RegionMeta.serializer(), String(r.metadata)) }.getOrNull()

    private fun publish() {
        _regions.value = live.values.map { r ->
            val d = r.definition
            val b = d.bounds
            val s = status[r.id]
            val m = meta(r)
            MapRegion(
                id = r.id, name = m?.name ?: "Region ${r.id}", kind = m?.kind ?: KIND_MANUAL,
                north = b?.latitudeNorth ?: 0.0, east = b?.longitudeEast ?: 0.0, south = b?.latitudeSouth ?: 0.0, west = b?.longitudeWest ?: 0.0,
                minZoom = d.minZoom, maxZoom = d.maxZoom,
                complete = s?.isComplete == true,
                downloading = s?.downloadState == OfflineRegion.STATE_ACTIVE && s.isComplete.not(),
                completedResources = s?.completedResourceCount ?: 0, requiredResources = s?.requiredResourceCount ?: 0,
                sizeBytes = s?.completedResourceSize ?: 0, error = errors[r.id],
            )
        }.sortedBy { it.id }
    }

    private fun attachObserver(r: OfflineRegion) {
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(s: OfflineRegionStatus) {
                status[r.id] = s
                if (s.isComplete) r.setDownloadState(OfflineRegion.STATE_INACTIVE)
                publish()
            }
            override fun onError(error: OfflineRegionError) { errors[r.id] = error.message; publish() }
            override fun mapboxTileCountLimitExceeded(limit: Long) { errors[r.id] = "Tile limit exceeded"; publish() }
        })
    }

    val totalBytes get() = _regions.value.sumOf { it.sizeBytes }

    /** Creates and starts a region download unless an equivalent region already exists. */
    fun download(name: String, kind: String, north: Double, east: Double, south: Double, west: Double, minZoom: Int, maxZoom: Int): Boolean {
        val style = styleUrl()
        if (style == null) { _message.value = "Offline downloads need an online vector tile source (Settings → Mapping)."; return false }
        if (_regions.value.any { it.kind == kind && it.name == name && kind != KIND_MANUAL }) return false
        val estimate = estimateTiles(north, east, south, west, minZoom, maxZoom) * AVG_TILE_BYTES
        val limit = settingsRepo.current.maxCacheMb * 1024L * 1024L
        if (totalBytes + estimate > limit) {
            _message.value = "Download (~${estimate / 1_048_576} MB) would exceed the ${settingsRepo.current.maxCacheMb} MB cache limit."
            return false
        }
        val bounds = LatLngBounds.Builder().include(LatLng(north, east)).include(LatLng(south, west)).build()
        val def = OfflineTilePyramidRegionDefinition(style, bounds, minZoom.toDouble(), maxZoom.toDouble(), context.resources.displayMetrics.density)
        val meta = json.encodeToString(RegionMeta.serializer(), RegionMeta(name, kind)).toByteArray()
        manager.createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                live[offlineRegion.id] = offlineRegion
                attachObserver(offlineRegion)
                if (downloadsAllowed()) offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                else _message.value = "\"$name\" queued – waiting for Wi-Fi."
                publish()
            }
            override fun onError(error: String) { _message.value = "Could not create region: $error" }
        })
        return true
    }

    fun ensureInitialRegion(lat: Double, lon: Double) {
        if (_regions.value.none { it.kind == KIND_WORLD }) download("World overview", KIND_WORLD, 85.0, 180.0, -85.0, -180.0, 0, 3)
        val covered = _regions.value.any { it.kind == KIND_INITIAL && lat in it.south..it.north && lon in it.west..it.east }
        if (!covered) download("Around %.2f, %.2f".format(lat, lon), KIND_INITIAL, lat + 1.5, lon + 2.0, lat - 1.5, lon - 2.0, 4, 9)
    }

    /** Pre-caches a corridor (~±110 km) along the great-circle route at en-route zoom levels. */
    fun cacheRoute(dep: Airport, arr: Airport) {
        val name = "Route ${dep.iata}–${arr.iata}"
        if (_regions.value.any { it.kind == KIND_ROUTE && it.name.startsWith(name) }) return
        val pts = Geo.greatCirclePoints(com.planemode.model.LatLon(dep.lat, dep.lon), com.planemode.model.LatLon(arr.lat, arr.lon), 250_000.0)
        pts.zipWithNext().forEachIndexed { i, (a, b) ->
            val n = max(a.lat, b.lat) + 1.0; val s = min(a.lat, b.lat) - 1.0
            var w = min(a.lon, b.lon) - 1.3; var e = max(a.lon, b.lon) + 1.3
            if (abs(a.lon - b.lon) > 180) { w = -180.0; e = 180.0 }
            download("$name (${i + 1}/${pts.size - 1})", KIND_ROUTE, n, e, s, w, 3, 7)
        }
        // Higher detail around the departure and arrival airports
        download("$name · ${dep.iata} area", KIND_ROUTE, dep.lat + 0.8, dep.lon + 1.0, dep.lat - 0.8, dep.lon - 1.0, 8, 10)
        download("$name · ${arr.iata} area", KIND_ROUTE, arr.lat + 0.8, arr.lon + 1.0, arr.lat - 0.8, arr.lon - 1.0, 8, 10)
    }

    fun delete(id: Long) {
        val r = live[id] ?: return
        r.setDownloadState(OfflineRegion.STATE_INACTIVE)
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { live.remove(id); status.remove(id); errors.remove(id); publish() }
            override fun onError(error: String) { _message.value = "Delete failed: $error" }
        })
    }

    fun clearAll() {
        live.keys.toList().forEach { delete(it) }
        manager.clearAmbientCache(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() { _message.value = "Map cache cleared" }
            override fun onError(message: String) { _message.value = "Clear failed: $message" }
        })
    }

    fun clearMessage() { _message.value = null }
}
