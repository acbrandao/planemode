package com.planemode.calc

import com.planemode.model.GpsFix
import kotlin.math.abs
import kotlin.math.exp

/**
 * Rejects invalid, stale, duplicate and physically implausible fixes.
 * After [maxConsecutiveJumps] jump rejections the new fix is accepted anyway,
 * because the previously accepted fix may have been the outlier.
 */
class FixFilter(
    var maxStaleMs: Long = 15_000,
    var hardMaxAccuracyM: Double = 500.0,
    var maxSpeedMps: Double = 450.0,
    var maxConsecutiveJumps: Int = 5,
) {
    enum class Result { ACCEPTED, INVALID, DUPLICATE, STALE, INACCURATE, JUMP }

    private var last: GpsFix? = null
    private var jumpRejects = 0

    fun reset() { last = null; jumpRejects = 0 }

    fun check(fix: GpsFix, nowMono: Long): Result {
        if (fix.lat.isNaN() || fix.lon.isNaN() || abs(fix.lat) > 90 || abs(fix.lon) > 180 ||
            (fix.lat == 0.0 && fix.lon == 0.0)) return Result.INVALID
        val prev = last
        if (prev != null && fix.monoMs <= prev.monoMs) return Result.DUPLICATE
        if (nowMono - fix.monoMs > maxStaleMs) return Result.STALE
        if ((fix.hAccM ?: 0.0) > hardMaxAccuracyM) return Result.INACCURATE
        if (prev != null) {
            val dt = (fix.monoMs - prev.monoMs) / 1000.0
            val d = Geo.distanceM(prev.lat, prev.lon, fix.lat, fix.lon)
            val slack = (fix.hAccM ?: 20.0) + (prev.hAccM ?: 20.0)
            if (d - slack > maxSpeedMps * dt && jumpRejects < maxConsecutiveJumps) {
                jumpRejects++
                return Result.JUMP
            }
        }
        jumpRejects = 0
        last = fix
        return Result.ACCEPTED
    }
}

/** Time-constant based exponential moving average, robust to irregular sample intervals. */
class TimeEma(private val tauMs: Double) {
    var value: Double? = null; private set
    private var lastMono = 0L
    fun reset() { value = null }
    fun update(x: Double, monoMs: Long): Double {
        val v = value
        value = if (v == null) x else {
            val dt = (monoMs - lastMono).coerceAtLeast(0)
            val alpha = 1 - exp(-dt / tauMs)
            v + alpha * (x - v)
        }
        lastMono = monoMs
        return value!!
    }
}

/** Tracks how long a boolean condition has continuously held, in monotonic time. */
class Sustain {
    private var since: Long? = null
    fun update(condition: Boolean, monoMs: Long): Long {
        if (!condition) { since = null; return 0 }
        val s = since ?: monoMs.also { since = it }
        return monoMs - s
    }
    fun reset() { since = null }
}
