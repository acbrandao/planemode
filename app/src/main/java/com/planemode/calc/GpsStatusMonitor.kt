package com.planemode.calc

import com.planemode.model.GpsState
import kotlin.math.max

/**
 * Derives the GPS display state from the arrival of fixes over (monotonic) time.
 * Thresholds adapt to the observed fix interval so that slow update rates are not
 * mistaken for signal loss.
 */
class GpsStatusMonitor(
    var predictionWindowMs: Long = 30_000,
    var reacquiredHoldMs: Long = 10_000,
    var baseWeakMs: Long = 4_000,
    var baseLostMs: Long = 10_000,
) {
    var state = GpsState.SEARCHING; private set
    var lastFixMono: Long? = null; private set
    var lossStartMono: Long? = null; private set
    var lockStartMono: Long? = null; private set
    private var lastAccuracyOk = false
    private var reacquiredAt: Long? = null
    private var avgIntervalMs = 1000.0
    var estimationAvailable = false

    val weakAfterMs get() = max(baseWeakMs.toDouble(), 2.5 * avgIntervalMs).toLong()
    val lostAfterMs get() = max(baseLostMs.toDouble(), 3.5 * avgIntervalMs).toLong()

    fun reset() {
        state = GpsState.SEARCHING; lastFixMono = null; lossStartMono = null; lockStartMono = null
        reacquiredAt = null; avgIntervalMs = 1000.0
    }

    /** Called for each accepted fix. Returns the new state. */
    fun onFix(monoMs: Long, accuracyOk: Boolean): GpsState {
        lastFixMono?.let { prev ->
            val iv = (monoMs - prev).toDouble()
            if (iv in 1.0..120_000.0 && state != GpsState.LOST && state != GpsState.ESTIMATED) {
                avgIntervalMs = 0.8 * avgIntervalMs + 0.2 * iv
            }
        }
        val wasLost = state == GpsState.LOST || state == GpsState.ESTIMATED
        lastFixMono = monoMs
        lastAccuracyOk = accuracyOk
        if (wasLost) { reacquiredAt = monoMs; lossStartMono = null }
        state = evaluate(monoMs)
        if (state.hasValidFix && lockStartMono == null) lockStartMono = monoMs
        return state
    }

    /** Called periodically with the current monotonic time. Returns the new state. */
    fun onTick(nowMono: Long): GpsState {
        state = evaluate(nowMono)
        if (!state.hasValidFix) lockStartMono = null
        if ((state == GpsState.LOST || state == GpsState.ESTIMATED) && lossStartMono == null) {
            lossStartMono = (lastFixMono ?: nowMono) + lostAfterMs
        }
        return state
    }

    fun sinceLastFix(nowMono: Long) = lastFixMono?.let { nowMono - it }

    /** Whether, at [nowMono], the estimator should still extrapolate position. */
    fun withinPrediction(nowMono: Long): Boolean {
        val last = lastFixMono ?: return false
        return nowMono - last <= lostAfterMs + predictionWindowMs
    }

    private fun evaluate(now: Long): GpsState {
        val last = lastFixMono ?: return GpsState.SEARCHING
        val age = now - last
        return when {
            age >= lostAfterMs ->
                if (estimationAvailable && withinPrediction(now)) GpsState.ESTIMATED else GpsState.LOST
            age >= weakAfterMs -> GpsState.WEAK
            !lastAccuracyOk -> GpsState.WEAK
            reacquiredAt != null && now - reacquiredAt!! < reacquiredHoldMs -> GpsState.REACQUIRED
            else -> { reacquiredAt = null; GpsState.LOCKED }
        }
    }
}
