package com.planemode.calc

/**
 * Smoothed vertical speed from altitude observations using least-squares regression
 * over a sliding time window (always keeps at least [minSamples] latest points).
 */
class ClimbRateCalculator(
    private val windowMs: Long = 20_000,
    private val minSamples: Int = 3,
    private val minSpanMs: Long = 2_000,
) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun reset() = samples.clear()

    fun add(monoMs: Long, altitudeM: Double): Double? {
        if (samples.isNotEmpty() && monoMs <= samples.last().first) return rate()
        samples.addLast(monoMs to altitudeM)
        while (samples.size > minSamples && monoMs - samples.first().first > windowMs) samples.removeFirst()
        return rate()
    }

    /** Vertical speed in m/s, or null when not enough data. */
    fun rate(): Double? {
        if (samples.size < 2) return null
        val t0 = samples.first().first
        val span = samples.last().first - t0
        if (span < minSpanMs) return null
        var st = 0.0; var sa = 0.0; var stt = 0.0; var sta = 0.0
        val n = samples.size.toDouble()
        for ((t, a) in samples) {
            val x = (t - t0) / 1000.0
            st += x; sa += a; stt += x * x; sta += x * a
        }
        val den = n * stt - st * st
        if (den == 0.0) return null
        return (n * sta - st * sa) / den
    }
}
