package com.planemode.calc

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Speed/altitude-driven map zoom: close in when slow and low (taxi, landing),
 * progressively wider as the aircraft accelerates and climbs.
 */
object AutoZoom {
    const val MIN_ZOOM = 5.5
    const val MAX_ZOOM = 15.0

    // Ground speed (kt) -> zoom. Taxiing shows the airport (runways/taxiways) in detail.
    private val speedStops = listOf(0.0 to 15.0, 25.0 to 14.6, 60.0 to 13.4, 120.0 to 12.2, 180.0 to 11.0, 250.0 to 10.0, 350.0 to 9.0, 480.0 to 8.1)
    // Height above the departure field (ft) -> extra zoom-out
    private val altStops = listOf(0.0 to 0.0, 1_000.0 to 0.3, 5_000.0 to 0.7, 15_000.0 to 1.1, 35_000.0 to 1.5)

    private fun interp(stops: List<Pair<Double, Double>>, x: Double): Double {
        if (x <= stops.first().first) return stops.first().second
        for (i in 1 until stops.size) {
            val (x1, y1) = stops[i]
            if (x <= x1) {
                val (x0, y0) = stops[i - 1]
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            }
        }
        return stops.last().second
    }

    /** Target zoom for the given ground speed (m/s) and height above ground (m). */
    fun target(groundSpeedMps: Double?, heightAboveGroundM: Double?): Double {
        val kts = Units.mpsToKts(groundSpeedMps ?: 0.0).coerceAtLeast(0.0)
        val ft = Units.mToFt(heightAboveGroundM ?: 0.0).coerceAtLeast(0.0)
        return (interp(speedStops, kts) - interp(altStops, ft)).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /**
     * Moves [current] gradually toward [target]: exponential easing with time constant [tauSec],
     * capped at [maxRatePerSec] zoom levels per second so changes are never abrupt.
     */
    fun step(current: Double, target: Double, dtSec: Double, tauSec: Double = 6.0, maxRatePerSec: Double = 0.25): Double {
        if (dtSec <= 0) return current
        val eased = (target - current) * (1 - exp(-dtSec / tauSec))
        val cap = maxRatePerSec * dtSec
        val delta = if (abs(eased) > cap) cap * kotlin.math.sign(eased) else eased
        return current + delta
    }

    fun converged(current: Double, target: Double) = abs(current - target) < 0.005
}
