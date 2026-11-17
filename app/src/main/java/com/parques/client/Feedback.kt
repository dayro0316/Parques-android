package com.parques.client

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibración y sonidos cortos para los eventos del juego. Todo está protegido: si algo falla, simplemente no suena. */
@Suppress("DEPRECATION")
class Feedback(context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        null
    }

    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 90)
    } catch (e: Exception) {
        null
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                v.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            // sin vibrador: se ignora
        }
    }

    private fun beep(type: Int, ms: Int) {
        try {
            tone?.startTone(type, ms)
        } catch (e: Exception) {
            // sin sonido: se ignora
        }
    }

    /** Es tu turno de lanzar. */
    fun myTurn() {
        vibrate(longArrayOf(0, 120, 80, 120))
        beep(ToneGenerator.TONE_PROP_PROMPT, 200)
    }

    /** Sacaste pares. */
    fun pair() {
        vibrate(longArrayOf(0, 60, 60, 60, 60, 60))
        beep(ToneGenerator.TONE_PROP_ACK, 250)
    }

    /** Tú comiste una ficha. */
    fun captured() {
        vibrate(longArrayOf(0, 250))
        beep(ToneGenerator.TONE_SUP_CONFIRM, 300)
    }

    /** Te comieron una ficha. */
    fun lost() {
        vibrate(longArrayOf(0, 500))
        beep(ToneGenerator.TONE_PROP_NACK, 400)
    }

    fun release() {
        try {
            tone?.release()
        } catch (e: Exception) {
            // nada
        }
    }
}
