package com.planemode.calc

import com.planemode.model.LatLon
import kotlin.math.min

/**
 * Short-term dead reckoning from the last reliable ground speed and course over ground.
 * Beyond the prediction window the position is frozen and flagged stale – we never
 * invent an increasingly inaccurate route.
 */
object PositionEstimator {
    data class Estimate(val position: LatLon, val uncertaintyM: Double, val stale: Boolean, val extrapolatedMs: Long)

    fun estimate(
        lastLat: Double, lastLon: Double, lastAccuracyM: Double,
        speedMps: Double, courseDeg: Double,
        elapsedSinceFixMs: Long, maxExtrapolationMs: Long,
    ): Estimate {
        val stale = elapsedSinceFixMs > maxExtrapolationMs
        val dtMs = min(elapsedSinceFixMs, maxExtrapolationMs).coerceAtLeast(0)
        val dt = dtMs / 1000.0
        val pos = Geo.destination(lastLat, lastLon, courseDeg, speedMps * dt)
        // Growth: speed error (~3%) plus an unknown 0.5 m/s² manoeuvre acceleration term.
        val uncertainty = lastAccuracyM + 0.03 * speedMps * dt + 0.25 * dt * dt
        return Estimate(pos, min(uncertainty, 50_000.0), stale, dtMs)
    }
}
