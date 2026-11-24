package com.planemode

import com.planemode.calc.*
import com.planemode.model.*
import org.junit.Assert.*
import org.junit.Test

private fun fix(t: Long, lat: Double, lon: Double, speed: Double? = 200.0, acc: Double = 5.0, alt: Double? = 10_000.0) =
    GpsFix(epochMs = t, monoMs = t, lat = lat, lon = lon, altitudeM = alt, speedMps = speed, courseDeg = 90.0, hAccM = acc, vAccM = 8.0)

class GeoTest {
    @Test fun greatCircleEwrLax() {
        val d = Geo.distanceM(40.6895, -74.1745, 33.9425, -118.4081)
        assertEquals(3_950_000.0, d, 0.01 * 3_950_000) // ~2,133 nm
        assertEquals(2133.0, d / Units.NM_TO_M, 25.0)
    }
    @Test fun destinationRoundTrip() {
        val p = Geo.destination(10.0, 20.0, 45.0, 100_000.0)
        assertEquals(100_000.0, Geo.distanceM(10.0, 20.0, p.lat, p.lon), 1.0)
        assertEquals(45.0, Geo.initialBearing(10.0, 20.0, p.lat, p.lon), 0.5)
    }
    @Test fun angleHelpers() {
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(0.0, Geo.lerpAngle(350.0, 10.0, 0.5), 1e-9)
    }
}

class UnitsTest {
    @Test fun speedConversions() {
        assertEquals(194.38, Units.speed(100.0, SpeedUnit.KNOTS), 0.01)
        assertEquals(223.69, Units.speed(100.0, SpeedUnit.MPH), 0.01)
        assertEquals(360.0, Units.speed(100.0, SpeedUnit.KMH), 1e-9)
        assertEquals(100.0, Units.speedToMps(Units.speed(100.0, SpeedUnit.MPH), SpeedUnit.MPH), 1e-9)
        assertEquals(450.0, Units.mpsToKts(Units.ktsToMps(450.0)), 1e-9)
    }
    @Test fun altitudeConversions() {
        assertEquals(35_000.0, Units.altitude(Units.ftToM(35_000.0), AltitudeUnit.FEET), 1e-6)
        assertEquals(10_668.0, Units.ftToM(35_000.0), 0.01)
        assertEquals(1000.0, Units.altitude(1000.0, AltitudeUnit.METERS), 1e-9)
        assertEquals(1968.5, Units.verticalSpeed(10.0, AltitudeUnit.FEET), 0.1) // 10 m/s ≈ 1969 fpm
        assertEquals(1.0, Units.distance(1852.0, SpeedUnit.KNOTS), 1e-9)
    }
}

class DistanceAccumulatorTest {
    @Test fun accumulatesAndRejectsJumps() {
        val acc = DistanceAccumulator()
        var lon = -100.0
        for (i in 0..10) { acc.add(fix(i * 1000L, 40.0, lon)); lon += 0.0025 } // ~213 m per second
        val expected = Geo.distanceM(40.0, -100.0, 40.0, -100.0 + 0.0025 * 10)
        assertEquals(expected, acc.totalM, 1.0)
        val before = acc.totalM
        assertEquals(0.0, acc.add(fix(11_000L, 41.0, lon)), 0.0) // 111 km in 1 s – rejected
        assertEquals(before, acc.totalM, 0.0)
        assertTrue(acc.add(fix(12_000L, 40.0, lon + 0.0025)) > 0) // continues from last good anchor
    }
    @Test fun ignoresStationaryJitterAndInaccurateFixes() {
        val acc = DistanceAccumulator()
        acc.add(fix(0, 40.0, -100.0, speed = 0.2))
        acc.add(fix(1000, 40.00003, -100.0, speed = 0.3))
        acc.add(fix(2000, 40.01, -100.0, speed = 100.0, acc = 500.0))
        assertEquals(0.0, acc.totalM, 0.0)
    }
}

class ClimbRateTest {
    @Test fun steadyClimb() {
        val c = ClimbRateCalculator()
        var r: Double? = null
        for (i in 0..30) r = c.add(i * 1000L, 1000.0 + 10.0 * i + if (i % 2 == 0) 1.5 else -1.5)
        assertEquals(10.0, r!!, 0.3)
    }
    @Test fun needsEnoughData() {
        val c = ClimbRateCalculator()
        assertNull(c.add(0, 100.0))
        assertNull(c.add(500, 101.0))
    }
}

class GpsStatusMonitorTest {
    @Test fun lossEstimationAndRecovery() {
        val m = GpsStatusMonitor(predictionWindowMs = 30_000)
        assertEquals(GpsState.SEARCHING, m.state)
        for (t in 0..10) m.onFix(t * 1000L, accuracyOk = true)
        assertEquals(GpsState.LOCKED, m.state)
        assertEquals(GpsState.WEAK, m.onTick(15_000))           // 5 s without fix
        m.estimationAvailable = true
        assertEquals(GpsState.ESTIMATED, m.onTick(21_000))      // >10 s: lost, estimating
        assertEquals(GpsState.ESTIMATED, m.onTick(45_000))      // within loss threshold + window
        assertEquals(GpsState.LOST, m.onTick(52_000))           // window exceeded -> stale
        assertEquals(GpsState.REACQUIRED, m.onFix(53_000, true))
        for (t in 54..64) m.onFix(t * 1000L, true)
        assertEquals(GpsState.LOCKED, m.state)
    }
    @Test fun poorAccuracyIsWeak() {
        val m = GpsStatusMonitor()
        assertEquals(GpsState.WEAK, m.onFix(1000, accuracyOk = false))
    }
}

class PositionEstimatorTest {
    @Test fun extrapolatesAndFreezes() {
        val e = PositionEstimator.estimate(0.0, 0.0, 5.0, 250.0, 90.0, 20_000, 40_000)
        assertEquals(5_000.0, Geo.distanceM(0.0, 0.0, e.position.lat, e.position.lon), 5.0)
        assertFalse(e.stale)
        val late = PositionEstimator.estimate(0.0, 0.0, 5.0, 250.0, 90.0, 90_000, 40_000)
        assertTrue(late.stale)
        assertEquals(10_000.0, Geo.distanceM(0.0, 0.0, late.position.lat, late.position.lon), 5.0) // frozen at window
        assertTrue(late.uncertaintyM > e.uncertaintyM)
    }
}

class TrackReconciliationTest {
    @Test fun estimatedRunIsBlendedIntoNewFix() {
        val r = TrackRecorder(minIntervalMs = 1000)
        fun p(t: Long, lon: Double, src: SampleSource) = TrackPoint(t, t, 40.0, lon, 10_000.0, 250.0, src)
        r.addMeasured(p(0, -100.0, SampleSource.GPS))
        r.addMeasured(p(10_000, -99.97, SampleSource.GPS))
        // Dead-reckoned points drift north of the true track
        for (i in 1..4) r.addEstimated(p(10_000 + i * 10_000L, -99.97 + 0.03 * i, SampleSource.ESTIMATED).copy(lat = 40.0 + 0.01 * i))
        val newFix = p(60_000, -99.82, SampleSource.GPS)
        val corrected = r.reconcile(newFix)
        assertEquals(4, corrected.size)
        val pts = r.points
        // No "teleport": every consecutive step is small, and the run is pulled onto the new fix
        for (i in 1 until pts.size) assertTrue(Geo.distanceM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon) < 3_500)
        val uncorrectedGap = Geo.distanceM(40.04, -99.85, newFix.lat, newFix.lon)
        val gap = Geo.distanceM(pts[pts.size - 2].lat, pts[pts.size - 2].lon, newFix.lat, newFix.lon)
        assertTrue("gap $gap vs $uncorrectedGap", gap < uncorrectedGap * 0.5)
        assertEquals(SampleSource.GPS, pts.last().source)
        assertEquals(SampleSource.ESTIMATED, pts[pts.size - 2].source) // estimated points keep their marking
    }
}

class EtaTest {
    @Test fun etaFromSmoothedSpeedAndFallback() {
        val e = EtaCalculator(tauMs = 1000.0)
        for (t in 0..60) e.updateSpeed(250.0, t * 1000L)
        val eta = e.compute(900_000.0, positionReliable = true, speedReliable = true, fallbackCruiseMps = 230.0)
        assertEquals(EtaBasis.GPS_GROUND_SPEED, eta.basis)
        assertEquals(3_600_000.0, eta.remainingMs!!.toDouble(), 5_000.0)
        val fb = e.compute(460_000.0, positionReliable = true, speedReliable = false, fallbackCruiseMps = 230.0)
        assertEquals(EtaBasis.TYPICAL_CRUISE, fb.basis)
        assertEquals(2_000_000L, fb.remainingMs)
        assertEquals(EtaBasis.NONE, e.compute(1000.0, positionReliable = false, speedReliable = true, fallbackCruiseMps = 230.0).basis)
    }
}

class AutoFlightTest {
    @Test fun autoStartNeedsSustainedAccurateMeasuredSpeed() {
        val a = AutoStartDetector(thresholdKts = 140.0, sustainMs = 8_000)
        assertFalse(a.update(0, 0, 150.0, 5.0, measured = true))
        assertFalse(a.update(5_000, 5_000, 150.0, 5.0, measured = true))
        assertFalse(a.update(6_000, 6_000, 150.0, 5.0, measured = false)) // estimated data resets
        assertFalse(a.update(7_000, 7_000, 150.0, 5.0, measured = true))
        assertFalse(a.update(10_000, 10_000, 150.0, 200.0, measured = true)) // inaccurate resets
        assertFalse(a.update(11_000, 11_000, 150.0, 5.0, measured = true))
        assertTrue(a.update(19_000, 19_000, 155.0, 5.0, measured = true))
        assertFalse(AutoStartDetector().update(30_000, 20_000, 160.0, 5.0, true)) // stale fix
    }
    @Test fun autoStopPolicy() {
        assertTrue(AutoStopPolicy.shouldStop(true, false, FlightPhase.COMPLETED, true))
        assertFalse(AutoStopPolicy.shouldStop(true, true, FlightPhase.COMPLETED, true))
        assertFalse(AutoStopPolicy.shouldStop(false, false, FlightPhase.COMPLETED, true))
        assertFalse(AutoStopPolicy.shouldStop(true, false, FlightPhase.LANDING, false))
    }
}

class AutoZoomTest {
    @Test fun zoomsInWhenSlowAndLowOutWhenFastAndHigh() {
        val taxi = AutoZoom.target(Units.ktsToMps(15.0), 30.0)
        val landing = AutoZoom.target(Units.ktsToMps(140.0), Units.ftToM(500.0))
        val climb = AutoZoom.target(Units.ktsToMps(280.0), Units.ftToM(15_000.0))
        val cruise = AutoZoom.target(Units.ktsToMps(460.0), Units.ftToM(37_000.0))
        assertTrue(taxi > landing && landing > climb && climb > cruise)
        assertTrue(cruise >= AutoZoom.MIN_ZOOM && taxi <= AutoZoom.MAX_ZOOM)
        assertTrue("airport zoom $taxi", taxi >= 14.5)          // detailed airport view on the ground
        assertTrue("cruise zoom $cruise", cruise in 6.0..7.5)    // wide regional view at cruise
        // Higher altitude at the same speed zooms out further
        assertTrue(AutoZoom.target(200.0, 1_000.0) > AutoZoom.target(200.0, 11_000.0))
    }
    @Test fun changesAreGradual() {
        var z = 7.0
        val first = AutoZoom.step(z, 13.0, 1.0)
        assertTrue(first - z <= 0.25 + 1e-9) // rate-limited
        repeat(120) { z = AutoZoom.step(z, 13.0, 0.5) }
        assertEquals(13.0, z, 0.05) // converges
        assertEquals(10.0, AutoZoom.step(10.0, 12.0, 0.0), 0.0)
    }
}
