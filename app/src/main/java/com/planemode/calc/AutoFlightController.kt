package com.planemode.calc

import com.planemode.model.FlightPhase

/**
 * Automatic start: measured, accurate, current fixes above the threshold for a sustained period.
 * Never uses estimated/extrapolated data.
 */
class AutoStartDetector(
    var thresholdKts: Double = 140.0,
    var sustainMs: Long = 8_000,
    var maxAccuracyM: Double = 50.0,
    var maxFixAgeMs: Long = 5_000,
) {
    private val sustain = Sustain()
    fun reset() = sustain.reset()

    fun update(nowMono: Long, fixMono: Long, gsKts: Double?, hAccM: Double?, measured: Boolean): Boolean {
        val ok = measured && gsKts != null && gsKts >= thresholdKts &&
            (hAccM ?: Double.MAX_VALUE) <= maxAccuracyM && nowMono - fixMono <= maxFixAgeMs
        return sustain.update(ok, nowMono) >= sustainMs
    }
}

object AutoStopPolicy {
    fun shouldStop(enabled: Boolean, userOverride: Boolean, phase: FlightPhase, landedConfirmed: Boolean) =
        enabled && !userOverride && phase == FlightPhase.COMPLETED && landedConfirmed
}
