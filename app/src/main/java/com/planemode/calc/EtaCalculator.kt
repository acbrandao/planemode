package com.planemode.calc

enum class EtaBasis { GPS_GROUND_SPEED, TYPICAL_CRUISE, NONE }

data class Eta(val remainingMs: Long?, val basis: EtaBasis)

/** Remaining distance / filtered ground speed, with an explicit typical-cruise fallback. */
class EtaCalculator(tauMs: Double = 90_000.0, var minReliableSpeedMps: Double = 40.0) {
    private val ema = TimeEma(tauMs)
    val smoothedSpeedMps get() = ema.value

    fun reset() = ema.reset()

    fun updateSpeed(speedMps: Double, monoMs: Long) { ema.update(speedMps, monoMs) }

    fun compute(remainingM: Double?, positionReliable: Boolean, speedReliable: Boolean, fallbackCruiseMps: Double?): Eta {
        if (remainingM == null || !positionReliable) return Eta(null, EtaBasis.NONE)
        val s = ema.value
        if (speedReliable && s != null && s >= minReliableSpeedMps) {
            return Eta((remainingM / s * 1000).toLong(), EtaBasis.GPS_GROUND_SPEED)
        }
        if (fallbackCruiseMps != null && fallbackCruiseMps > 0) {
            return Eta((remainingM / fallbackCruiseMps * 1000).toLong(), EtaBasis.TYPICAL_CRUISE)
        }
        return Eta(null, EtaBasis.NONE)
    }
}
