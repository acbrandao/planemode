package com.planemode.calc

import com.planemode.model.SampleSource
import com.planemode.model.TrackPoint

/**
 * Keeps the route history (measured and estimated points) at a bounded density and
 * reconciles estimated runs when GPS returns so the track never "teleports".
 */
class TrackRecorder(var minIntervalMs: Long = 5_000) {
    private val _points = ArrayList<TrackPoint>()
    val points: List<TrackPoint> get() = _points
    var version = 0; private set

    fun clear() { _points.clear(); version++ }
    fun load(list: List<TrackPoint>) { _points.clear(); _points.addAll(list); version++ }

    val last get() = _points.lastOrNull()

    fun addMeasured(p: TrackPoint, force: Boolean = false): Boolean = add(p.copy(source = SampleSource.GPS), force)

    fun addEstimated(p: TrackPoint): Boolean = add(p.copy(source = SampleSource.ESTIMATED), false)

    private fun add(p: TrackPoint, force: Boolean): Boolean {
        val l = _points.lastOrNull()
        val sourceChanged = l != null && l.source != p.source
        if (!force && !sourceChanged && l != null && p.monoMs - l.monoMs < minIntervalMs) return false
        if (l != null && p.monoMs <= l.monoMs) return false
        _points.add(p); version++
        return true
    }

    /** Trailing run of estimated points (empty if the last point is measured). */
    fun trailingEstimated(): List<TrackPoint> {
        var i = _points.size
        while (i > 0 && _points[i - 1].source == SampleSource.ESTIMATED) i--
        return _points.subList(i, _points.size).toList()
    }

    /**
     * GPS has returned at [fix]. The trailing estimated points are shifted progressively
     * (proportionally to elapsed time between the last measured point and the new fix), so the
     * dead-reckoning error is distributed along the run and it joins the new fix smoothly instead
     * of jumping. Returns the corrected estimated points.
     */
    fun reconcile(fix: TrackPoint): List<TrackPoint> {
        var start = _points.size
        while (start > 0 && _points[start - 1].source == SampleSource.ESTIMATED) start--
        val corrected = ArrayList<TrackPoint>()
        if (start < _points.size) {
            val anchor = if (start > 0) _points[start - 1] else _points[start]
            val lastEst = _points.last()
            val dLat = fix.lat - lastEst.lat
            val dLon = Geo.angleDiff(lastEst.lon, fix.lon)
            val span = (fix.monoMs - anchor.monoMs).coerceAtLeast(1).toDouble()
            for (i in start until _points.size) {
                val p = _points[i]
                val f = ((p.monoMs - anchor.monoMs) / span).coerceIn(0.0, 1.0)
                val q = p.copy(lat = p.lat + dLat * f, lon = Geo.normalizeLon(p.lon + dLon * f))
                _points[i] = q
                corrected.add(q)
            }
            version++
        }
        add(fix.copy(source = SampleSource.GPS), force = true)
        return corrected
    }
}
