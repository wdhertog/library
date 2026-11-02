package nl.wdhertog.library.ui.scan

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

enum class ScanSignal { ADDED, ALREADY_PRESENT, NOT_AN_ISBN }

/** Geluid en trilling bij het scannen, zodat je niet naar het scherm hoeft te kijken. */
class ScanFeedback(context: Context, private val sound: () -> Boolean = { true }, private val vibrate: () -> Boolean = { true }) {

    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun play(signal: ScanSignal) {
        when (signal) {
            ScanSignal.ADDED -> {
                if (sound()) tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                vibrate(longArrayOf(0, 40))
            }
            ScanSignal.ALREADY_PRESENT -> {
                if (sound()) tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
                vibrate(longArrayOf(0, 60, 80, 60))
            }
            ScanSignal.NOT_AN_ISBN -> vibrate(longArrayOf(0, 20))
        }
    }

    private fun vibrate(pattern: LongArray) {
        if (vibrate()) vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun release() {
        tones?.release()
    }
}
