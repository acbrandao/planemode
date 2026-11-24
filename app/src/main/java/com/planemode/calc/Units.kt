package com.planemode.calc

import com.planemode.model.AltitudeUnit
import com.planemode.model.SpeedUnit

object Units {
    const val MPS_TO_KTS = 1.9438444924406
    const val MPS_TO_MPH = 2.2369362920544
    const val MPS_TO_KMH = 3.6
    const val M_TO_FT = 3.2808398950131
    const val NM_TO_M = 1852.0
    const val MI_TO_M = 1609.344

    fun ktsToMps(kts: Double) = kts / MPS_TO_KTS
    fun mpsToKts(mps: Double) = mps * MPS_TO_KTS
    fun ftToM(ft: Double) = ft / M_TO_FT
    fun mToFt(m: Double) = m * M_TO_FT

    fun speed(mps: Double, unit: SpeedUnit) = when (unit) {
        SpeedUnit.KNOTS -> mps * MPS_TO_KTS
        SpeedUnit.MPH -> mps * MPS_TO_MPH
        SpeedUnit.KMH -> mps * MPS_TO_KMH
    }

    fun speedToMps(value: Double, unit: SpeedUnit) = when (unit) {
        SpeedUnit.KNOTS -> value / MPS_TO_KTS
        SpeedUnit.MPH -> value / MPS_TO_MPH
        SpeedUnit.KMH -> value / MPS_TO_KMH
    }

    fun altitude(m: Double, unit: AltitudeUnit) = when (unit) {
        AltitudeUnit.FEET -> m * M_TO_FT
        AltitudeUnit.METERS -> m
    }

    /** Vertical speed in fpm (feet system) or m/s (metric system). */
    fun verticalSpeed(mps: Double, unit: AltitudeUnit) = when (unit) {
        AltitudeUnit.FEET -> mps * M_TO_FT * 60.0
        AltitudeUnit.METERS -> mps
    }

    /** Distance in nm / mi / km, following the selected speed unit family. */
    fun distance(m: Double, unit: SpeedUnit) = when (unit) {
        SpeedUnit.KNOTS -> m / NM_TO_M
        SpeedUnit.MPH -> m / MI_TO_M
        SpeedUnit.KMH -> m / 1000.0
    }
}
