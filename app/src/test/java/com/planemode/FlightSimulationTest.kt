package com.planemode

import com.planemode.calc.*
import com.planemode.model.FlightEventType
import com.planemode.model.FlightPhase
import com.planemode.sim.MockFlightGenerator
import com.planemode.sim.RecordedFlight
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FlightSimulationTest {
    private val samples by lazy { MockFlightGenerator().generateAll(2.0) }

    @Test fun generatorProducesRealisticEwrLaxFlight() {
        val first = samples.first(); val last = samples.last()
        assertTrue(Geo.distanceM(first.lat, first.lon, MockFlightGenerator.EWR.lat, MockFlightGenerator.EWR.lon) < 2_000)
        assertTrue(Geo.distanceM(last.lat, last.lon, MockFlightGenerator.MIA.lat, MockFlightGenerator.MIA.lon) < 1_000)
        assertEquals(37_000.0, samples.maxOf { it.altFt }, 400.0)
        assertTrue(samples.maxOf { it.gsKts } in 430.0..520.0)
        val hours = last.tSec / 3600
        assertTrue("duration $hours h", hours in 2.3..3.3)
        assertEquals("PARKED", last.phase)
        // No unrealistic position jumps
        samples.zipWithNext().forEach { (a, b) -> assertTrue(Geo.distanceM(a.lat, a.lon, b.lat, b.lon) / (b.tSec - a.tSec) < 320) }
    }

    @Test fun phaseDetectionRunsThroughWholeFlight() {
        val det = FlightPhaseDetector(PhaseConfig(maxCruiseKts = 473.0))
        val climb = ClimbRateCalculator()
        val events = ArrayList<FlightEventType>()
        val phases = LinkedHashSet<FlightPhase>()
        for (s in samples) {
            val ms = (s.tSec * 1000).toLong()
            val vs = climb.add(ms, Units.ftToM(s.altFt))
            events += det.update(ms, s.gsKts, s.altFt, vs?.let { it * Units.M_TO_FT * 60 })
            phases += det.phase
        }
        // Keep reporting a parked aircraft for the landing-confirmation window
        val end = (samples.last().tSec * 1000).toLong()
        for (i in 1..120) events += det.update(end + i * 1000L, 0.0, samples.last().altFt, 0.0)
        assertEquals(listOf(FlightEventType.TAKEOFF_SPEED, FlightEventType.TEN_THOUSAND, FlightEventType.CRUISE), events.filter {
            it in setOf(FlightEventType.TAKEOFF_SPEED, FlightEventType.TEN_THOUSAND, FlightEventType.CRUISE) })
        assertEquals(1, events.count { it == FlightEventType.TOP_OF_DESCENT })
        assertEquals(1, events.count { it == FlightEventType.LANDING })
        assertTrue(phases.containsAll(listOf(FlightPhase.TAKEOFF, FlightPhase.INITIAL_CLIMB, FlightPhase.CLIMB, FlightPhase.CRUISE, FlightPhase.DESCENT, FlightPhase.LANDING)))
        assertEquals(FlightPhase.COMPLETED, det.phase)
        assertTrue(det.landedConfirmed)
    }

    @Test fun briefSlowdownDoesNotEndFlight() {
        val det = FlightPhaseDetector()
        var t = 0L
        repeat(10) { det.update(t, 160.0, 500.0, 2000.0); t += 1000 } // takeoff
        repeat(60) { det.update(t, 250.0, 5000.0, 2000.0); t += 1000 }
        repeat(30) { det.update(t, 20.0, 5000.0, 0.0); t += 1000 } // glitch shorter than 90 s
        assertNotEquals(FlightPhase.COMPLETED, det.phase)
        assertFalse(det.landedConfirmed)
    }

    @Test fun jsonlRoundTripAndBundledAsset() {
        val text = samples.take(50).joinToString("\n") { it.toJson() }
        val parsed = RecordedFlight.parse(text.lineSequence())
        assertEquals(50, parsed.size)
        assertEquals(samples[10].lat, parsed[10].lat, 1e-5)
        val asset = File("src/main/assets/mock_flight_ewr_mia.jsonl")
        if (System.getenv("EXPORT_MOCK_JSONL") != null) {
            val data = MockFlightGenerator().generateAll(5.0).toMutableList()
            // Embed a 45 s GPS dropout during cruise to exercise estimated-position rendering
            val cruiseStart = data.indexOfFirst { it.phase == "CRUISE" } + 600
            for (i in cruiseStart until cruiseStart + 9) data[i] = data[i].copy(valid = false, satellites = 0, signalDbHz = 8.0)
            asset.writeText("# planeMode deterministic mock flight EWR->MIA, 5 s interval. Illustrative test data only.\n" + data.joinToString("\n") { it.toJson() } + "\n")
        }
        if (asset.exists()) {
            val bundled = asset.useLines { RecordedFlight.parse(it) }
            assertTrue(bundled.size > 1500)
            assertTrue(bundled.any { !it.valid })
        }
    }
}
