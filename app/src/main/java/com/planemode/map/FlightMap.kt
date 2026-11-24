package com.planemode.map

import android.graphics.Color as AColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.planemode.calc.AutoZoom
import com.planemode.calc.Geo
import com.planemode.calc.Units
import com.planemode.model.*
import com.planemode.tracking.FlightState
import com.planemode.tracking.TrackSnapshot
import com.planemode.ui.components.AircraftShapes
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Imperative handle for zoom buttons and visible-bounds queries (manual region downloads). */
class MapController {
    internal var map: MapLibreMap? = null
    /** Current camera bearing (degrees clockwise from north), observable by UI such as a compass button. */
    var bearing by mutableFloatStateOf(0f)
        internal set
    internal var onManualZoom: () -> Unit = {}
    /** Zoom requested by +/- while following; applied by the frame loop (a separate animation would be cancelled). */
    internal var requestedZoom: Double? = null
    internal var following = false
    private var zoomBeforeOverview: Double? = null

    /** Returns to the zoom level in use before [fitPoints] (applied smoothly once following resumes). */
    fun restoreZoomAfterOverview() {
        zoomBeforeOverview?.let { requestedZoom = it }
        zoomBeforeOverview = null
    }
    /**
     * Fits the camera (north-up) to [points] with the given padding in px.
     * Call only after following has been switched off, otherwise the follow loop cancels the animation.
     */
    fun fitPoints(points: List<LatLon>, left: Int, top: Int, right: Int, bottom: Int) {
        val m = map ?: return
        requestedZoom = null
        zoomBeforeOverview = m.cameraPosition.zoom
        if (points.isEmpty()) return
        if (points.size == 1 || points.all { Geo.distanceM(it, points[0]) < 2_000 }) {
            m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(points[0].lat, points[0].lon)).zoom(11.0).bearing(0.0).build()), 800)
            return
        }
        val b = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        m.easeCamera(CameraUpdateFactory.newLatLngBounds(b, 0.0, 0.0, left, top, right, bottom), 900)
    }

    fun zoomIn() = zoomBy(1.0)
    fun zoomOut() = zoomBy(-1.0)
    private fun zoomBy(delta: Double) {
        onManualZoom()
        val m = map ?: return
        val target = ((requestedZoom ?: m.cameraPosition.zoom) + delta).coerceIn(1.0, AutoZoom.MAX_ZOOM + 0.5)
        if (following) requestedZoom = target else m.animateCamera(CameraUpdateFactory.zoomTo(target), 250)
    }
    fun visibleBounds(): LatLngBounds? = map?.projection?.visibleRegion?.latLngBounds
    val zoom get() = map?.cameraPosition?.zoom
}

private data class Pose(val lat: Double, val lon: Double, val course: Double)

fun speedBandColor(speedMps: Double?, bandsKts: List<Int>): String {
    val kts = Units.mpsToKts(speedMps ?: 0.0)
    val b = bandsKts.sorted()
    return when {
        kts < b.getOrElse(0) { 150 } -> "#22D3EE"
        kts < b.getOrElse(1) { 250 } -> "#34D399"
        kts < b.getOrElse(2) { 350 } -> "#FACC15"
        else -> "#FB923C"
    }
}

/** Groups consecutive track segments by colour/estimated flag into line features. */
fun trackFeatures(points: List<TrackPoint>, bandsKts: List<Int>): FeatureCollection {
    val features = ArrayList<Feature>()
    if (points.size < 2) return FeatureCollection.fromFeatures(features)
    var coords = ArrayList<Point>()
    var curKey: Pair<Boolean, String>? = null
    for (i in 1 until points.size) {
        val a = points[i - 1]; val b = points[i]
        val est = a.source == SampleSource.ESTIMATED || b.source == SampleSource.ESTIMATED
        val key = est to (if (est) "#3C4250" else speedBandColor(b.speedMps, bandsKts))
        if (key != curKey) {
            if (coords.size >= 2) features.add(lineFeature(coords, curKey!!))
            coords = arrayListOf(Point.fromLngLat(a.lon, a.lat))
            curKey = key
        }
        coords.add(Point.fromLngLat(b.lon, b.lat))
    }
    if (coords.size >= 2) features.add(lineFeature(coords, curKey!!))
    return FeatureCollection.fromFeatures(features)
}

private fun lineFeature(coords: List<Point>, key: Pair<Boolean, String>) =
    Feature.fromGeometry(LineString.fromLngLats(unwrap(coords))).apply {
        addBooleanProperty("estimated", key.first); addStringProperty("color", key.second)
    }

/** Keeps longitudes continuous across the antimeridian so lines don't wrap around the globe. */
private fun unwrap(pts: List<Point>): List<Point> {
    if (pts.isEmpty()) return pts
    val out = ArrayList<Point>(pts.size); var offset = 0.0; var prev = pts[0].longitude()
    out.add(pts[0])
    for (i in 1 until pts.size) {
        val lon = pts[i].longitude()
        if (lon - prev > 180) offset -= 360 else if (lon - prev < -180) offset += 360
        prev = lon
        out.add(Point.fromLngLat(lon + offset, pts[i].latitude()))
    }
    return out
}

private fun circle(lat: Double, lon: Double, radiusM: Double): Feature {
    val ring = (0..48).map { val p = Geo.destination(lat, lon, it * 7.5, radiusM); Point.fromLngLat(p.lon, p.lat) }
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
}

@Composable
fun FlightMap(
    modifier: Modifier,
    settings: FlightSettings,
    state: FlightState?,
    track: TrackSnapshot?,
    interactive: Boolean = true,
    following: Boolean = true,
    onUserGesture: () -> Unit = {},
    controller: MapController? = null,
    aircraftKind: SilhouetteKind = SilhouetteKind.NARROWBODY,
    /** Fractions of map height reserved above/below so the aircraft stays clear of overlay panels. */
    focusTop: Double = 0.18,
    focusBottom: Double = 0.42,
    /** Speed/altitude-driven zoom while following. Any manual zoom calls [onManualZoom]. */
    autoZoom: Boolean = false,
    onManualZoom: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current.density
    val dark = settings.mapStyle == MapStyleKind.AVIATION_DARK
    val styleJson = remember(dark, settings.tileProvider, settings.customTileJsonUrl, settings.customGlyphsUrl) { MapStyle.build(settings, dark) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Set false before the MapView is destroyed so late frame callbacks never touch dead native objects.
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    fun guard(block: () -> Unit) { if (alive.get()) runCatching(block) }
    var style by remember { mutableStateOf<Style?>(null) }
    val gestureCallback by rememberUpdatedState(onUserGesture)
    val manualZoomCallback by rememberUpdatedState(onManualZoom)
    SideEffect { controller?.onManualZoom = { manualZoomCallback() } }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.apply {
                    isCompassEnabled = false
                    isLogoEnabled = false
                    isAttributionEnabled = true
                    setAttributionMargins((8 * density).toInt(), 0, 0, (8 * density).toInt())
                    isTiltGesturesEnabled = false
                    setAllGesturesEnabled(interactive)
                    isTiltGesturesEnabled = false
                }
                m.setMinZoomPreference(1.0)
                m.setMaxZoomPreference(AutoZoom.MAX_ZOOM + 0.5)
                m.addOnCameraMoveListener { controller?.bearing = m.cameraPosition.bearing.toFloat() }
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { gestureCallback(); manualZoomCallback() }
                }
                m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(39.0, -95.0)).zoom(if (interactive) 6.0 else 3.2).build()))
                map = m
            }
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            alive.set(false)
            lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
            controller?.map = null
        }
    }

    // Style (re)load
    LaunchedEffect(map, styleJson, aircraftKind) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromJson(styleJson)) { st ->
            st.addImage(MapStyle.AIRCRAFT_ICON, AircraftShapes.mapIcon(aircraftKind, (56 * density).toInt(), AColor.WHITE, AColor.parseColor("#0B1426")))
            style = st
        }
        controller?.map = m
    }

    // Planned route and airports
    val dep = state?.departure; val arr = state?.arrival
    LaunchedEffect(style, dep, arr) {
        val st = style ?: return@LaunchedEffect
        val routeSrc = st.getSourceAs<GeoJsonSource>(MapStyle.SRC_ROUTE)
        val apSrc = st.getSourceAs<GeoJsonSource>(MapStyle.SRC_AIRPORTS)
        if (dep != null && arr != null) {
            val pts = Geo.greatCirclePoints(LatLon(dep.lat, dep.lon), LatLon(arr.lat, arr.lon), 40_000.0).map { Point.fromLngLat(it.lon, it.lat) }
            guard { routeSrc?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(unwrap(pts)))) }
        } else guard { routeSrc?.setGeoJson(FeatureCollection.fromFeatures(emptyList())) }
        val aps = listOfNotNull(dep?.let { it to "#8A96A8" }, arr?.let { it to "#22D3EE" }).map { (a, c) ->
            Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).apply { addStringProperty("code", a.iata); addStringProperty("color", c) }
        }
        guard { apSrc?.setGeoJson(FeatureCollection.fromFeatures(aps)) }
    }

    // Route history (throttled to ~1 Hz by snapshot versioning)
    val bands = settings.speedBandsKts
    LaunchedEffect(style, track?.version, bands) {
        val st = style ?: return@LaunchedEffect
        val pts = track?.points ?: emptyList()
        guard { st.getSourceAs<GeoJsonSource>(MapStyle.SRC_TRACK)?.setGeoJson(trackFeatures(pts, bands)) }
        delay(800)
    }

    // Uncertainty circle when the position is estimated
    LaunchedEffect(style, state?.positionEstimated, state?.uncertaintyM, state?.lat) {
        val st = style ?: return@LaunchedEffect
        val src = st.getSourceAs<GeoJsonSource>(MapStyle.SRC_UNCERTAINTY) ?: return@LaunchedEffect
        if (state != null && state.positionEstimated && state.lat != null && state.lon != null && state.uncertaintyM != null)
            guard { src.setGeoJson(circle(state.lat, state.lon, state.uncertaintyM.coerceAtLeast(50.0))) }
        else guard { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())) }
    }

    // Smooth aircraft interpolation + camera follow
    val target = state?.let { s -> if (s.lat != null && s.lon != null) Pose(s.lat, s.lon, s.courseDeg ?: 0.0) else null }
    val stale = state?.positionStale == true
    var from by remember { mutableStateOf<Pose?>(null) }
    var shown by remember { mutableStateOf<Pose?>(null) }
    var animStart by remember { mutableLongStateOf(0L) }
    var animDur by remember { mutableLongStateOf(1000L) }
    var lastTargetAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(target) {
        val now = System.nanoTime()
        if (target != null) {
            val interval = if (lastTargetAt == 0L) 0L else (now - lastTargetAt) / 1_000_000
            lastTargetAt = now
            from = shown ?: target
            animDur = interval.coerceIn(150, 1500)
            animStart = now
            // Large jumps (e.g. first fix, source switch) are not animated
            val f = from
            if (f == null || Geo.distanceM(f.lat, f.lon, target.lat, target.lon) > 30_000) { from = target; shown = target }
        }
    }
    // The aircraft is drawn as a screen overlay positioned synchronously from the map projection.
    // (Pushing a GeoJSON update every frame queues asynchronously in MapLibre and makes the marker lag.)
    var markerPose by remember { mutableStateOf<Pose?>(null) }
    var markerScreen by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var markerRotation by remember { mutableFloatStateOf(0f) }
    fun updateMarker(m: MapLibreMap) {
        if (!alive.get()) return
        val p = markerPose ?: run { markerScreen = null; return }
        val pt = m.projection.toScreenLocation(LatLng(p.lat, p.lon))
        markerScreen = androidx.compose.ui.geometry.Offset(pt.x, pt.y)
        markerRotation = (p.course - m.cameraPosition.bearing).toFloat()
    }
    DisposableEffect(map) {
        val m = map
        val l = MapLibreMap.OnCameraMoveListener { m?.let { updateMarker(it) } }
        m?.addOnCameraMoveListener(l)
        onDispose { m?.removeOnCameraMoveListener(l) }
    }
    val orientation by rememberUpdatedState(settings.mapOrientation)
    val followNow by rememberUpdatedState(following && interactive)
    val targetNow by rememberUpdatedState(target)
    val focusTopNow by rememberUpdatedState(focusTop)
    val focusBottomNow by rememberUpdatedState(focusBottom)
    val staleNow by rememberUpdatedState(stale)
    val autoZoomNow by rememberUpdatedState(autoZoom && interactive)
    // Ground reference: departure field elevation, else the altitude observed while slow on the ground,
    // so high-elevation airports still get the close-in ground zoom.
    var groundAltM by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(state?.departure, state?.arrival, state?.altitudeM, state?.groundSpeedMps) {
        val alt = state?.altitudeM
        // Use the elevation of whichever planned airport is closer (departure on climb-out, arrival on approach).
        val field = if (state?.lat != null && state.lon != null)
            listOfNotNull(state.departure, state.arrival).minByOrNull { Geo.distanceM(it.lat, it.lon, state.lat, state.lon) }
        else state?.departure
        if (field != null) groundAltM = Units.ftToM(field.elevationFt.toDouble())
        else if (alt != null && (state.groundSpeedMps ?: 0.0) < Units.ktsToMps(40.0) && !state.positionEstimated)
            groundAltM = groundAltM?.let { it * 0.8 + alt * 0.2 } ?: alt
    }
    val heightAgl = state?.altitudeM?.let { a -> groundAltM?.let { a - it } ?: if ((state.groundSpeedMps ?: 0.0) < Units.ktsToMps(40.0)) 0.0 else a }
    val zoomTargetNow by rememberUpdatedState(AutoZoom.target(state?.groundSpeedMps, heightAgl))
    val heightAglNow by rememberUpdatedState(heightAgl)
    val gsNow by rememberUpdatedState(state?.groundSpeedMps)
    LaunchedEffect(style, interactive) {
        val st = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        var lastApplied: Pose? = null
        var lastStale = false
        var ambientLon = -100.0
        var zoom: Double? = null
        var bearing: Double? = null
        var lastPad = Pair(-1.0, -1.0)
        var lastFrame = 0L
        var lastZoomLog = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (!alive.get()) return@withFrameNanos
                val target = targetNow
                val stale = staleNow
                if (!interactive && target == null) {
                    ambientLon += 0.01; if (ambientLon > 180) ambientLon -= 360
                    m.moveCamera(CameraUpdateFactory.newLatLng(LatLng(35.0, ambientLon)))
                    return@withFrameNanos
                }
                val t = target ?: return@withFrameNanos
                val f = from ?: t
                val k = ((now - animStart) / 1_000_000.0 / animDur).coerceIn(0.0, 1.0)
                val pose = Pose(f.lat + (t.lat - f.lat) * k, Geo.normalizeLon(f.lon + Geo.angleDiff(f.lon, t.lon) * k), Geo.lerpAngle(f.course, t.course, k))
                shown = pose
                val dt = if (lastFrame == 0L) 0.0 else (now - lastFrame) / 1e9
                lastFrame = now
                controller?.following = followNow
                val zooming = autoZoomNow && followNow
                val requested = controller?.requestedZoom?.takeIf { followNow && !zooming }
                var zoomSettled: Boolean
                if (zooming) {
                    controller?.requestedZoom = null
                    val z = zoom ?: if (lastApplied == null) zoomTargetNow else m.cameraPosition.zoom
                    zoom = AutoZoom.step(z, zoomTargetNow, dt.coerceAtMost(0.5))
                    zoomSettled = AutoZoom.converged(zoom!!, zoomTargetNow)
                } else if (requested != null) {
                    // Manual +/- : quick ease (~250 ms) toward the requested level
                    val z = zoom ?: m.cameraPosition.zoom
                    zoom = z + (requested - z) * (dt * 10).coerceIn(0.0, 1.0)
                    if (kotlin.math.abs(requested - zoom!!) < 0.01) { zoom = requested; controller?.requestedZoom = null }
                    zoomSettled = false
                } else { zoom = null; zoomSettled = true }
                if (com.planemode.BuildConfig.DEBUG && now - lastZoomLog > 1_000_000_000L) {
                    lastZoomLog = now
                    android.util.Log.d("PM-ZOOM", "gs=%.0fkt agl=%.0fft target=%.2f applied=%.2f cam=%.2f auto=%s follow=%s".format(
                        Units.mpsToKts(gsNow ?: 0.0), Units.mToFt(heightAglNow ?: 0.0), zoomTargetNow,
                        zoom ?: -1.0, m.cameraPosition.zoom, autoZoomNow, followNow))
                }
                // Ease the map bearing toward north (north-up) or the track (track-up) so mode switches are smooth.
                val desiredBearing = if (orientation == MapOrientation.TRACK_UP && interactive) pose.course else 0.0
                val b = bearing ?: m.cameraPosition.bearing
                bearing = if (lastApplied == null) desiredBearing
                    else Geo.lerpAngle(b, desiredBearing, (dt * 5).coerceIn(0.0, 1.0))
                val bearingSettled = kotlin.math.abs(Geo.angleDiff(bearing!!, desiredBearing)) < 0.05
                val pad = Pair(focusTopNow, focusBottomNow)
                val padSettled = pad == lastPad
                lastPad = pad
                if (!followNow) bearing = null
                if (pose == lastApplied && stale == lastStale && zoomSettled && (bearingSettled || !followNow) && padSettled) return@withFrameNanos
                lastApplied = pose; lastStale = stale
                markerPose = pose
                if (followNow || !interactive) {
                    val h = mapView.height.toDouble()
                    val cam = CameraPosition.Builder(m.cameraPosition)
                        .target(LatLng(pose.lat, pose.lon))
                        .bearing(bearing ?: 0.0)
                        .apply { zoom?.let { zoom(it) } }
                        .padding(0.0, if (interactive) h * focusTopNow else 0.0, 0.0, if (interactive) h * focusBottomNow else 0.0)
                        .build()
                    m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
                }
                updateMarker(m)
            }
            delay(30) // ~30 fps is plenty for a smooth marker and keeps CPU low
        }
    }

    LaunchedEffect(map, settings.mapOrientation) {
        val m = map ?: return@LaunchedEffect
        if (interactive && !followNow && settings.mapOrientation == MapOrientation.NORTH_UP)
            m.easeCamera(CameraUpdateFactory.bearingTo(0.0), 400)
    }

    val iconPx = (56 * density).toInt()
    val icon = remember(aircraftKind, iconPx) {
        AircraftShapes.mapIcon(aircraftKind, iconPx, AColor.WHITE, AColor.parseColor("#0B1426")).asImageBitmap()
    }
    androidx.compose.foundation.layout.Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        val pos = markerScreen
        if (pos != null && target != null) {
            androidx.compose.foundation.Image(
                icon, contentDescription = "Aircraft",
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((pos.x - iconPx / 2f).toInt(), (pos.y - iconPx / 2f).toInt()) }
                    .graphicsLayer { rotationZ = markerRotation; alpha = if (stale) 0.55f else 1f },
            )
        }
    }
}
