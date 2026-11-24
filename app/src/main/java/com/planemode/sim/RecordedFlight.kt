package com.planemode.sim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Playback of the bundled JSONL dataset, interpolated to arbitrary virtual time steps. */
class RecordedFlight(private val samples: List<MockSample>) {
    var tSec = 0.0; private set
    private var idx = 0
    val finished get() = idx >= samples.size - 1
    val progress get() = if (samples.isEmpty()) 1.0 else idx.toDouble() / samples.size

    fun step(dt: Double): MockSample {
        tSec += dt
        while (idx < samples.size - 1 && samples[idx + 1].tSec <= tSec) idx++
        val a = samples[idx]
        val b = samples.getOrNull(idx + 1) ?: return a.copy(tSec = tSec)
        val f = ((tSec - a.tSec) / (b.tSec - a.tSec)).coerceIn(0.0, 1.0)
        fun l(x: Double, y: Double) = x + (y - x) * f
        return a.copy(
            tSec = tSec, lat = l(a.lat, b.lat), lon = l(a.lon, b.lon), altFt = l(a.altFt, b.altFt),
            gsKts = l(a.gsKts, b.gsKts), courseDeg = com.planemode.calc.Geo.lerpAngle(a.courseDeg, b.courseDeg, f),
            vsFpm = l(a.vsFpm, b.vsFpm), valid = a.valid && b.valid,
        )
    }

    companion object {
        fun parse(lines: Sequence<String>): List<MockSample> = lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val o = Json.parseToJsonElement(line) as JsonObject
            fun d(k: String) = o[k]!!.jsonPrimitive.double
            MockSample(
                tSec = d("t"), lat = d("lat"), lon = d("lon"), altFt = d("alt_ft"), gsKts = d("gs_kt"),
                courseDeg = d("course"), vsFpm = d("vs_fpm"), hAccM = d("h_acc_m"), vAccM = d("v_acc_m"),
                satellites = o["sats"]!!.jsonPrimitive.int, valid = o["valid"]!!.jsonPrimitive.boolean,
                signalDbHz = d("cn0"), phase = o["phase"]?.jsonPrimitive?.content ?: "",
            )
        }.toList()
    }
}
