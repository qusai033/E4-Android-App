package com.empatica.e4companion.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback helper for E4 events.
 *
 * Uses the Vibrator service to provide tactile feedback [4].
 * Requires VIBRATE permission in the manifest [4].
 *
 * Events:
 *   - Tag marked: short double-tap
 *   - Connected: gentle confirmation pulse
 *   - Disconnected: longer warning pulse
 *   - Error: strong rejection pulse
 *   - Battery low: short alert
 */
object HapticHelper {

    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= 31) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Tag button pressed — short double-tap [4]
     */
    fun tagMarked(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= 26) {
            // Two quick taps: 50ms vibrate, 100ms pause, 50ms vibrate
            val timings = longArrayOf(0, 50, 100, 50)
            val amplitudes = intArrayOf(0, 150, 0, 150)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    /**
     * Device connected — gentle confirmation [4]
     */
    fun connected(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= 29) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    /**
     * Device disconnected unexpectedly — warning pulse
     */
    fun disconnected(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= 26) {
            val timings = longArrayOf(0, 200, 150, 200)
            val amplitudes = intArrayOf(0, 200, 0, 200)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(400)
        }
    }

    /**
     * Error occurred — strong rejection pulse [4]
     */
    fun error(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= 29) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        } else if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, 255))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(300)
        }
    }

    /**
     * Battery low alert — short buzz
     */
    fun batteryLow(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, 100))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }
}