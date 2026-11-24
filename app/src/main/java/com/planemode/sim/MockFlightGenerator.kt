package com.planemode.sim

import com.planemode.calc.Geo
import com.planemode.calc.Units
import com.planemode.model.LatLon
import kotlin.math.*
import kotlin.random.Random

/** One simulated GNSS observation. Units chosen to match aviation conventions. */
data class MockSample(
    val tSec: Double,
    val lat: Double,
    val lon: Double,
    val altFt: Double,
    val gsKts: Double,
    val courseDeg: Double,
    val vsFpm: Double,
    val hAccM: Double,
    val vAccM: Double,
    val satellites: Int,
    val valid: Boolean,
    val signalDbHz: Double,
    val phase: String,
) {
    fun toJson(): String = buildString {
        append("{\"t\":").append("%.1f".format(java.util.Locale.US, tSec))
        append(",\"lat\":").append("%.6f".format(java.util.Locale.US, lat))
        append(",\"lon\":").append("%.6f".format(java.util.Locale.US, lon))
        append(",\"alt_ft\":").append(altFt.roundToInt())
        append(",\"gs_kt\":").append("%.1f".format(java.util.Locale.US, gsKts))
        append(",\"course\":").append("%.1f".format(java.util.Locale.US, courseDeg))
        append(",\"vs_fpm\":").append(vsFpm.roundToInt())
        append(",\"h_acc_m\":").append("%.1f".format(java.util.Locale.US, hAccM))
        append(",\"v_acc_m\":").append("%.1f".format(java.util.Locale.US, vAccM))
        append(",\"sats\":").append(satellites)
        append(",\"valid\":").append(valid)
        append(",\"cn0\":").append("%.1f".format(java.util.Locale.US, signalDbHz))
        append(",\"phase\":\"").append(phase).append("\"}")
    }
}

/**
 * Procedural, deterministic EWR → MIA flight (~1,100 nm, ~2¾ h). The aircraft departs Newark runway 22R,
 * follows a curved route down the US East Coast (great-circle interpolated between waypoints), and lands
 * on Miami runway 8R after a turn onto final. Figures are illustrative test parameters, not performance guarantees.
 */
class MockFlightGenerator(seed: Int = 42, private val cruiseFt: Double = 37_000.0, private val stepClimbFt: Double = 37_000.0) {

    companion object {
        val EWR = LatLon(40.6895, -74.1745)
        val MIA = LatLon(25.7955, -80.2790)
        const val EWR_ELEV_FT = 18.0
        const val MIA_ELEV_FT = 8.0

        private val TAXI_OUT = listOf(LatLon(40.6900, -74.1780), LatLon(40.6990, -74.1720), LatLon(40.7066, -74.1556))
        private val ENROUTE = listOf(
            LatLon(40.6800, -74.1830), // runway 22R departure end
            LatLon(40.6000, -74.3500), LatLon(40.3000, -74.5200), LatLon(39.7000, -74.6500),
            LatLon(38.8000, -74.8500), LatLon(37.6000, -75.1500), LatLon(36.3000, -75.3500),
            LatLon(35.0000, -76.0000), LatLon(33.6000, -77.2000), LatLon(32.0000, -78.6000),
            LatLon(30.2000, -79.6000), LatLon(28.4000, -80.0500), LatLon(27.0000, -80.2500),
            LatLon(26.3500, -80.4000), LatLon(26.0000, -80.5200), // downwind west of the Everglades shoreline
            LatLon(25.8400, -80.5300), LatLon(25.8000, -80.4700), // base turn onto final for 8R
        )
        val MIA_THRESHOLD = LatLon(25.8000, -80.3090) // runway 8R, landing east
        private val ROLLOUT_AND_TAXI = listOf(LatLon(25.8000, -80.2860), LatLon(25.7975, -80.2820), LatLon(25.7955, -80.2790))
    }

    private val rnd = Random(seed)
    private val path: List<LatLon>
    private val cum: DoubleArray
    private val takeoffStartM: Double
    private val thresholdM: Double
    val totalPathM: Double

    init {
        val raw = ArrayList<LatLon>()
        val all = TAXI_OUT + ENROUTE + MIA_THRESHOLD + ROLLOUT_AND_TAXI
        for (i in 0 until all.size - 1) {
            val seg = Geo.greatCirclePoints(all[i], all[i + 1], 15_000.0)
            if (raw.isNotEmpty()) raw.addAll(seg.drop(1)) else raw.addAll(seg)
        }
        path = raw
        cum = DoubleArray(path.size)
        for (i in 1 until path.size) cum[i] = cum[i - 1] + Geo.distanceM(path[i - 1], path[i])
        totalPathM = cum.last()
        takeoffStartM = distanceToVertex(TAXI_OUT.last())
        thresholdM = distanceToVertex(MIA_THRESHOLD)
    }

    private fun distanceToVertex(p: LatLon): Double {
        val i = path.indices.minBy { Geo.distanceM(path[it], p) }
        return cum[i]
    }

    // --- dynamic state ---
    var tSec = 0.0; private set
    private var distM = 0.0
    private var gsMps = 0.0
    private var altAglFt = 0.0
    private var vsFpm = 0.0
    private var holdRemaining = 40.0
    private var airborne = false
    private var landed = false
    var phase = "TAXI"; private set
    val finished get() = phase == "PARKED"
    val progress get() = (distM / totalPathM).coerceIn(0.0, 1.0)

    fun positionAt(d: Double): Pair<LatLon, Double> {
        val dd = d.coerceIn(0.0, totalPathM)
        var lo = 0; var hi = cum.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= dd) lo = m else hi = m }
        val segLen = cum[hi] - cum[lo]
        val f = if (segLen > 0) (dd - cum[lo]) / segLen else 0.0
        val a = path[lo]; val b = path[hi]
        val p = Geo.interpolate(a.lat, a.lon, b.lat, b.lon, f)
        return p to Geo.initialBearing(a.lat, a.lon, b.lat, b.lon)
    }

    /** Maximum altitude AGL (ft) permitted at a given distance-to-threshold (nm): 3° final, ~3.2 nm/1000 ft above. */
    private fun descentCeilingFt(remainNm: Double) =
        if (remainNm <= 10) max(0.0, remainNm * 300.0) else 3_000.0 + (remainNm - 10) * 312.0

    private fun approach(current: Double, target: Double, maxDelta: Double) =
        if (current < target) min(target, current + maxDelta) else max(target, current - maxDelta)

    fun step(dt: Double): MockSample {
        tSec += dt
        val remainNm = (thresholdM - distM) / Units.NM_TO_M
        val wind = 18 * sin(2 * PI * distM / 900_000.0) + 6 * sin(2 * PI * tSec / 700.0)
        val prevAlt = altAglFt
        var targetKts: Double
        var accel = 1.0 // m/s² limit
        when {
            distM < takeoffStartM && !airborne -> { // taxi out & line-up
                phase = "TAXI"
                val toGo = takeoffStartM - distM
                targetKts = if (toGo < 120) 3.0 else 16.0
                if (toGo < 8) { targetKts = 0.0; distM = takeoffStartM }
            }
            !airborne && holdRemaining > 0 -> { phase = "HOLD"; targetKts = 0.0; holdRemaining -= dt; accel = 3.0 }
            !airborne && !landed -> { // takeoff roll
                phase = "TAKEOFF_ROLL"; targetKts = 165.0; accel = 2.2
                if (Units.mpsToKts(gsMps) >= 152) { airborne = true; phase = "TAKEOFF" }
            }
            airborne -> {
                val ceiling = descentCeilingFt(remainNm)
                val cruiseTarget = if (progress > 0.55) stepClimbFt else cruiseFt
                val climbRate = when {
                    altAglFt < 1_500 -> 2_200.0
                    altAglFt < 10_000 -> 2_600.0
                    altAglFt < 30_000 -> 2_000.0 - (altAglFt - 10_000) / 20_000 * 800
                    else -> 1_100.0 - (altAglFt - 30_000) / 9_000 * 600
                }
                var newAlt = if (altAglFt < cruiseTarget) min(cruiseTarget, altAglFt + climbRate * dt / 60.0) else altAglFt
                if (newAlt > ceiling) newAlt = max(ceiling, altAglFt - 3_500 * dt / 60.0)
                newAlt = min(newAlt, ceiling + 50)
                altAglFt = max(0.0, newAlt)
                val descending = ceiling < cruiseTarget - 200 && altAglFt >= ceiling - 100 && remainNm < 160
                targetKts = if (!descending) when {
                    altAglFt < 1_500 -> 175.0
                    altAglFt < 10_000 -> 175 + (altAglFt - 1_500) / 8_500 * 75
                    altAglFt < 30_000 -> 290 + (altAglFt - 10_000) / 20_000 * 60
                    else -> 350 + min(1.0, (altAglFt - 30_000) / 7_000) * 110
                } else when {
                    remainNm < 1.5 -> 140.0
                    remainNm < 8 -> 150.0
                    remainNm < 25 -> 185.0
                    altAglFt < 10_000 -> 250.0
                    else -> 290 + (altAglFt - 10_000) / 29_000 * 130
                }
                targetKts += if (altAglFt > 20_000) wind else wind * altAglFt / 20_000
                accel = 0.8
                phase = when {
                    altAglFt < 1_500 && !descending -> "TAKEOFF"
                    descending && altAglFt < 10_000 -> "APPROACH"
                    descending -> "DESCENT"
                    altAglFt < 10_000 -> "INITIAL_CLIMB"
                    altAglFt < cruiseTarget - 300 -> "CLIMB"
                    else -> "CRUISE"
                }
                if (remainNm <= 0.05 || (descending && altAglFt <= 1.0 && remainNm < 1.0)) {
                    airborne = false; landed = true; altAglFt = 0.0; phase = "LANDING"
                }
            }
            else -> { // landed: rollout and taxi in
                val toGo = totalPathM - distM
                phase = if (Units.mpsToKts(gsMps) > 40) "LANDING" else "TAXI_IN"
                targetKts = when { toGo < 30 -> 0.0; toGo < 200 -> 5.0; Units.mpsToKts(gsMps) > 40 -> 15.0; else -> 15.0 }
                accel = if (Units.mpsToKts(gsMps) > 40) 2.2 else 1.0
                if (toGo < 3 || (toGo < 30 && gsMps < 0.3)) { phase = "PARKED"; gsMps = 0.0 }
            }
        }
        if (phase != "PARKED") gsMps = approach(gsMps, Units.ktsToMps(targetKts), accel * dt)
        distM = min(totalPathM, distM + gsMps * dt)
        vsFpm = if (dt > 0) (altAglFt - prevAlt) / dt * 60.0 else 0.0

        val (pos, bearing) = positionAt(distM)
        val elevFt = EWR_ELEV_FT + (MIA_ELEV_FT - EWR_ELEV_FT) * progress
        val turbulence = if (altAglFt > 20_000) 15 * sin(tSec / 23.0) + rnd.nextDouble(-6.0, 6.0) else rnd.nextDouble(-2.0, 2.0)
        val hAcc = 3.5 + rnd.nextDouble(0.0, 3.0)
        val noiseM = rnd.nextDouble(0.0, hAcc * 0.4)
        val noisy = Geo.destination(pos.lat, pos.lon, rnd.nextDouble(0.0, 360.0), noiseM)
        val moving = gsMps > 1.0
        return MockSample(
            tSec = tSec,
            lat = noisy.lat, lon = noisy.lon,
            altFt = altAglFt + elevFt + turbulence,
            gsKts = Units.mpsToKts(gsMps) + if (moving) rnd.nextDouble(-0.6, 0.6) else 0.0,
            courseDeg = Geo.normalizeDeg(bearing + if (moving) rnd.nextDouble(-0.4, 0.4) else 0.0),
            vsFpm = vsFpm,
            hAccM = hAcc, vAccM = hAcc * 1.6 + rnd.nextDouble(0.0, 2.0),
            satellites = 10 + rnd.nextInt(0, 5),
            valid = true,
            signalDbHz = 34 + rnd.nextDouble(0.0, 9.0),
            phase = phase,
        )
    }

    /** Generates the full flight at a fixed interval (used for the bundled JSONL dataset and tests). */
    fun generateAll(intervalSec: Double, maxSec: Double = 8 * 3600.0): List<MockSample> {
        val out = ArrayList<MockSample>()
        while (!finished && tSec < maxSec) out.add(step(intervalSec))
        return out
    }
}
