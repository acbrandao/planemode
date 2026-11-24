package com.planemode.sim

import android.content.Context
import com.planemode.calc.Geo
import com.planemode.calc.Units
import com.planemode.location.PositionSource
import com.planemode.location.SourceEvent
import com.planemode.model.GpsFix
import com.planemode.model.SimRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class SimState(
    val running: Boolean = false,
    val started: Boolean = false,
    val speed: Int = 20,
    val route: SimRoute = SimRoute.PROCEDURAL,
    val virtualSec: Double = 0.0,
    val progress: Double = 0.0,
    val phase: String = "READY",
    val gpsDropped: Boolean = false,
    val weakSignal: Boolean = false,
    val noSatelliteData: Boolean = false,
    val inaccurate: Boolean = false,
    val finished: Boolean = false,
    val scriptedOutage: Boolean = true,
)

/**
 * Simulated GPS source. Uses a virtual clock so durations, phase detection and history remain
 * coherent at 1x/5x/20x/60x playback. Implements the same [PositionSource] contract as real GPS.
 */
@Singleton
class FlightSimulator @Inject constructor(@ApplicationContext private val context: Context) : PositionSource {
    override val isSimulated = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(SimState())
    val state: StateFlow<SimState> = _state
    private val _events = MutableSharedFlow<SourceEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var procedural: MockFlightGenerator? = null
    private var recorded: RecordedFlight? = null
    private var loop: Job? = null
    private var epochBase = System.currentTimeMillis()
    private val monoBase = 1_000_000L
    private var dropUntilSec = -1.0
    private var outageDone = false
    private var outageArmedAtSec: Double? = null
    private val noise = Random(99)
    private var parkedSec = 0.0

    override fun events(intervalMs: Long): Flow<SourceEvent> = flow {
        emit(SourceEvent.Reset(simulated = true))
        emit(SourceEvent.Availability(true, null))
        emitAll(_events)
    }

    fun configure(route: SimRoute, speed: Int, scriptedOutage: Boolean) {
        val s = _state.value
        if (s.route != route && !s.started) _state.value = s.copy(route = route)
        _state.update { it.copy(speed = speed, scriptedOutage = scriptedOutage) }
        if (s.route != route && s.started) { _state.update { it.copy(route = route) }; reset() }
    }

    fun start() {
        if (_state.value.running) return
        if (!_state.value.started || _state.value.finished) newFlight()
        _state.update { it.copy(running = true, started = true) }
        loop = scope.launch { runLoop() }
    }

    fun pause() { loop?.cancel(); loop = null; _state.update { it.copy(running = false) } }
    fun resume() = start()

    fun reset() {
        pause()
        procedural = null; recorded = null
        _state.update { SimState(speed = it.speed, route = it.route, scriptedOutage = it.scriptedOutage) }
        _events.tryEmit(SourceEvent.Reset(simulated = true, hard = true))
    }

    fun setSpeed(x: Int) = _state.update { it.copy(speed = x) }
    fun dropGps(seconds: Int = 30) { dropUntilSec = _state.value.virtualSec + seconds; _state.update { it.copy(gpsDropped = true) } }
    fun recoverGps() { dropUntilSec = -1.0; _state.update { it.copy(gpsDropped = false) } }
    fun setWeak(on: Boolean) = _state.update { it.copy(weakSignal = on) }
    fun setNoSatelliteData(on: Boolean) = _state.update { it.copy(noSatelliteData = on) }
    fun setInaccurate(on: Boolean) = _state.update { it.copy(inaccurate = on) }

    private fun newFlight() {
        epochBase = System.currentTimeMillis()
        dropUntilSec = -1.0; outageDone = false; outageArmedAtSec = null; parkedSec = 0.0
        _events.tryEmit(SourceEvent.Reset(simulated = true))
        when (_state.value.route) {
            SimRoute.PROCEDURAL -> { procedural = MockFlightGenerator(); recorded = null }
            SimRoute.RECORDED -> {
                val samples = context.assets.open("mock_flight_ewr_mia.jsonl").bufferedReader().useLines { RecordedFlight.parse(it) }
                recorded = RecordedFlight(samples); procedural = null
            }
        }
        _state.update { it.copy(virtualSec = 0.0, progress = 0.0, finished = false, phase = "TAXI", gpsDropped = false) }
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val speed = _state.value.speed.coerceIn(1, 120)
            val realPeriod = when { speed <= 1 -> 1000L; speed <= 20 -> 200L; else -> 100L }
            val stepSec = speed * realPeriod / 1000.0
            emitStep(stepSec)
            if (_state.value.finished) { pause(); break }
            delay(realPeriod)
        }
    }

    private fun emitStep(dt: Double) {
        val gen = procedural
        val rec = recorded
        val sample = gen?.step(dt) ?: rec?.step(dt) ?: return
        val t = gen?.tSec ?: rec!!.tSec
        if (gen?.finished ?: rec!!.finished) parkedSec += dt
        // Keep reporting a stationary aircraft for a few minutes so landing confirmation can complete.
        val finished = parkedSec > 240
        val progress = gen?.progress ?: rec!!.progress
        val mono = monoBase + (t * 1000).toLong()
        val epoch = epochBase + (t * 1000).toLong()
        var st = _state.value

        // Scripted outage: 60 s GPS dropout about 45 minutes into cruise.
        if (st.scriptedOutage && !outageDone && sample.phase == "CRUISE") {
            val armed = outageArmedAtSec ?: t.also { outageArmedAtSec = it }
            if (t - armed > 45 * 60) { outageDone = true; dropUntilSec = t + 60; st = st.copy(gpsDropped = true) }
        }
        val dropped = t < dropUntilSec || !sample.valid
        if (st.gpsDropped && !dropped && dropUntilSec >= 0 && t >= dropUntilSec) st = st.copy(gpsDropped = false)

        if (!dropped) {
            var lat = sample.lat; var lon = sample.lon
            var hAcc = sample.hAccM; var vAcc = sample.vAccM
            if (st.weakSignal) { hAcc *= 12; vAcc *= 8 }
            if (st.inaccurate) {
                hAcc = 60.0 + noise.nextDouble(0.0, 90.0); vAcc = hAcc * 1.5
                val p = Geo.destination(lat, lon, noise.nextDouble(0.0, 360.0), noise.nextDouble(0.0, hAcc))
                lat = p.lat; lon = p.lon
            }
            _events.tryEmit(SourceEvent.Fix(GpsFix(
                epochMs = epoch, monoMs = mono, lat = lat, lon = lon,
                altitudeM = Units.ftToM(sample.altFt), speedMps = Units.ktsToMps(sample.gsKts),
                courseDeg = sample.courseDeg, hAccM = hAcc, vAccM = vAcc, speedAccMps = 0.5,
                satellitesUsed = if (st.weakSignal) 4 else sample.satellites, simulated = true,
            )))
        }
        if (!st.noSatelliteData) {
            _events.tryEmit(SourceEvent.Satellites(SimulatedSatellites.snapshot(
                t, mono, (sample.signalDbHz - 38).toFloat(), if (st.weakSignal) 4 else sample.satellites, st.weakSignal, dropped)))
        } else {
            _events.tryEmit(SourceEvent.Satellites(com.planemode.model.GnssSnapshot(false, emptyList(), mono, simulated = true)))
        }
        _events.tryEmit(SourceEvent.Tick(mono, epoch))
        _state.value = st.copy(virtualSec = t, progress = progress, phase = sample.phase, finished = finished)
    }
}
