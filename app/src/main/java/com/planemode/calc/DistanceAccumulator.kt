package com.planemode.calc

import com.planemode.model.GpsFix

/** Cumulative great-circle distance between accepted measured fixes. */
class DistanceAccumulator(
    var maxSpeedMps: Double = 400.0,
    var maxAccuracyM: Double = 200.0,
    var stationarySpeedMps: Double = 1.5,
) {
    var totalM = 0.0; private set
    private var anchor: GpsFix? = null

    fun reset(initial: Double = 0.0) { totalM = initial; anchor = null }

    /** Returns the distance added (0 if rejected or stationary). */
    fun add(fix: GpsFix): Double {
        if ((fix.hAccM ?: 0.0) > maxAccuracyM) return 0.0
        val a = anchor
        if (a == null) { anchor = fix; return 0.0 }
        val dt = (fix.monoMs - a.monoMs) / 1000.0
        if (dt <= 0) return 0.0
        if (fix.speedMps != null && fix.speedMps < stationarySpeedMps) return 0.0
        val d = Geo.distanceM(a.lat, a.lon, fix.lat, fix.lon)
        if (d / dt > maxSpeedMps) return 0.0 // unrealistic jump; keep old anchor
        anchor = fix
        totalM += d
        return d
    }
}
