package com.planemode.calc

import com.planemode.model.LatLon
import kotlin.math.*

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance using the Haversine formula. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun distanceM(a: LatLon, b: LatLon) = distanceM(a.lat, a.lon, b.lat, b.lon)

    fun initialBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalizeDeg(Math.toDegrees(atan2(y, x)))
    }

    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): LatLon {
        val d = distanceM / EARTH_RADIUS_M; val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLon(Math.toDegrees(p2), normalizeLon(Math.toDegrees(l2)))
    }

    /** Intermediate point at fraction [f] along the great circle between two points. */
    fun interpolate(lat1: Double, lon1: Double, lat2: Double, lon2: Double, f: Double): LatLon {
        val p1 = Math.toRadians(lat1); val l1 = Math.toRadians(lon1)
        val p2 = Math.toRadians(lat2); val l2 = Math.toRadians(lon2)
        val delta = distanceM(lat1, lon1, lat2, lon2) / EARTH_RADIUS_M
        if (delta < 1e-12) return LatLon(lat1, lon1)
        val a = sin((1 - f) * delta) / sin(delta); val b = sin(f * delta) / sin(delta)
        val x = a * cos(p1) * cos(l1) + b * cos(p2) * cos(l2)
        val y = a * cos(p1) * sin(l1) + b * cos(p2) * sin(l2)
        val z = a * sin(p1) + b * sin(p2)
        return LatLon(Math.toDegrees(atan2(z, sqrt(x * x + y * y))), Math.toDegrees(atan2(y, x)))
    }

    /** Points along the great circle, spaced roughly [stepM] apart (inclusive of ends). */
    fun greatCirclePoints(a: LatLon, b: LatLon, stepM: Double = 50_000.0): List<LatLon> {
        val n = max(1, ceil(distanceM(a, b) / stepM).toInt())
        return (0..n).map { interpolate(a.lat, a.lon, b.lat, b.lon, it.toDouble() / n) }
    }

    fun normalizeDeg(d: Double): Double { val r = d % 360.0; return if (r < 0) r + 360.0 else r }
    fun normalizeLon(d: Double): Double { var r = (d + 180.0) % 360.0; if (r < 0) r += 360.0; return r - 180.0 }

    /** Signed smallest difference b - a in degrees, in (-180, 180]. */
    fun angleDiff(a: Double, b: Double): Double { var d = (b - a) % 360.0; if (d > 180) d -= 360.0; if (d <= -180) d += 360.0; return d }

    fun lerpAngle(a: Double, b: Double, t: Double) = normalizeDeg(a + angleDiff(a, b) * t)
}
