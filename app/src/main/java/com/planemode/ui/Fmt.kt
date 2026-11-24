package com.planemode.ui

import com.planemode.calc.Units
import com.planemode.model.AltitudeUnit
import com.planemode.model.SpeedUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Display formatting. Missing values render as "--" rather than misleading zeros. */
object Fmt {
    const val NONE = "--"

    fun speedValue(mps: Double?, unit: SpeedUnit) = mps?.let { Units.speed(it, unit).roundToInt().toString() } ?: NONE
    fun speed(mps: Double?, unit: SpeedUnit) = "${speedValue(mps, unit)} ${unit.label}"

    fun altitudeValue(m: Double?, unit: AltitudeUnit) = m?.let { "%,d".format(Locale.US, Units.altitude(it, unit).roundToInt()) } ?: NONE
    fun altitude(m: Double?, unit: AltitudeUnit) = "${altitudeValue(m, unit)} ${unit.label}"

    fun vsValue(mps: Double?, unit: AltitudeUnit): String {
        mps ?: return NONE
        val v = Units.verticalSpeed(mps, unit)
        return when (unit) {
            AltitudeUnit.FEET -> { val r = (v / 50).roundToInt() * 50; (if (r > 0) "+" else "") + "%,d".format(Locale.US, r) }
            AltitudeUnit.METERS -> (if (v > 0.05) "+" else "") + "%.1f".format(Locale.US, v)
        }
    }
    fun vs(mps: Double?, unit: AltitudeUnit) = "${vsValue(mps, unit)} ${unit.vsLabel}"

    fun distanceValue(m: Double?, unit: SpeedUnit): String {
        m ?: return NONE
        val d = Units.distance(m, unit)
        return if (d < 100) "%.1f".format(Locale.US, d) else "%,d".format(Locale.US, d.roundToLong())
    }
    fun distance(m: Double?, unit: SpeedUnit) = "${distanceValue(m, unit)} ${unit.distanceLabel}"

    fun heading(deg: Double?) = deg?.let { "%03d°".format(Locale.US, ((it.roundToInt() % 360) + 360) % 360) } ?: NONE

    fun duration(ms: Long?): String {
        ms ?: return NONE
        val s = ms / 1000
        return "%d:%02d:%02d".format(Locale.US, s / 3600, (s / 60) % 60, s % 60)
    }

    fun shortDuration(ms: Long?): String {
        ms ?: return NONE
        val s = ms / 1000
        return when { s < 60 -> "${s}s"; s < 3600 -> "${s / 60}m ${s % 60}s"; else -> "${s / 3600}h ${(s / 60) % 60}m" }
    }

    fun hoursMinutes(ms: Long?): String {
        ms ?: return NONE
        val m = ms / 60_000
        return "${m / 60}h ${"%02d".format(Locale.US, m % 60)}m"
    }

    fun clock(epochMs: Long?) = epochMs?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: NONE
    fun clockSeconds(epochMs: Long?) = epochMs?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: NONE

    fun lat(v: Double?) = v?.let { "%.4f° %s".format(Locale.US, abs(it), if (it >= 0) "N" else "S") } ?: NONE
    fun lon(v: Double?) = v?.let { "%.4f° %s".format(Locale.US, abs(it), if (it >= 0) "E" else "W") } ?: NONE

    fun meters(v: Double?) = v?.let { "±${it.roundToInt()} m" } ?: NONE

    fun bytes(b: Long) = when {
        b < 1024 -> "$b B"
        b < 1_048_576 -> "%.0f KB".format(Locale.US, b / 1024.0)
        else -> "%.1f MB".format(Locale.US, b / 1_048_576.0)
    }

    fun cardinal(deg: Double?): String {
        deg ?: return ""
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((deg + 22.5) % 360 + 360) % 360 / 45).toInt() % 8]
    }
}
