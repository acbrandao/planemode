package com.planemode.sim

import com.planemode.model.Constellation
import com.planemode.model.GnssSnapshot
import com.planemode.model.SatelliteInfo
import kotlin.math.max
import kotlin.math.sin

/** Deterministic synthetic constellation for the simulator. Clearly flagged as simulated. */
object SimulatedSatellites {
    private data class Sv(val c: Constellation, val svid: Int, val az0: Float, val el0: Float, val drift: Float)

    private val svs = buildList {
        val r = kotlin.random.Random(7)
        fun add(c: Constellation, ids: IntRange) = ids.forEach {
            add(Sv(c, it, r.nextFloat() * 360f, 8f + r.nextFloat() * 80f, (r.nextFloat() - 0.5f) * 30f))
        }
        add(Constellation.GPS, 1..11)
        add(Constellation.GLONASS, 65..71)
        add(Constellation.GALILEO, 1..7)
        add(Constellation.BEIDOU, 19..24)
        add(Constellation.SBAS, 131..133)
    }

    fun snapshot(tSec: Double, monoMs: Long, signalBoostDb: Float, maxUsed: Int, weak: Boolean, lost: Boolean): GnssSnapshot {
        val hours = (tSec / 3600.0).toFloat()
        val list = svs.mapIndexed { i, sv ->
            val el = (sv.el0 + 12f * sin(hours * 0.9f + i)).coerceIn(2f, 89f)
            val az = (sv.az0 + sv.drift * hours + 360f) % 360f
            var cn0 = 18f + 26f * (el / 90f) + signalBoostDb + 3f * sin(tSec.toFloat() / 40f + i)
            if (weak) cn0 -= 14f
            if (lost) cn0 = max(0f, cn0 - 28f)
            SatelliteInfo(sv.svid, sv.c, az, el, cn0.coerceAtLeast(0f), false)
        }
        val usedIds = if (lost) emptySet() else list.filter { it.cn0DbHz > 26 && it.constellation != Constellation.SBAS }
            .sortedByDescending { it.cn0DbHz }.take(maxUsed).map { it.constellation to it.svid }.toSet()
        return GnssSnapshot(true, list.map { it.copy(usedInFix = (it.constellation to it.svid) in usedIds) }, monoMs, simulated = true)
    }
}
