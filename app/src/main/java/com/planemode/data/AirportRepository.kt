package com.planemode.data

import android.content.Context
import com.planemode.model.Airport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Local airport database (OurAirports, public domain), bundled in assets. */
@Singleton
class AirportRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private var cache: List<Airport>? = null
    private val mutex = Mutex()

    suspend fun all(): List<Airport> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) {
            context.assets.open("airports.csv").bufferedReader().useLines { lines ->
                lines.filter { !it.startsWith("#") && it.isNotBlank() }.mapNotNull { line ->
                    val p = line.split('|')
                    if (p.size < 8) null else runCatching {
                        Airport(p[0], p[1], p[2], p[3], p[4], p[5].toDouble(), p[6].toDouble(), p[7].toInt())
                    }.getOrNull()
                }.toList()
            }
        }.also { cache = it }
    }

    suspend fun find(code: String?): Airport? {
        if (code.isNullOrBlank()) return null
        val c = code.trim().uppercase()
        return all().firstOrNull { it.iata == c } ?: all().firstOrNull { it.icao == c }
    }

    suspend fun search(query: String, limit: Int = 25): List<Airport> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val u = q.uppercase()
        val l = q.lowercase()
        return all().asSequence().mapNotNull { a ->
            val score = when {
                a.iata == u -> 0
                a.icao == u -> 1
                a.iata.startsWith(u) -> 2
                a.icao.startsWith(u) -> 3
                a.city.lowercase().startsWith(l) -> 4
                a.name.lowercase().contains(l) || a.city.lowercase().contains(l) -> 5
                else -> -1
            }
            if (score < 0) null else score to a
        }.sortedWith(compareBy({ it.first }, { it.second.iata })).take(limit).map { it.second }.toList()
    }
}
