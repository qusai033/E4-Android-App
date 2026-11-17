package com.empatica.e4companion.processing

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session timer that tracks elapsed streaming time.
 * Updates every second for display on the dashboard.
 */
class SessionTimer(private val scope: CoroutineScope) {

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var timerJob: Job? = null
    private var startTimeMs: Long = 0

    fun start() {
        startTimeMs = System.currentTimeMillis()
        _isRunning.value = true
        _elapsedSeconds.value = 0

        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                _elapsedSeconds.value = (System.currentTimeMillis() - startTimeMs) / 1000
                delay(1000)
            }
        }
    }

    fun stop() {
        timerJob?.cancel()
        _isRunning.value = false
    }

    fun reset() {
        stop()
        _elapsedSeconds.value = 0
        startTimeMs = 0
    }

    /**
     * Format elapsed seconds as HH:MM:SS
     */
    companion object {
        fun formatTime(totalSeconds: Long): String {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
    }
}