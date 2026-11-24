package com.planemode.calc

import com.planemode.model.FlightEventType
import com.planemode.model.FlightPhase
import com.planemode.model.FlightPhase.*
import kotlin.math.abs

data class PhaseConfig(
    val takeoffSpeedKts: Double = 140.0,
    val taxiSpeedKts: Double = 35.0,
    val landingConfirmMs: Long = 90_000,
    val maxCruiseKts: Double? = null,
)

/**
 * Lightweight flight-phase state machine driven by filtered measured data only.
 * Hysteresis and minimum durations suppress repeated/false events.
 */
class FlightPhaseDetector(var config: PhaseConfig = PhaseConfig()) {
    var phase = ARMED; private set
    var landedConfirmed = false; private set
    private var groundAltFt: Double? = null
    private var cruiseAltFt = 0.0
    private var cruiseAnnounced = false
    private var todAnnounced = false
    private var tenKArmed = true
    private var maxSpeedArmed = true
    private var phaseSince = 0L
    private val sTakeoff = Sustain(); private val sClimb = Sustain(); private val sLevel = Sustain()
    private val sDesc = Sustain(); private val sTaxi = Sustain(); private val sTouch = Sustain()
    private val sInflight = Sustain(); private val sRejected = Sustain()

    fun reset(start: FlightPhase = ARMED) {
        phase = start; landedConfirmed = false; groundAltFt = null; cruiseAltFt = 0.0
        cruiseAnnounced = false; todAnnounced = false; tenKArmed = true; maxSpeedArmed = true; phaseSince = 0
        listOf(sTakeoff, sClimb, sLevel, sDesc, sTaxi, sTouch, sInflight, sRejected).forEach { it.reset() }
    }

    fun restore(p: FlightPhase) { phase = p; if (p.ordinal >= CRUISE.ordinal) { cruiseAnnounced = true }; if (p.ordinal >= TOP_OF_DESCENT.ordinal) todAnnounced = true; tenKArmed = p.ordinal < CLIMB.ordinal }

    /**
     * @param altFt altitude in feet (null if unavailable), @param vsFpm smoothed vertical speed.
     * @return events produced by this update.
     */
    fun update(monoMs: Long, gsKts: Double, altFt: Double?, vsFpm: Double?): List<FlightEventType> {
        val ev = ArrayList<FlightEventType>()
        val vs = vsFpm ?: 0.0
        val agl = if (altFt != null && groundAltFt != null) altFt - groundAltFt!! else null
        val level = vsFpm != null && abs(vs) < 300
        val climbing = vs > 500
        val descending = vs < -500

        fun go(p: FlightPhase) { phase = p; phaseSince = monoMs; sLevel.reset(); sDesc.reset(); sClimb.reset() }

        val taxiFor = sTaxi.update(gsKts < config.taxiSpeedKts, monoMs)

        when (phase) {
            IDLE, COMPLETED -> {}
            ARMED -> {
                if (gsKts < 60 && altFt != null) groundAltFt = groundAltFt?.let { it * 0.9 + altFt * 0.1 } ?: altFt
                // Session started while already airborne
                if (sInflight.update(gsKts > 180 && (altFt ?: 0.0) > 3000, monoMs) > 10_000) {
                    groundAltFt = null
                    tenKArmed = (altFt ?: 0.0) < 10_000
                    go(when { climbing -> CLIMB; descending -> DESCENT; else -> CRUISE })
                    if (phase == CRUISE) { cruiseAnnounced = true; cruiseAltFt = altFt ?: 0.0 }
                } else if (sTakeoff.update(gsKts >= config.takeoffSpeedKts, monoMs) >= 3_000) {
                    ev += FlightEventType.TAKEOFF_SPEED; go(TAKEOFF)
                }
            }
            TAKEOFF -> when {
                (agl != null && agl > 1_000) || sClimb.update(climbing, monoMs) > 10_000 -> go(INITIAL_CLIMB)
                sRejected.update(gsKts < config.taxiSpeedKts, monoMs) > 30_000 -> { go(ARMED); sTakeoff.reset() }
            }
            INITIAL_CLIMB -> when {
                (altFt != null && altFt >= 10_000) || (agl != null && agl >= 8_000) -> go(CLIMB)
                sLevel.update(level && (agl ?: 5_000.0) > 3_000, monoMs) > 120_000 -> { cruiseAltFt = altFt ?: 0.0; go(CRUISE); announceCruise(ev) }
                sDesc.update(descending, monoMs) > 60_000 -> go(DESCENT)
            }
            CLIMB -> when {
                sLevel.update(level, monoMs) > 90_000 -> { cruiseAltFt = altFt ?: 0.0; go(CRUISE); announceCruise(ev) }
                sDesc.update(descending, monoMs) > 60_000 -> go(DESCENT)
            }
            CRUISE -> {
                if (altFt != null && level) cruiseAltFt = cruiseAltFt * 0.98 + altFt * 0.02
                when {
                    sDesc.update(descending, monoMs) > 60_000 -> {
                        go(TOP_OF_DESCENT)
                        if (!todAnnounced) { todAnnounced = true; ev += FlightEventType.TOP_OF_DESCENT }
                    }
                    sClimb.update(climbing, monoMs) > 60_000 -> go(CLIMB) // step climb
                }
            }
            TOP_OF_DESCENT -> when {
                monoMs - phaseSince > 60_000 && descending -> go(DESCENT)
                altFt != null && altFt < cruiseAltFt - 3_000 -> go(DESCENT)
                sLevel.update(level, monoMs) > 120_000 -> go(CRUISE)
            }
            DESCENT -> when {
                sTouch.update(gsKts < 100 && abs(vs) < 300, monoMs) > 8_000 -> { go(LANDING); ev += FlightEventType.LANDING }
                sLevel.update(level && (agl ?: 10_000.0) > 5_000 && gsKts > 200, monoMs) > 180_000 -> go(CRUISE)
            }
            LANDING -> when {
                taxiFor >= config.landingConfirmMs -> { go(COMPLETED); landedConfirmed = true }
                sTakeoff.update(gsKts >= config.takeoffSpeedKts, monoMs) >= 3_000 -> go(TAKEOFF) // go-around / touch-and-go
            }
        }
        // Safety net: airborne phase but sustained taxi speed => landed (missed descent detection)
        if (phase.airborne && phase != TAKEOFF && taxiFor >= config.landingConfirmMs) {
            ev += FlightEventType.LANDING; go(COMPLETED); landedConfirmed = true
        }

        // Milestones with hysteresis
        if (altFt != null) {
            if (tenKArmed && altFt >= 10_000 && vs > 0 && phase in setOf(TAKEOFF, INITIAL_CLIMB, CLIMB)) {
                tenKArmed = false; ev += FlightEventType.TEN_THOUSAND
            } else if (altFt < 9_000) tenKArmed = true
        }
        config.maxCruiseKts?.let { max ->
            if (maxSpeedArmed && gsKts >= max && phase.airborne) { maxSpeedArmed = false; ev += FlightEventType.MAX_CRUISE_SPEED }
            else if (gsKts < max - 15) maxSpeedArmed = true
        }
        return ev
    }

    private fun announceCruise(ev: MutableList<FlightEventType>) {
        if (!cruiseAnnounced) { cruiseAnnounced = true; ev += FlightEventType.CRUISE }
    }
}
