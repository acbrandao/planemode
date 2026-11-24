package com.planemode.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.planemode.model.Constellation
import com.planemode.model.GnssSnapshot
import com.planemode.model.GpsFix
import com.planemode.model.SatelliteInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real GNSS source: Fused Location Provider when Google Play services is available,
 * otherwise the platform GPS provider. Satellite data via [GnssStatus].
 */
@Singleton
class AndroidPositionSource @Inject constructor(@ApplicationContext private val context: Context) : PositionSource {
    override val isSimulated = false
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun playServicesAvailable() =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    val hasGnssHardware get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

    @SuppressLint("MissingPermission")
    override fun events(intervalMs: Long): Flow<SourceEvent> = callbackFlow {
        trySend(SourceEvent.Reset(simulated = false))
        val handler = Handler(Looper.getMainLooper())
        var fusedCallback: LocationCallback? = null
        var platformListener: LocationListener? = null
        var gnssCallback: GnssStatus.Callback? = null

        fun toFix(l: Location) = GpsFix(
            epochMs = l.time,
            monoMs = l.elapsedRealtimeNanos / 1_000_000,
            lat = l.latitude, lon = l.longitude,
            altitudeM = if (l.hasAltitude()) l.altitude else null,
            speedMps = if (l.hasSpeed()) l.speed.toDouble() else null,
            courseDeg = if (l.hasBearing()) l.bearing.toDouble() else null,
            hAccM = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
            vAccM = if (Build.VERSION.SDK_INT >= 26 && l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toDouble() else null,
            speedAccMps = if (Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond.toDouble() else null,
            satellitesUsed = l.extras?.getInt("satellites", -1)?.takeIf { it >= 0 },
            simulated = false,
        )

        if (!hasPermission()) {
            trySend(SourceEvent.Availability(false, "Location permission not granted"))
        } else {
            if (playServicesAvailable()) {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                    .setMinUpdateIntervalMillis(intervalMs / 2)
                    .setWaitForAccurateLocation(false)
                    .build()
                val cb = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) { result.locations.forEach { trySend(SourceEvent.Fix(toFix(it))) } }
                }
                fusedCallback = cb
                runCatching { client.requestLocationUpdates(req, cb, Looper.getMainLooper()) }
                    .onFailure { trySend(SourceEvent.Availability(false, it.message)) }
            } else if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                val l = LocationListener { trySend(SourceEvent.Fix(toFix(it))) }
                platformListener = l
                runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, l, Looper.getMainLooper()) }
                    .onFailure { trySend(SourceEvent.Availability(false, it.message)) }
            } else {
                trySend(SourceEvent.Availability(false, "No GPS provider on this device"))
            }

            if (hasGnssHardware) {
                val cb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        val list = (0 until status.satelliteCount).map { i ->
                            SatelliteInfo(
                                svid = status.getSvid(i),
                                constellation = when (status.getConstellationType(i)) {
                                    GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
                                    GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
                                    GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
                                    GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
                                    GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
                                    GnssStatus.CONSTELLATION_SBAS -> Constellation.SBAS
                                    GnssStatus.CONSTELLATION_IRNSS -> Constellation.IRNSS
                                    else -> Constellation.UNKNOWN
                                },
                                azimuthDeg = status.getAzimuthDegrees(i),
                                elevationDeg = status.getElevationDegrees(i),
                                cn0DbHz = status.getCn0DbHz(i),
                                usedInFix = status.usedInFix(i),
                            )
                        }
                        trySend(SourceEvent.Satellites(GnssSnapshot(true, list, SystemClock.elapsedRealtime())))
                    }
                }
                gnssCallback = cb
                runCatching { lm.registerGnssStatusCallback(cb, handler) }
            } else {
                trySend(SourceEvent.Satellites(GnssSnapshot(false, emptyList(), SystemClock.elapsedRealtime())))
            }
        }

        val ticker = launch {
            var lastEnabled: Boolean? = null
            while (true) {
                val enabled = LocationManagerCompat.isLocationEnabled(lm)
                if (enabled != lastEnabled && hasPermission()) {
                    trySend(SourceEvent.Availability(enabled, if (enabled) null else "Location services are disabled"))
                    lastEnabled = enabled
                }
                trySend(SourceEvent.Tick(SystemClock.elapsedRealtime(), System.currentTimeMillis()))
                delay(1000)
            }
        }

        awaitClose {
            ticker.cancel()
            fusedCallback?.let { LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(it) }
            platformListener?.let { lm.removeUpdates(it) }
            gnssCallback?.let { lm.unregisterGnssStatusCallback(it) }
        }
    }
}
