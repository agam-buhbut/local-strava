package com.localstrava.tracking

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Rings once when the run goal is reached (spec §4.2): the default alarm tone for [RING_MS] plus a
 * short buzz, both with ALARM usage so they're heard/felt in a pocket on silent or mute. Nothing is
 * prepared until [ring] — the service creates this in onCreate (context attached), main thread only.
 */
class GoalBell(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null

    fun ring() {
        release()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = ALARM_AUDIO
            play()
        }
        handler.postDelayed(::release, RING_MS)
        vibrate()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        ringtone = null
    }

    private fun vibrate() {
        val effect = VibrationEffect.createWaveform(PATTERN, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION") // AudioAttributes overload is the only alarm-usage path below API 33
            vibrator().vibrate(effect, ALARM_AUDIO)
        }
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") // VibratorManager is API 31+
            context.getSystemService(Vibrator::class.java)
        }

    private companion object {
        const val RING_MS = 4_000L
        val PATTERN = longArrayOf(0, 600, 300, 600, 300, 600)
        val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
