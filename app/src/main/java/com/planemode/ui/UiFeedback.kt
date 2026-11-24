package com.planemode.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.staticCompositionLocalOf
import com.planemode.R
import com.planemode.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Subtle click sound + haptic tick for buttons and page changes (each can be disabled in Settings). */
@Singleton
class UiFeedback @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
) {
    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val clickId = pool.load(context, R.raw.snd_click, 1)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    private var lastClick = 0L

    fun click() {
        val now = System.currentTimeMillis()
        if (now - lastClick < 40) return // avoid double feedback for nested handlers
        lastClick = now
        val s = settings.current
        if (s.clickSound) {
            val v = (0.35f * s.soundVolume).coerceIn(0f, 1f)
            pool.play(clickId, v, v, 0, 0, 1f)
        }
        if (s.clickHaptics) vibrate()
    }

    private fun vibrate() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        runCatching {
            // A short explicit pulse. Predefined TICK effects are classed as "touch" vibrations, which some
            // devices (e.g. Samsung) scale to zero for apps, so the user would feel nothing.
            val effect = VibrationEffect.createOneShot(30, if (vib.hasAmplitudeControl()) 200 else VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= 33) {
                vib.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_MEDIA))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            }
        }
    }
}

/** Click feedback available to any composable. */
val LocalClick = staticCompositionLocalOf<() -> Unit> { {} }
