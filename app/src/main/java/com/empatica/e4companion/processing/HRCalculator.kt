package com.empatica.e4companion.processing

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * HR calculation with sliding window smoothing.
 *
 * Instead of showing instantaneous HR which jumps around,
 * we average the last N beats for a stable display.
 *
 * From decompiled EmpaLinkManager.setLastIbi():
 *   float f2 = 60.0f / f;
 *   f2 = (f2 * 0.2f) + (0.8f * averageHR);  // EMA
 *
 * We improve on this with a sliding window of the last 8 beats
 * which provides better stability for clinical display.
 */
class HRCalculator(private val scope: CoroutineScope) {

    companion object {
        const val HR_RESET_TIMEOUT_MS = 15_000L
        const val INVALID_HR = -1f
        const val WINDOW_SIZE = 8  // Average last 8 beats
    }

    private val _heartRate = MutableStateFlow(INVALID_HR)
    val heartRate: StateFlow<Float> = _heartRate.asStateFlow()

    private val _lastIbi = MutableStateFlow(0f)
    val lastIbi: StateFlow<Float> = _lastIbi.asStateFlow()

    // Sliding window of recent HR values
    private val hrWindow = ArrayDeque<Float>(WINDOW_SIZE)
    private var resetJob: Job? = null

    /**
     * Called when an IBI value is received.
     * Converts to BPM and averages over the last WINDOW_SIZE beats
     * for a stable, non-jumping display.
     */
    fun onIbiReceived(ibiSeconds: Float) {
        if (ibiSeconds <= 0f) return
        _lastIbi.value = ibiSeconds

        val bpm = 60f / ibiSeconds

        // Sanity check
        if (bpm !in 30f..220f) return

        // Add to sliding window
        synchronized(hrWindow) {
            if (hrWindow.size >= WINDOW_SIZE) hrWindow.removeFirst()
            hrWindow.addLast(bpm)

            // Calculate windowed average
            val avgBpm = hrWindow.average().toFloat()
            _heartRate.value = avgBpm
        }

        // Reset HR if no IBI for 15s
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(HR_RESET_TIMEOUT_MS)
            resetHR()
        }
    }

    private fun resetHR() {
        synchronized(hrWindow) { hrWindow.clear() }
        _heartRate.value = INVALID_HR
    }

    fun reset() {
        synchronized(hrWindow) { hrWindow.clear() }
        _heartRate.value = INVALID_HR
        _lastIbi.value = 0f
        resetJob?.cancel()
    }
}