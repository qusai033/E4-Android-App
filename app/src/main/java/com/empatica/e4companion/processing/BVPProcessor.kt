package com.empatica.e4companion.processing

import com.empatica.e4companion.decoder.E4PacketFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.me.berndporr.iirj.Butterworth

/**
 * E4 BVP Decoder using the correct 7-bit delta encoding discovered via
 * Ghidra reverse-engineering of libempac.so [9].
 *
 * Native pipeline (DeviceManager_computeBvpSampleWithNewAlgorithmFromHolyMatlabwith):
 *   1. FirFilter_filter(red_channel)           → filtered red
 *   2. FirFilter_filter((red + green*10)/11)   → filtered combined
 *   3. KalmanFilter_evaluate(filtered_red, filtered_combined) → kalman output
 *   4. FirFilter_filterAndSum(kalman, -kalman)  → final BVP (bandpass effect)
 *   5. return -fVar6 (negation)
 *
 * Without exact FIR coefficients, we approximate with:
 *   - 2nd-order Butterworth highpass at 0.5 Hz (removes DC drift from accumulation)
 *   - 2nd-order Butterworth lowpass at 5.0 Hz (approximates 31-tap FIR smoothing)
 *   - Negate output
 */

data class BvpSample(
    val timestamp: Double,
    val rawBvp: Float,
    val filteredBvp: Float,
    val heartRate: Float
)

class BVPProcessor {

    companion object {
        const val SAMPLE_RATE = 64.0
        const val SAMPLES_PER_PACKET = 11
        const val WARMUP_SAMPLES = 192  // 3 seconds at 64 Hz
        val WINDOW_SIZE = (SAMPLE_RATE * 8).toInt()
        const val MIN_PEAK_DISTANCE = 22
        const val PROMINENCE_THRESHOLD = 0.20f
        const val CHART_BUFFER_SIZE = 512
    }

    // Delta accumulator state
    private var greenAccum: Int = 0
    private var redAccum: Int = 0
    private var lastSeq: Int = -1
    private var droppedPackets: Int = 0
    private var totalSamples: Long = 0
    private var packetCount: Long = 0

    // Highpass filter at 0.5 Hz to remove DC baseline drift [9]
    // Approximates the native FirFilter_filterAndSum which adds signal to its
    // negated delayed copy, creating a highpass effect
    private val highpassFilter = Butterworth().apply {
        highPass(2, SAMPLE_RATE, 0.5)
    }

    // Lowpass filter at 5.0 Hz (approximates native 31-tap FIR smoothing) [9]
    // Native uses FirFilter with normalized_freq=0.625 (0x3f200000) and 10-sample decimation
    private val lowpassFilter = Butterworth().apply {
        lowPass(2, SAMPLE_RATE, 5.0)
    }

    private val filteredWindow = FloatArray(WINDOW_SIZE)
    private var windowPos = 0
    private var windowFilled = false

    private val _latestSample = MutableStateFlow(BvpSample(0.0, 0f, 0f, 0f))
    val latestSample: StateFlow<BvpSample> = _latestSample.asStateFlow()

    private val _heartRate = MutableStateFlow(0f)
    val heartRate: StateFlow<Float> = _heartRate.asStateFlow()

    private val _chartData = MutableStateFlow(FloatArray(0))
    val chartData: StateFlow<FloatArray> = _chartData.asStateFlow()

    private val chartBuffer = ArrayDeque<Float>(CHART_BUFFER_SIZE)
    private val hrSmoothing = ArrayDeque<Float>(12)
    private var recentAccMagnitude = 0f
    fun setAccMagnitude(mag: Float) {
        recentAccMagnitude = mag
    }

    var onSampleDecoded: ((BvpSample) -> Unit)? = null
    val totalPackets: Long get() = packetCount

    /**
     * Process a raw 20-byte BVP BLE packet using 7-bit delta decoding [9].
     */
    fun processPacket(rawPacket: ByteArray) {
        if (rawPacket.size < 20) return
        packetCount++

        // Skip calibration packets [1]
        if (rawPacket[0].toInt() and 0xFF == 0x7E &&
            rawPacket[1].toInt() and 0xFF == 0xFD &&
            (rawPacket[2].toInt() and 0xFF == 0xFB || rawPacket[2].toInt() and 0xFF == 0xFA)
        ) {
            return
        }

        // Extract sequence counter (byte 19 & 0x3F)
        val seq = rawPacket[19].toInt() and 0x3F

        // Detect dropped packets via sequence counter
        if (lastSeq >= 0) {
            val expected = (lastSeq + 1) and 0x3F
            if (seq != expected) {
                val gap = ((seq - lastSeq + 64) % 64) - 1
                if (gap in 1..31) {
                    droppedPackets += gap
                }
            }
        }
        lastSeq = seq

        // 7-bit delta unpacking (from PacketHandler_decodeSampleFromCharacteristic7) [9]
        val unpacked = decode7BitPacked(rawPacket)

        val baseTimestamp = System.currentTimeMillis() / 1000.0

        // Process 11 sample pairs (green_delta, red_delta)
        for (i in 0 until SAMPLES_PER_PACKET) {
            val idx = i * 2
            if (idx + 1 >= unpacked.size) break

            // Sign-extend from 7-bit to signed int
            var greenDelta = unpacked[idx]
            var redDelta = unpacked[idx + 1]
            if (greenDelta > 127) greenDelta -= 256
            if (redDelta > 127) redDelta -= 256

            // Accumulate running sums
            greenAccum += greenDelta
            redAccum += redDelta

            totalSamples++

            // Skip warmup period (accumulators stabilize)
            if (totalSamples < WARMUP_SAMPLES) continue

            // Combined signal matches native: (red + green * 10) / 11 [9]
            val combined = (redAccum.toFloat() + greenAccum.toFloat() * 10f) / 11f

            // Highpass removes DC baseline drift (approximates FirFilter_filterAndSum)
            val acCoupled = highpassFilter.filter(combined.toDouble()).toFloat()

            // Lowpass removes HF noise (approximates 31-tap FIR)
            val filtered = lowpassFilter.filter(acCoupled.toDouble()).toFloat()

            // Negate per libempac.so: return -fVar6 [9]
            val bvpFinal = -filtered

            // Store for HR calculation
            filteredWindow[windowPos] = bvpFinal
            windowPos = (windowPos + 1) % WINDOW_SIZE
            if (windowPos == 0) windowFilled = true

            // Chart buffer
            synchronized(chartBuffer) {
                if (chartBuffer.size >= CHART_BUFFER_SIZE) chartBuffer.removeFirst()
                chartBuffer.addLast(bvpFinal)
            }

            val sampleTs = baseTimestamp + (i / SAMPLE_RATE)
            val hr = if (totalSamples % 64 == 0L) calculateHeartRate() else _heartRate.value

            val sample = BvpSample(sampleTs, combined, bvpFinal, hr)
            _latestSample.value = sample
            _heartRate.value = hr
            onSampleDecoded?.invoke(sample)
        }

        synchronized(chartBuffer) {
            _chartData.value = chartBuffer.toFloatArray()
        }
    }

    /**
     * 7-bit delta unpacking algorithm [9].
     * Exact replication of PacketHandler_decodeSampleFromCharacteristic7.
     */
    private fun decode7BitPacked(rawBytes: ByteArray): IntArray {
        val output = IntArray(24)
        var carry = 0
        var outIdx = 0

        for (byteIdx in 0 until 20) {
            val rawByte = rawBytes[byteIdx].toInt() and 0xFF
            val remainder = byteIdx % 7
            val shiftAmount = remainder + 1

            var value = (rawByte ushr shiftAmount) or carry

            if (value and 0x40 != 0) {
                value = value or 0x80
            }
            output[outIdx] = value and 0xFF
            outIdx++

            val mask = (1 shl shiftAmount) - 1
            carry = (rawByte and mask) shl (6 - remainder)

            if (shiftAmount == 7) {
                var extraValue = carry
                if (extraValue and 0x40 != 0) {
                    extraValue = extraValue or 0x80
                }
                output[outIdx] = extraValue and 0xFF
                outIdx++
                carry = 0
            }
        }

        return output.copyOf(outIdx)
    }

    /**
     * Calculate heart rate using peak detection with robust outlier rejection.
     */
    private fun calculateHeartRate(): Float {
        val size = if (windowFilled) WINDOW_SIZE else windowPos
        if (size < MIN_PEAK_DISTANCE * 4) return _heartRate.value

        val data = FloatArray(size) { idx ->
            filteredWindow[(windowPos - size + idx + WINDOW_SIZE) % WINDOW_SIZE]
        }

        val min = data.min()
        val max = data.max()
        val range = max - min
        if (range < 0.01f) return _heartRate.value

        if (recentAccMagnitude > 1.2f) {  // >1.2g = significant motion
            return _heartRate.value  // Hold previous HR
        }

        val norm = FloatArray(size) { (data[it] - min) / range }

        val peaks = mutableListOf<Int>()
        for (idx in 2 until size - 2) {
            if (norm[idx] > norm[idx - 1] && norm[idx] > norm[idx + 1] &&
                norm[idx] > norm[idx - 2] && norm[idx] > norm[idx + 2] &&
                norm[idx] > 0.45f
            ) {
                val searchLeft = maxOf(0, idx - MIN_PEAK_DISTANCE)
                val searchRight = minOf(size - 1, idx + MIN_PEAK_DISTANCE)
                val leftMin = (searchLeft until idx).minOfOrNull { norm[it] } ?: norm[idx]
                val rightMin = ((idx + 1)..searchRight).minOfOrNull { norm[it] } ?: norm[idx]
                val prominence = norm[idx] - maxOf(leftMin, rightMin)

                if (prominence >= PROMINENCE_THRESHOLD) {
                    if (peaks.isEmpty() || (idx - peaks.last()) >= MIN_PEAK_DISTANCE) {
                        peaks.add(idx)
                    }
                }
            }
        }

        if (peaks.size < 3) return _heartRate.value

        val intervals = mutableListOf<Float>()
        for (idx in 1 until peaks.size) {
            intervals.add((peaks[idx] - peaks[idx - 1]).toFloat())
        }

        if (intervals.size < 2) return _heartRate.value

        val sortedIntervals = intervals.sorted()
        val medianInterval = sortedIntervals[sortedIntervals.size / 2]

        val validIntervals = intervals.filter { interval ->
            val deviation = kotlin.math.abs(interval - medianInterval) / medianInterval
            deviation < 0.30f
        }

        if (validIntervals.isEmpty()) return _heartRate.value

        val avgInterval = validIntervals.average().toFloat()
        val bpm = (60f * SAMPLE_RATE.toFloat()) / avgInterval

        if (bpm !in 40f..180f) return _heartRate.value

        synchronized(hrSmoothing) {
            if (hrSmoothing.size >= 12) hrSmoothing.removeFirst()
            hrSmoothing.addLast(bpm)
            val sorted = hrSmoothing.sorted()
            return sorted[sorted.size / 2].toFloat()
        }
    }

    fun reset() {
        greenAccum = 0
        redAccum = 0
        lastSeq = -1
        droppedPackets = 0
        totalSamples = 0
        packetCount = 0
        windowPos = 0
        windowFilled = false
        filteredWindow.fill(0f)
        chartBuffer.clear()
        _heartRate.value = 0f
        _latestSample.value = BvpSample(0.0, 0f, 0f, 0f)
        _chartData.value = FloatArray(0)
        highpassFilter.reset()
        lowpassFilter.reset()
        synchronized(hrSmoothing) { hrSmoothing.clear() }
    }
}