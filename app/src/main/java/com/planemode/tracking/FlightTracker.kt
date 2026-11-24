package com.planemode.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.planemode.calc.*
import com.planemode.data.AircraftRepository
import com.planemode.data.AirportRepository
import com.planemode.data.SettingsRepository
import com.planemode.data.db.EventEntity
import com.planemode.data.db.FlightDao
import com.planemode.data.db.SampleEntity
import com.planemode.data.db.SessionEntity
import com.planemode.location.LocationHub
import com.planemode.location.SourceEvent
import com.planemode.model.*
import com.planemode.sim.FlightSimulator
import com.planemode.sound.SoundPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * The flight-tracking engine. Runs on a single-threaded dispatcher, completely independent
 * of the current UI screen, and is kept alive in the background by [TrackingService].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FlightTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hub: LocationHub,
    private val simulator: FlightSimulator,
    private val settingsRepo: SettingsRepository,
    private val dao: FlightDao,
    private val sound: SoundPlayer,
    private val aircraftRepo: AircraftRepository,
    private val airportRepo: AirportRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val _state = MutableStateFlow(FlightState())
    val state: StateFlow<FlightState> = _state.asStateFlow()
    private val _track = MutableStateFlow(TrackSnapshot())
    val track: StateFlow<TrackSnapshot> = _track.asStateFlow()
    private val _gnss = MutableStateFlow<GnssSnapshot?>(null)
    val gnss: StateFlow<GnssSnapshot?> = _gnss.asStateFlow()
    private val _events = MutableStateFlow<List<FlightEvent>>(emptyList())
    val events: StateFlow<List<FlightEvent>> = _events.asStateFlow()
    private val _banners = MutableSharedFlow<FlightEvent>(extraBufferCapacity = 8)
    val banners: SharedFlow<FlightEvent> = _banners

    private val demand = MutableStateFlow<Set<String>>(emptySet())
    fun acquire(tag: String) = demand.update { it + tag }
    fun release(tag: String) = demand.update { it - tag }

    // ---- engine components ----
    private val filter = FixFilter()
    private val gpsMonitor = GpsStatusMonitor()
    private val climb = ClimbRateCalculator()
    private val distance = DistanceAccumulator()
    private val recorder = TrackRecorder()
    private val etaCalc = EtaCalculator()
    private val phaseDetector = FlightPhaseDetector()
    private val autoStart = AutoStartDetector()
    private val speedEma = TimeEma(3_000.0)

    // ---- live values ----
    private var lastFix: GpsFix? = null
    private var reliableFix: GpsFix? = null
    private var reliableSpeed = 0.0
    private var reliableCourse = 0.0
    private var currentSpeed: Double? = null
    private var currentCourse: Double? = null
    private var verticalSpeed: Double? = null
    private var nowMono = 0L
    private var nowEpoch = System.currentTimeMillis()
    private var lastTickMono: Long? = null
    private var sourceAvailable = true
    private var availabilityReason: String? = null
    private var estimate: PositionEstimator.Estimate? = null
    private var lastGpsAcquiredMono = Long.MIN_VALUE / 2

    // ---- session ----
    private var session: SessionEntity? = null
    private var aircraft: AircraftProfile? = null
    private var departure: Airport? = null
    private var arrival: Airport? = null
    private var elapsedBaseMs = 0L
    private var elapsedRefMono: Long? = null
    private var lastElapsedMs = 0L
    private var timers = GpsTimers()
    private var maxAlt = 0.0
    private var maxSpeed = 0.0
    private var autoStopOverride = false
    private var landingDismissed = false
    private val pending = ArrayList<SampleEntity>()
    private var lastFlushReal = 0L
    private var lastSaveReal = 0L
    private var monitoring = false

    private val settings get() = settingsRepo.current

    init {
        scope.launch {
            runCatching { recover() }
            demand.map { it.isNotEmpty() }.distinctUntilChanged()
                .flatMapLatest { if (it) hub.events() else emptyFlow() }
                .collect { runCatching { handle(it) } }
        }
        scope.launch { settingsRepo.settings.collect { applySettings(it) } }
    }

    // =============================== public API ===============================

    fun startFlight() = scope.launch { startFlightInternal() }

    fun endFlight() = scope.launch { endFlightInternal() }

    fun setAutoStopOverride(on: Boolean) = scope.launch { autoStopOverride = on; publish() }

    fun dismissLandingSuggestion() = scope.launch { landingDismissed = true; publish() }

    /** Called when the UI becomes visible so background services can be (re)started legally. */
    fun onForeground() {
        acquire("ui")
        scope.launch { if (session != null || monitoring) ensureService() }
    }

    fun onBackground() = release("ui")

    // =============================== event handling ===============================

    private suspend fun handle(e: SourceEvent) {
        when (e) {
            is SourceEvent.Reset -> {
                resetLive()
                val s = session
                if (s != null && (s.simulated != e.simulated || (e.hard && s.simulated))) endFlightInternal()
            }
            is SourceEvent.Availability -> { sourceAvailable = e.available; availabilityReason = e.reason; publish() }
            is SourceEvent.Satellites -> _gnss.value = e.snapshot
            is SourceEvent.Fix -> onFix(e.fix)
            is SourceEvent.Tick -> onTick(e.monoMs, e.epochMs)
        }
    }

    private fun resetLive() {
        if (session != null) { elapsedBaseMs = currentElapsed(); elapsedRefMono = null }
        filter.reset(); gpsMonitor.reset(); climb.reset(); speedEma.reset(); etaCalc.reset(); autoStart.reset()
        lastFix = null; reliableFix = null; currentSpeed = null; currentCourse = null; verticalSpeed = null
        nowMono = 0; lastTickMono = null; estimate = null
        _gnss.value = null
        distance.reset(distance.totalM)
    }

    private fun advanceClock(mono: Long, epoch: Long) {
        if (mono > nowMono) nowMono = mono
        nowEpoch = epoch
        if (session != null && elapsedRefMono == null) elapsedRefMono = nowMono
    }

    private suspend fun onFix(fix: GpsFix) {
        advanceClock(fix.monoMs, fix.epochMs)
        if (filter.check(fix, nowMono) != FixFilter.Result.ACCEPTED) { publish(); return }
        val s = settings
        val accuracyOk = (fix.hAccM ?: 0.0) <= s.minAccuracyM
        val prev = lastFix
        val prevState = gpsMonitor.state

        val moved = prev?.let { Geo.distanceM(it.lat, it.lon, fix.lat, fix.lon) } ?: 0.0
        val dt = prev?.let { (fix.monoMs - it.monoMs) / 1000.0 } ?: 0.0
        val rawSpeed = fix.speedMps ?: if (dt > 0) moved / dt else null
        currentSpeed = rawSpeed?.let { speedEma.update(it, fix.monoMs) }
        currentCourse = when {
            fix.courseDeg != null && (rawSpeed ?: 0.0) > 3.0 -> fix.courseDeg
            prev != null && moved > 30 && (rawSpeed ?: 0.0) > 3.0 -> Geo.initialBearing(prev.lat, prev.lon, fix.lat, fix.lon)
            else -> currentCourse
        }
        fix.altitudeM?.let { alt -> if ((fix.vAccM ?: 0.0) < 60) verticalSpeed = climb.add(fix.monoMs, alt) }
        lastFix = fix
        estimate = null
        val newState = gpsMonitor.onFix(fix.monoMs, accuracyOk)
        if (accuracyOk && (currentSpeed ?: 0.0) > 10 && currentCourse != null) {
            reliableFix = fix; reliableSpeed = currentSpeed!!; reliableCourse = currentCourse!!
        }
        if (accuracyOk) currentSpeed?.let { etaCalc.updateSpeed(it, fix.monoMs) }
        onGpsTransition(prevState, newState)

        val sess = session
        if (sess != null) {
            distance.add(fix)
            val point = TrackPoint(fix.epochMs, fix.monoMs, fix.lat, fix.lon, fix.altitudeM, currentSpeed, SampleSource.GPS, fix.hAccM, distance.totalM)
            if (recorder.last?.source == SampleSource.ESTIMATED) {
                val corrected = recorder.reconcile(point)
                corrected.forEach { queue(it) }
                queue(point)
                publishTrack()
            } else if (recorder.addMeasured(point)) {
                queue(point); publishTrack()
            }
            if (accuracyOk) {
                val evs = phaseDetector.update(
                    fix.monoMs, Units.mpsToKts(currentSpeed ?: 0.0),
                    fix.altitudeM?.let { Units.mToFt(it) }, verticalSpeed?.let { it * Units.M_TO_FT * 60 },
                )
                evs.forEach { emitEvent(it) }
            }
            fix.altitudeM?.let { maxAlt = max(maxAlt, it) }
            if (accuracyOk) currentSpeed?.let { maxSpeed = max(maxSpeed, it) }
        } else if (monitoring) {
            if (autoStart.update(nowMono, fix.monoMs, currentSpeed?.let { Units.mpsToKts(it) }, fix.hAccM, measured = true)) {
                startFlightInternal()
            }
        }
        publish()
    }

    private suspend fun onTick(mono: Long, epoch: Long) {
        advanceClock(mono, epoch)
        val prevState = gpsMonitor.state
        gpsMonitor.estimationAvailable = session != null && reliableFix != null
        val st = gpsMonitor.onTick(nowMono)
        onGpsTransition(prevState, st)

        val sess = session
        if (sess != null) {
            val dt = lastTickMono?.let { (nowMono - it).coerceIn(0, 300_000) } ?: 0
            lastTickMono = nowMono
            timers = when {
                st.hasValidFix -> timers.copy(validMs = timers.validMs + dt)
                st == GpsState.ESTIMATED -> timers.copy(estimatedMs = timers.estimatedMs + dt)
                else -> timers.copy(noPositionMs = timers.noPositionMs + dt)
            }
            val rf = reliableFix
            if ((st == GpsState.ESTIMATED || st == GpsState.LOST) && rf != null) {
                val est = PositionEstimator.estimate(
                    rf.lat, rf.lon, rf.hAccM ?: 10.0, reliableSpeed, reliableCourse,
                    nowMono - rf.monoMs, gpsMonitor.lostAfterMs + gpsMonitor.predictionWindowMs,
                )
                estimate = est
                if (st == GpsState.ESTIMATED && !est.stale) {
                    val p = TrackPoint(nowEpoch, nowMono, est.position.lat, est.position.lon, lastFix?.altitudeM, reliableSpeed,
                        SampleSource.ESTIMATED, est.uncertaintyM, distance.totalM)
                    if (recorder.addEstimated(p)) publishTrack()
                }
            }
            if (AutoStopPolicy.shouldStop(settings.autoStop, autoStopOverride, phaseDetector.phase, phaseDetector.landedConfirmed)) {
                endFlightInternal(); return
            }
            val real = System.currentTimeMillis()
            if (real - lastFlushReal > 20_000 || pending.size >= 40) flush()
            if (real - lastSaveReal > 15_000) saveSession()
        }
        publish()
    }

    private suspend fun onGpsTransition(prev: GpsState, now: GpsState) {
        if (prev == now || session == null) return
        val wasValid = prev.hasValidFix
        if (wasValid && (now == GpsState.LOST || now == GpsState.ESTIMATED)) emitEvent(FlightEventType.GPS_LOST)
        if (!wasValid && (now == GpsState.LOCKED || now == GpsState.REACQUIRED) && nowMono - lastGpsAcquiredMono > 60_000) {
            lastGpsAcquiredMono = nowMono
            emitEvent(FlightEventType.GPS_ACQUIRED)
        }
    }

    private suspend fun emitEvent(type: FlightEventType) {
        val pos = displayPosition()
        val ev = FlightEvent(type, nowEpoch, pos?.lat, pos?.lon, lastFix?.altitudeM)
        _events.update { (it + ev).takeLast(100) }
        _banners.tryEmit(ev)
        sound.onEvent(type)
        session?.let { s -> withContext(Dispatchers.IO) { runCatching { dao.insertEvent(EventEntity(0, s.id, type.name, ev.epochMs, ev.lat, ev.lon, ev.altitudeM)) } } }
    }

    // =============================== session lifecycle ===============================

    private suspend fun startFlightInternal() {
        if (session != null) return
        val s = settings
        aircraft = aircraftRepo.byId(s.selectedAircraftId ?: s.defaultAircraftId)
        departure = airportRepo.find(s.departureCode)
        arrival = airportRepo.find(s.arrivalCode)
        applyPhaseConfig(s)
        val entity = SessionEntity(
            startEpochMs = System.currentTimeMillis(), aircraftId = aircraft?.id,
            departure = departure?.iata, arrival = arrival?.iata, simulated = s.simEnabled,
        )
        val id = withContext(Dispatchers.IO) { dao.insertSession(entity) }
        session = entity.copy(id = id)
        recorder.clear(); distance.reset(); phaseDetector.reset(FlightPhase.ARMED)
        timers = GpsTimers(); maxAlt = 0.0; maxSpeed = 0.0; autoStopOverride = false; landingDismissed = false
        elapsedBaseMs = 0; elapsedRefMono = if (nowMono > 0) nowMono else null; lastTickMono = null
        _events.value = emptyList(); pending.clear()
        // Record the starting position if we already hold a current, valid fix.
        lastFix?.takeIf { gpsMonitor.state.hasValidFix }?.let { f ->
            distance.add(f)
            val p = TrackPoint(f.epochMs, f.monoMs, f.lat, f.lon, f.altitudeM, currentSpeed, SampleSource.GPS, f.hAccM, 0.0)
            if (recorder.addMeasured(p, force = true)) queue(p)
        }
        publishTrack()
        if (s.simEnabled) simulator.start()
        acquire("session")
        updateMonitoring()
        ensureService()
        saveSession()
        publish()
    }

    private suspend fun endFlightInternal() {
        val s = session ?: return
        emitEvent(FlightEventType.FLIGHT_COMPLETE)
        recorder.trailingEstimated().forEach { queue(it) }
        lastElapsedMs = currentElapsed()
        val final = buildSessionEntity(s).copy(endEpochMs = System.currentTimeMillis(), phase = FlightPhase.COMPLETED.name)
        withContext(Dispatchers.IO) {
            runCatching { if (pending.isNotEmpty()) dao.insertSamples(pending.toList()); dao.updateSession(final); dao.prune(30) }
        }
        pending.clear()
        session = null
        phaseDetector.reset(FlightPhase.COMPLETED)
        release("session")
        updateMonitoring()
        // A simulated flight ends with the simulator: stop playback and rewind it to the gate.
        if (s.simulated) simulator.reset()
        publish()
    }

    private suspend fun recover() {
        val s = withContext(Dispatchers.IO) { dao.activeSession() } ?: return
        if (s.simulated) { // A simulation cannot be resumed after process death – close it cleanly.
            withContext(Dispatchers.IO) { dao.updateSession(s.copy(endEpochMs = s.lastUpdateEpochMs, phase = FlightPhase.COMPLETED.name)) }
            return
        }
        session = s
        aircraft = aircraftRepo.byId(s.aircraftId)
        departure = airportRepo.find(s.departure)
        arrival = airportRepo.find(s.arrival)
        applyPhaseConfig(settings)
        val pts = withContext(Dispatchers.IO) { dao.samples(s.id) }.map { it.toPoint() }
        // Rebase monotonic times (the device may have rebooted) so new samples append cleanly.
        val shift = (pts.lastOrNull()?.monoMs ?: 0L) + 1
        recorder.load(pts.map { it.copy(monoMs = it.monoMs - shift) })
        distance.reset(s.distanceM)
        timers = GpsTimers(s.gpsValidMs, s.estimatedMs, s.noPositionMs)
        maxAlt = s.maxAltitudeM; maxSpeed = s.maxSpeedMps
        elapsedBaseMs = s.elapsedMs + (System.currentTimeMillis() - s.lastUpdateEpochMs).coerceAtLeast(0)
        elapsedRefMono = null
        phaseDetector.reset()
        runCatching { phaseDetector.restore(FlightPhase.valueOf(s.phase)) }
        _events.value = withContext(Dispatchers.IO) { dao.events(s.id) }.mapNotNull { e ->
            runCatching { FlightEvent(FlightEventType.valueOf(e.type), e.epochMs, e.lat, e.lon, e.altitudeM) }.getOrNull()
        }
        acquire("session")
        ensureService()
        publishTrack(); publish()
    }

    private fun currentElapsed(): Long =
        if (session == null) lastElapsedMs else elapsedBaseMs + (elapsedRefMono?.let { (nowMono - it).coerceAtLeast(0) } ?: 0)

    private fun buildSessionEntity(s: SessionEntity) = s.copy(
        distanceM = distance.totalM, elapsedMs = currentElapsed(), gpsValidMs = timers.validMs,
        estimatedMs = timers.estimatedMs, noPositionMs = timers.noPositionMs, phase = phaseDetector.phase.name,
        maxAltitudeM = maxAlt, maxSpeedMps = maxSpeed, lastUpdateEpochMs = System.currentTimeMillis(),
    )

    private suspend fun saveSession() {
        val s = session ?: return
        lastSaveReal = System.currentTimeMillis()
        val e = buildSessionEntity(s)
        session = e
        withContext(Dispatchers.IO) { runCatching { dao.updateSession(e) } }
    }

    private fun queue(p: TrackPoint) {
        val s = session ?: return
        pending.add(SampleEntity.from(s.id, p))
    }

    private suspend fun flush() {
        lastFlushReal = System.currentTimeMillis()
        if (pending.isEmpty()) return
        val batch = pending.toList(); pending.clear()
        withContext(Dispatchers.IO) { runCatching { dao.insertSamples(batch) } }
    }

    // =============================== settings & service ===============================

    private fun applySettings(s: FlightSettings) = scope.launch {
        gpsMonitor.predictionWindowMs = s.predictionSec * 1000L
        autoStart.thresholdKts = s.autoStartKts.toDouble()
        autoStart.maxAccuracyM = s.minAccuracyM.toDouble()
        applyPhaseConfig(s)
        simulator.configure(s.simRoute, s.simSpeed, s.simScriptedOutage)
        if (session == null) {
            departure = airportRepo.find(s.departureCode)
            arrival = airportRepo.find(s.arrivalCode)
            aircraft = aircraftRepo.byId(s.selectedAircraftId ?: s.defaultAircraftId)
            applyPhaseConfig(s)
        }
        val sess = session
        if (sess != null && sess.simulated != s.simEnabled) endFlightInternal()
        updateMonitoring()
    }

    private fun applyPhaseConfig(s: FlightSettings) {
        phaseDetector.config = PhaseConfig(
            takeoffSpeedKts = s.takeoffAlertKts.toDouble(), taxiSpeedKts = s.autoStopKts.toDouble(),
            landingConfirmMs = s.landingConfirmSec * 1000L, maxCruiseKts = aircraft?.maxCruiseKts?.toDouble(),
        )
    }

    private fun updateMonitoring() {
        val m = settings.autoStart && session == null
        if (m == monitoring) return
        monitoring = m
        autoStart.reset()
        if (m) { acquire("monitor"); ensureService() } else release("monitor")
        publish()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ensureService() {
        if (!hasLocationPermission()) return // simulation without permission keeps running in-process
        runCatching { TrackingService.start(context) }
    }

    // =============================== publishing ===============================

    private fun displayPosition(): LatLon? = estimate?.position ?: lastFix?.let { LatLon(it.lat, it.lon) }

    private fun publishTrack() { _track.value = TrackSnapshot(recorder.points.toList(), recorder.version) }

    private fun publish() {
        val st = gpsMonitor.state
        val pos = displayPosition()
        val est = estimate
        val estimated = est != null && (st == GpsState.ESTIMATED || st == GpsState.LOST)
        val stale = estimated && est!!.stale || (st == GpsState.LOST && est == null && lastFix != null)
        val arr = arrival
        val remaining = if (pos != null && arr != null) Geo.distanceM(pos.lat, pos.lon, arr.lat, arr.lon) else null
        val dep = departure
        val total = if (dep != null && arr != null) Geo.distanceM(dep.lat, dep.lon, arr.lat, arr.lon) else null
        val positionReliable = st.hasValidFix || (st == GpsState.ESTIMATED && est?.stale == false)
        val speedReliable = st == GpsState.LOCKED || st == GpsState.REACQUIRED
        val eta = etaCalc.compute(remaining, positionReliable, speedReliable, aircraft?.typicalCruiseKts?.let { Units.ktsToMps(it.toDouble()) })
        val gpsState = if (!sourceAvailable && (lastFix == null || !st.hasValidFix)) GpsState.UNAVAILABLE else st
        val active = session != null
        _state.value = FlightState(
            sessionId = session?.id,
            active = active,
            monitoring = monitoring,
            simulated = settings.simEnabled,
            aircraft = if (active) aircraft else aircraftRepo.byId(settings.selectedAircraftId ?: settings.defaultAircraftId),
            departure = dep, arrival = arr,
            lat = pos?.lat, lon = pos?.lon,
            altitudeM = lastFix?.altitudeM,
            groundSpeedMps = if (estimated) reliableSpeed else currentSpeed,
            courseDeg = if (estimated) reliableCourse else currentCourse,
            verticalSpeedMps = if (st.hasValidFix) verticalSpeed else null,
            positionEstimated = estimated,
            positionStale = stale,
            uncertaintyM = if (estimated) est?.uncertaintyM else lastFix?.hAccM,
            gpsState = gpsState,
            gpsReason = availabilityReason,
            hAccM = lastFix?.hAccM, vAccM = lastFix?.vAccM,
            satellitesUsed = _gnss.value?.used ?: lastFix?.satellitesUsed,
            lastFixEpochMs = lastFix?.epochMs,
            sinceLastFixMs = gpsMonitor.sinceLastFix(nowMono),
            currentLockMs = gpsMonitor.lockStartMono?.let { nowMono - it },
            currentLossMs = if (!st.hasValidFix) gpsMonitor.lastFixMono?.let { nowMono - it } else null,
            elapsedMs = currentElapsed(),
            startedEpochMs = session?.startEpochMs,
            distanceM = distance.totalM,
            remainingM = remaining, routeTotalM = total, eta = eta,
            phase = if (active) phaseDetector.phase else if (lastElapsedMs > 0) FlightPhase.COMPLETED else FlightPhase.IDLE,
            timers = timers, maxAltitudeM = maxAlt, maxSpeedMps = maxSpeed,
            autoStopOverride = autoStopOverride,
            landedSuggested = active && phaseDetector.landedConfirmed && !settings.autoStop && !landingDismissed,
            nowEpochMs = nowEpoch,
        )
    }
}
