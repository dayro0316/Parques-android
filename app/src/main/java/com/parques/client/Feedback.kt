package com.parques.client

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    /** Silencia los sonidos (la vibración sigue). */
    var soundOn = true

    private val handler = Handler(Looper.getMainLooper())

    private fun beep(type: Int, ms: Int) {
        if (!soundOn) return
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

    /** Tú comiste una ficha (el sonido lo da `sfxCapture`, que suena para todos). */
    fun captured() {
        vibrate(longArrayOf(0, 250))
    }

    /** Te comieron una ficha. */
    fun lost() {
        vibrate(longArrayOf(0, 500))
    }

    // ---- efectos de sonido del tablero (suenan para todos los jugadores) ----
    private fun seq(vararg notes: Pair<Int, Int>) {
        var t = 0L
        for ((type, ms) in notes) {
            handler.postDelayed({ beep(type, ms) }, t)
            t += ms + 40L
        }
    }

    /** Dados lanzados: tres golpecitos rápidos. */
    fun sfxRoll() = seq(
        ToneGenerator.TONE_DTMF_3 to 40, ToneGenerator.TONE_DTMF_7 to 40, ToneGenerator.TONE_DTMF_5 to 40
    )

    /** Una ficha avanza. */
    fun sfxMove() = beep(ToneGenerator.TONE_DTMF_D, 70)

    /** Una ficha regresa a la cárcel. */
    fun sfxCapture() = seq(ToneGenerator.TONE_SUP_ERROR to 120, ToneGenerator.TONE_PROP_NACK to 200)

    /** Una ficha llega a la meta. */
    fun sfxGoal() = seq(ToneGenerator.TONE_PROP_ACK to 120, ToneGenerator.TONE_PROP_PROMPT to 160)

    /** Ganaste la partida. */
    fun sfxWin() = seq(
        ToneGenerator.TONE_PROP_ACK to 150, ToneGenerator.TONE_PROP_PROMPT to 150,
        ToneGenerator.TONE_SUP_CONFIRM to 300
    )

    fun release() {
        handler.removeCallbacksAndMessages(null)
        try {
            tone?.release()
        } catch (e: Exception) {
            // nada
        }
    }
}
