package com.planemode.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.planemode.R
import com.planemode.data.SettingsRepository
import com.planemode.model.FlightEventType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Plays planeMode's original synthesized chimes for flight events. */
@Singleton
class SoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    companion object {
        fun resFor(type: FlightEventType) = when (type) {
            FlightEventType.GPS_ACQUIRED -> R.raw.snd_gps_acquired
            FlightEventType.GPS_LOST -> R.raw.snd_gps_lost
            FlightEventType.TAKEOFF_SPEED -> R.raw.snd_takeoff_speed
            FlightEventType.TEN_THOUSAND -> R.raw.snd_ten_thousand
            FlightEventType.CRUISE -> R.raw.snd_cruise
            FlightEventType.MAX_CRUISE_SPEED -> R.raw.snd_max_speed
            FlightEventType.TOP_OF_DESCENT -> R.raw.snd_top_of_descent
            FlightEventType.LANDING -> R.raw.snd_landing
            FlightEventType.FLIGHT_COMPLETE -> R.raw.snd_flight_complete
        }
    }

    private val pool: SoundPool by lazy {
        SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build().also { p ->
            p.setOnLoadCompleteListener { pool, id, status ->
                synchronized(this) { if (status == 0) { loaded.add(id); if (pending.remove(id)) play(pool, id) } }
            }
        }
    }
    private val ids = HashMap<FlightEventType, Int>()
    private val loaded = HashSet<Int>()
    private val pending = HashSet<Int>()

    private fun play(p: SoundPool, id: Int) {
        val v = settings.current.soundVolume.coerceIn(0f, 1f)
        p.play(id, v, v, 1, 0, 1f)
    }

    /** Plays the chime for [type] if enabled in settings. */
    fun onEvent(type: FlightEventType) {
        val s = settings.current
        if (!s.soundEnabled || type.name in s.disabledSounds) return
        preview(type)
    }

    /** Plays regardless of per-event toggles (used by settings preview). */
    @Synchronized
    fun preview(type: FlightEventType) {
        val id = ids.getOrPut(type) { pool.load(context, resFor(type), 1) }
        if (id in loaded) play(pool, id) else pending.add(id)
    }
}
