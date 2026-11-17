package com.empatica.e4companion.processing

import com.empatica.e4companion.decoder.AccFilter
import com.empatica.e4companion.decoder.E4PacketFormat
import com.empatica.e4companion.decoder.TempFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

data class AccelData(
    val x: Float,
    val y: Float,
    val z: Float,
    val magnitude: Float = sqrt(x * x + y * y + z * z),
    val timestamp: Double = 0.0
)

class MultiSensorProcessor {

    // ── Shared filters ──
    private val accFilter = AccFilter()
    private val tempFilter = TempFilter()

    // ── EDA (4 Hz, μS) ──
    private val _gsrValue = MutableStateFlow(0f)
    val gsrValue: StateFlow<Float> = _gsrValue.asStateFlow()

    // ── Temperature (4 Hz, °C) ──
    private val _temperature = MutableStateFlow(0f)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    // ── Accelerometer (32 Hz) ──
    private val _acceleration = MutableStateFlow(AccelData(0f, 0f, 0f))
    val acceleration: StateFlow<AccelData> = _acceleration.asStateFlow()

    // ── Battery ──
    private val _batteryLevel = MutableStateFlow(-1f)
    val batteryLevel: StateFlow<Float> = _batteryLevel.asStateFlow()

    private var batteryEmaValue = -1f
    private var lastReportedBatteryPct = -1

    // ── Wrist status ──
    private val _wristStatus = MutableStateFlow(0)
    val wristStatus: StateFlow<Int> = _wristStatus.asStateFlow()

    // ── Tag ──
    private val _lastTagTimestamp = MutableStateFlow(0.0)
    val lastTagTimestamp: StateFlow<Double> = _lastTagTimestamp.asStateFlow()

    // EdaDecoder — SAME as offline pipeline for identical results
    //private val edaDecoder = EdaDecoder()

    // ── Cumulative ACC ──
    private val _accCumulativeValue = MutableStateFlow(-1.0)
    val accCumulativeValue: StateFlow<Double> = _accCumulativeValue.asStateFlow()

    private var accXSum = 0f
    private var accYSum = 0f
    private var accZSum = 0f
    private var accelerationCount = 0

    // Callbacks
    var onEdaSample: ((Double, Float) -> Unit)? = null
    var onTempSample: ((Double, Float) -> Unit)? = null
    var onAccSample: ((Double, Float, Float, Float) -> Unit)? = null
    var onTagEvent: ((Double) -> Unit)? = null

    var bvpProcessor: BVPProcessor? = null


    private var sessionStartTime = System.currentTimeMillis() / 1000.0

    // ── Wrist Detection State ──
    private val edaWristBuffer = ArrayDeque<Float>(20)
    private var consecutiveOffCount = 0
    private var consecutiveOnCount = 0
    private var internalWristState = -1
    private var currentWristStatus = 1

    // Add to class properties
    private var lastTempForWrist = 0f
    private var tempDropDetected = false
    private val tempWristBuffer = ArrayDeque<Float>(8)

    /**
     * Call this from processTemperaturePacket() after filtering to feed
     * temperature into wrist detection.
     */
    private fun updateTempForWristDetection(tempC: Float) {
        tempWristBuffer.addLast(tempC)
        if (tempWristBuffer.size > 8) tempWristBuffer.removeFirst()
        lastTempForWrist = tempC

        // Detect rapid temperature drop (> 1°C in 8 samples)
        if (tempWristBuffer.size >= 4) {
            val recent = tempWristBuffer.takeLast(4).average().toFloat()
            val older = tempWristBuffer.take(4).average().toFloat()
            tempDropDetected = (older - recent) > 1.0f
        }
    }

    /**
     * Wrist detection from EDA signal characteristics + temperature assist.
     *
     * From real E4 data:
     * - ON-WRIST:  stable EDA (variance < 0.0005, range < 0.02), temp > 30°C
     * - OFF-WRIST: erratic EDA jumps OR temp dropping rapidly OR temp < 28°C
     *
     * Temperature is key for detecting RE-WEARING:
     * EDA stays erratic for 30-60s after putting device back on,
     * but temperature recovers within seconds (28°C → 34°C).
     *
     * The native libempac.so uses only conductance >= 0.015 threshold [1],
     * but that can't detect removal when electrodes maintain partial contact.
     * We add variance + temperature for robust detection.
     */
    private fun updateWristFromEda(filteredEda: Float) {
        edaWristBuffer.addLast(filteredEda)
        if (edaWristBuffer.size > 20) edaWristBuffer.removeFirst()

        if (edaWristBuffer.size < 4) {
            currentWristStatus = 1
            _wristStatus.value = currentWristStatus
            return
        }

        val currentMean = edaWristBuffer.average().toFloat()
        val variance = edaWristBuffer.map { (it - currentMean).let { d -> d * d } }
            .average().toFloat()
        val range = (edaWristBuffer.maxOrNull() ?: 0f) - (edaWristBuffer.minOrNull() ?: 0f)
        val maxVal = edaWristBuffer.maxOrNull() ?: 0f

        // OFF-WRIST indicators (tuned from real removal data):
        // 1. Large range in EDA window (on-wrist changes < 0.02 µS/sample)
        // 2. High variance (on-wrist variance is ~0.000004)
        // 3. Impossibly high EDA value for on-wrist
        // 4. Temperature dropping rapidly (watch being removed)
        // 5. Temperature below skin threshold (< 28°C = not on skin)
        val isLikelyOff = (range > 0.15f) ||
                (variance > 0.005f) ||
                (maxVal > 2.0f) ||
                tempDropDetected ||
                (lastTempForWrist > 0f && lastTempForWrist < 28.0f)

        // ON-WRIST: stable EDA AND warm temperature
        // Temperature > 30°C is the KEY indicator for re-wearing detection
        // even when EDA is still settling after electrode re-contact
        val isLikelyOn = (variance < 0.001f) &&
                (range < 0.03f) &&
                (currentMean > 0.005f) &&
                (maxVal < 1.5f) &&
                (lastTempForWrist == 0f || lastTempForWrist > 30.0f)

        // ALTERNATIVE on-wrist: temperature recovered but EDA still settling
        // If temp > 33°C, device is definitely on skin regardless of EDA instability
        val tempConfirmsOnWrist = (lastTempForWrist > 33.0f) && !tempDropDetected

        if (currentWristStatus == -1) {
            currentWristStatus = if (isLikelyOff) 0 else 1
            _wristStatus.value = currentWristStatus
            return
        }

        // IMMEDIATE off-wrist detection (1 qualifying sample)
        if (isLikelyOff && !tempConfirmsOnWrist) {
            consecutiveOffCount++
            consecutiveOnCount = 0
            if (consecutiveOffCount >= 1 && currentWristStatus != 0) {
                currentWristStatus = 0
            }
        } else if (isLikelyOn || tempConfirmsOnWrist) {
            consecutiveOnCount++
            consecutiveOffCount = 0
            // If temperature confirms on-wrist, require fewer stable samples
            val requiredOnCount = if (tempConfirmsOnWrist) 4 else 12
            if (consecutiveOnCount >= requiredOnCount && currentWristStatus == 0) {
                currentWristStatus = 1
            }
        } else {
            // Ambiguous — don't change state, don't reset counters
            consecutiveOnCount = 0
            consecutiveOffCount = 0
        }

        _wristStatus.value = currentWristStatus
    }

    fun setSessionStartTime(time: Double) {
        sessionStartTime = time
    }

    /** _wristStatus.value = currentWristStatus
     * Process GSR/EDA packet — real-time BLE pipeline.
     *
     * VERIFIED identical path to offline:
     * 1. classifyEdaPacket() determines Type A vs Type B
     * 2. Type B floats → EdaDecoder → calibrator.floatToConductance()
     * 3. Type A ADC → SKIPPED
     * 4. No additional filtering applied
     * 5. Wrist detection updated from conductance value
     */
    private val edaDecoder = EdaDecoder().apply {
        onEdaSampleWithFiltered = { ts, raw, filtered ->
            _gsrValue.value = raw
            onEdaSample?.invoke(ts, raw)
            updateWristFromEda(filtered)
        }
    }
    fun processGsrPacket(data: ByteArray) {
        if (data.size < E4PacketFormat.PACKET_SIZE) return
        val timestamp = System.currentTimeMillis() / 1000.0
        edaDecoder.processPacket(data, timestamp)
    }

    /**
     * Process temperature packet.
     */
    /**
     * Process temperature packet.
     */
    fun processTemperaturePacket(data: ByteArray) {
        if (data.size < E4PacketFormat.PACKET_SIZE) return
        val timestamp = System.currentTimeMillis() / 1000.0

        for (i in 0 until E4PacketFormat.TEMP_SAMPLES_PER_PACKET) {
            val offset = i * 2
            if (offset + 1 >= E4PacketFormat.TEMP_DATA_BYTES) break

            val tempC = E4PacketFormat.decodeTempSample(data, offset) ?: continue
            val sampleTs = timestamp + (i * E4PacketFormat.TEMP_SAMPLE_PERIOD)
            val filtered = tempFilter.filter(tempC)

            // Feed RAW temp into wrist detection (not filtered)
            // Raw is immediately accurate (33.95°C from first sample)
            // Filtered starts at 0.19°C due to Butterworth warmup lag
            updateTempForWristDetection(tempC)

            _temperature.value = filtered
            onTempSample?.invoke(sampleTs, filtered)
        }
    }

    companion object {
        const val BATTERY_EMA_ALPHA = 0.1f
    }

    /**
     * Process accelerometer packet.
     */
    fun processAccPacket(data: ByteArray) {
        if (data.size < E4PacketFormat.PACKET_SIZE) return
        val timestamp = System.currentTimeMillis() / 1000.0

        for (i in 0 until E4PacketFormat.ACC_SAMPLES_PER_PACKET) {
            val offset = i * 3
            if (offset + 2 >= E4PacketFormat.ACC_DATA_BYTES) break

            val (xG, yG, zG) = E4PacketFormat.decodeAccTriplet(bytes = data, offset = offset)
            val (fX, fY, fZ) = accFilter.filter(xG, yG, zG)


            val mag = sqrt(fX * fX + fY * fY + fZ * fZ)
            bvpProcessor?.setAccMagnitude(mag)
            val sampleTs = timestamp + (i * E4PacketFormat.ACC_SAMPLE_PERIOD)

            val sample = AccelData(fX, fY, fZ, mag, sampleTs)
            _acceleration.value = sample
            onAccSample?.invoke(sampleTs, fX, fY, fZ)

            // Cumulative ACC (from decompiled EmpaLinkManager)
            val rawX = data[offset].toInt()
            val rawY = data[offset + 1].toInt()
            val rawZ = data[offset + 2].toInt()
            accXSum += abs(rawX).toFloat()
            accYSum += abs(rawY).toFloat()
            accZSum += abs(rawZ).toFloat()
            accelerationCount++

            if (accelerationCount % 16 == 0) {
                val totalSum = (accXSum + accYSum + accZSum).toDouble()
                _accCumulativeValue.value = maxOf(
                    1.0,
                    (0.015748031 * ((totalSum / 16.0) / 1.5)) - 44.8
                )
                accXSum = 0f
                accYSum = 0f
                accZSum = 0f
            }

        }
    }

    fun setDeviceHardwareId(hardwareId: String) {
        edaDecoder.setDeviceId(hardwareId)
    }

    fun processBatteryPacket(data: ByteArray) {
        if (data.size < 2) return
        val low = data[0].toInt() and 0xFF
        val high = data[1].toInt() and 0xFF
        val raw = (high shl 8) or low
        val level = when {
            raw in 0..100 -> raw.toFloat() / 100f
            raw in 101..10000 -> raw.toFloat() / 10000f
            else -> raw.toFloat() / 65535f
        }.coerceIn(0f, 1f)

        batteryEmaValue = if (batteryEmaValue < 0f) {
            level
        } else {
            (BATTERY_EMA_ALPHA * level) + ((1f - BATTERY_EMA_ALPHA) * batteryEmaValue)
        }

        val currentPct = (batteryEmaValue * 100).toInt().coerceIn(0, 100)
        if (currentPct != lastReportedBatteryPct) {
            lastReportedBatteryPct = currentPct
            _batteryLevel.value = batteryEmaValue
        }
    }

    fun processControlPacket(data: ByteArray) {
        val timestamp = System.currentTimeMillis() / 1000.0
        _lastTagTimestamp.value = timestamp
        onTagEvent?.invoke(timestamp)
        if (data.isNotEmpty()) {
            val status = data[0].toInt() and 0x03
            if (status in 0..2) _wristStatus.value = status
        }
    }

    fun reset() {
        _gsrValue.value = 0f
        _temperature.value = 0f
        _acceleration.value = AccelData(0f, 0f, 0f)
        _batteryLevel.value = -1f
        edaWristBuffer.clear()
        consecutiveOffCount = 0
        consecutiveOnCount = 0
        internalWristState = -1
        _accCumulativeValue.value = -1.0
        accXSum = 0f; accYSum = 0f; accZSum = 0f
        accelerationCount = 0
        sessionStartTime = System.currentTimeMillis() / 1000.0
        accFilter.reset()
        tempFilter.reset()
        batteryEmaValue = -1f
        lastReportedBatteryPct = -1
    }
}