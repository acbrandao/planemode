package com.planemode.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.planemode.MainActivity
import com.planemode.R
import com.planemode.data.SettingsRepository
import com.planemode.ui.Fmt
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Foreground service (type=location) that keeps the tracking engine alive with the screen off. */
@AndroidEntryPoint
class TrackingService : LifecycleService() {
    @Inject lateinit var tracker: FlightTracker
    @Inject lateinit var settings: SettingsRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    companion object {
        private const val CHANNEL = "flight_tracking"
        private const val NOTIF_ID = 42
        private const val ACTION_END = "com.planemode.END_FLIGHT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_END) { tracker.endFlight(); return START_NOT_STICKY }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, build(tracker.state.value), type)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!started) {
            started = true
            tracker.acquire("service")
            lifecycleScope.launch {
                while (true) {
                    val st = tracker.state.value
                    if (!st.active && !st.monitoring) { stopSelf(); break }
                    updateWakeLock(st.active)
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(st))
                    delay(5_000)
                }
            }
        }
        return START_STICKY
    }

    private fun updateWakeLock(active: Boolean) {
        if (active && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "planeMode:tracking").apply { acquire(12 * 3600 * 1000L) }
        } else if (!active) { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    }

    override fun onDestroy() {
        tracker.release("service")
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Flight tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing flight-tracking status"; setShowBadge(false)
            })
        }
    }

    private fun build(st: FlightState): Notification {
        val s = settings.current
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when {
            st.active -> (if (st.simulated) "Simulated flight · " else "Flight in progress · ") + Fmt.duration(st.elapsedMs)
            else -> "Auto flight tracking armed"
        }
        val text = when {
            st.active -> "${Fmt.speed(st.groundSpeedMps, s.speedUnit)} GS · ${Fmt.altitude(st.altitudeM, s.altitudeUnit)} · ${st.gpsState.label}"
            else -> "Waiting for sustained takeoff speed (${s.autoStartKts} kt)"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_plane)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (st.active) addAction(0, "End flight", end) }
            .addAction(0, "Open planeMode", open)
            .build()
    }
}
