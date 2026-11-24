package com.planemode.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.planemode.model.FlightSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("planemode_settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val key = stringPreferencesKey("settings_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun decode(s: String?) = migrate(s?.let { runCatching { json.decodeFromString(FlightSettings.serializer(), it) }.getOrNull() } ?: FlightSettings())

    /**
     * v2: map orientation defaults to north-up (conventional map orientation).
     * v3: re-enable auto-zoom — earlier versions saved "off" whenever the map was panned or zoomed.
     */
    private fun migrate(s: FlightSettings): FlightSettings {
        var m = s
        if (m.schemaVersion < 2) m = m.copy(mapOrientation = com.planemode.model.MapOrientation.NORTH_UP, schemaVersion = 2)
        if (m.schemaVersion < 3) m = m.copy(autoZoom = true, schemaVersion = 3)
        return m
    }

    // Settings are small; read once synchronously so every consumer starts from persisted values.
    private val initial: FlightSettings = runBlocking(Dispatchers.IO) { decode(context.dataStore.data.first()[key]) }

    val settings: StateFlow<FlightSettings> = context.dataStore.data
        .map { decode(it[key]) }
        .stateIn(scope, SharingStarted.Eagerly, initial)

    val current get() = settings.value

    init {
        // Persist one-time migrations immediately so the stored file always reflects the current schema.
        scope.launch {
            val raw = context.dataStore.data.first()[key]
            val stored = raw?.let { runCatching { json.decodeFromString(FlightSettings.serializer(), it) }.getOrNull() }
            if (stored != null && stored.schemaVersion < initial.schemaVersion) update { it }
        }
    }

    fun update(transform: (FlightSettings) -> FlightSettings) {
        scope.launch {
            context.dataStore.edit { prefs ->
                val next = transform(decode(prefs[key]))
                prefs[key] = json.encodeToString(FlightSettings.serializer(), next)
            }
        }
    }
}
