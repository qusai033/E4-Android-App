package com.empatica.e4companion.processing

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AcquisitionStatus { Idle, Calibrating, Acquiring }

class CalibrationManager(private val scope: CoroutineScope) {

    companion object {
        const val DEFAULT_CALIBRATING_SAMPLES = 400
        const val MIN_CALIBRATING_SAMPLES = 0     // 0 = skip calibration
        const val MAX_CALIBRATING_SAMPLES = 1000
        const val CALIBRATION_TIMEOUT_MS = 15_000L
        const val STREAMING_DELAY_TIMEOUT_MS = 5_000L
    }

    // Configurable threshold — can be changed by the user
    private var _calibratingSamples = MutableStateFlow(DEFAULT_CALIBRATING_SAMPLES)
    val calibratingSamples: StateFlow<Int> = _calibratingSamples.asStateFlow()

    private val _status = MutableStateFlow(AcquisitionStatus.Idle)
    val status: StateFlow<AcquisitionStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private var packetCount = 0
    private var calibrationTimeoutJob: Job? = null
    private var streamingDelayJob: Job? = null

    val isCalibrating: Boolean
        get() = _calibratingSamples.value > 0 && packetCount < _calibratingSamples.value

    var onCalibrationFailed: (() -> Unit)? = null

    /**
     * Set the calibration threshold.
     * Set to 0 to skip calibration entirely.
     */
    fun setCalibrationSamples(samples: Int) {
        _calibratingSamples.value = samples.coerceIn(MIN_CALIBRATING_SAMPLES, MAX_CALIBRATING_SAMPLES)
    }

    fun startCalibration() {
        reset()

        val threshold = _calibratingSamples.value
        if (threshold == 0) {
            // Skip calibration entirely
            _status.value = AcquisitionStatus.Acquiring
            _progress.value = 1f
            return
        }

        calibrationTimeoutJob = scope.launch {
            delay(CALIBRATION_TIMEOUT_MS)
            if (packetCount < threshold) {
                onCalibrationFailed?.invoke()
            }
        }
    }

    fun onBvpPacket() {
        val threshold = _calibratingSamples.value
        if (threshold == 0 || !isCalibrating) return

        packetCount++
        _progress.value = packetCount.toFloat() / threshold

        streamingDelayJob?.cancel()
        streamingDelayJob = scope.launch {
            delay(STREAMING_DELAY_TIMEOUT_MS)
            if (isCalibrating) {
                onCalibrationFailed?.invoke()
            }
        }

        when (packetCount) {
            1 -> {
                _status.value = AcquisitionStatus.Calibrating
                calibrationTimeoutJob?.cancel()
            }
            threshold -> {
                _status.value = AcquisitionStatus.Acquiring
                streamingDelayJob?.cancel()
            }
        }
    }

    fun reset() {
        packetCount = 0
        _status.value = AcquisitionStatus.Idle
        _progress.value = 0f
        calibrationTimeoutJob?.cancel()
        streamingDelayJob?.cancel()
    }
}