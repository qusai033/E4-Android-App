package com.empatica.e4companion.decoder

import com.empatica.e4companion.processing.EdaDecoder
import com.empatica.e4companion.processing.BVPProcessor
import android.util.Log
import java.io.OutputStream
import java.util.Locale

data class DecodedSession(
    val accSamples: Int = 0,
    val bvpSamples: Int = 0,
    val edaSamples: Int = 0,
    val tempSamples: Int = 0,
    val ibiSamples: Int = 0,
    val hrSamples: Int = 0,
    val droppedPackets: Int = 0,
    val largeGaps: Int = 0
)

class RawPacketDecoder {

    companion object {
        private const val TAG = "RawPacketDecoder"
    }

    private var lastAccSeq = -1
    private var lastBvpSeq = -1
    private var lastAccTimestamp = 0.0
    private var lastBvpTimestamp = 0.0
    private var droppedPackets = 0
    private var largeGaps = 0

    private val bvpFilter = BvpFilter()
    private val bvpProcessor = BVPProcessor()
    private val accFilter = AccFilter()
    private val tempFilter = TempFilter()

    // SAME EdaDecoder as real-time — ensures identical calibration
    private val edaDecoder = EdaDecoder()

    private var bvpSampleCounter = 0

    // ── Wrist Detection State ──
    private val edaWristBuffer = ArrayDeque<Float>(20)
    private var consecutiveOffCount = 0
    private var consecutiveOnCount = 0
    private var currentWristStatus = 1

    // Temperature-assisted wrist detection
    private var lastRawTempValue = 0f
    private var tempDropDetected = false
    private val tempBuffer = ArrayDeque<Float>(8)

    private var lastPeakTs = -1.0
    private var prevBvpSample = 0f
    private var prevPrevBvpSample = 0f

    /**
     * Set hardware ID for device-specific EDA calibration offset.
     */
    fun setDeviceHardwareId(hardwareId: String) {
        edaDecoder.setDeviceId(hardwareId)
    }

    fun decodeSinglePass(
        lines: List<String>,
        accOutput: OutputStream,
        bvpOutput: OutputStream,
        edaOutput: OutputStream,
        tempOutput: OutputStream,
        ibiOutput: OutputStream,
        hrOutput: OutputStream
    ): DecodedSession {


        // Reset all state
        lastAccSeq = -1; lastBvpSeq = -1
        lastAccTimestamp = 0.0; lastBvpTimestamp = 0.0
        droppedPackets = 0; largeGaps = 0
        bvpFilter.reset(); accFilter.reset(); tempFilter.reset()
        edaDecoder.reset()
        bvpSampleCounter = 0
        edaWristBuffer.clear()
        consecutiveOffCount = 0
        consecutiveOnCount = 0
        currentWristStatus = 0
        lastPeakTs = -1.0
        prevBvpSample = 0f
        prevPrevBvpSample = 0f

        var accCount = 0; var bvpCount = 0
        var edaCount = 0; var tempCount = 0
        var ibiCount = 0; var hrCount = 0

        // CSV headers
        accOutput.write("timestamp, x, y, z, x_filtered, y_filtered, z_filtered\n".toByteArray())
        bvpOutput.write("timestamp, bvp, bvp_filtered\n".toByteArray())
        edaOutput.write("timestamp, eda, eda_filtered, on_wrist\n".toByteArray())
        tempOutput.write("timestamp, temp, temp_filtered\n".toByteArray())
        ibiOutput.write("timestamp, ibi\n".toByteArray())
        hrOutput.write("timestamp, hr\n".toByteArray())


        for (line in lines) {
            if (line.startsWith("#") || line.isBlank()) continue

            val parts = line.split(",", limit = 3)
            if (parts.size < 3) continue

            val ts = parts[0].trim().toDoubleOrNull() ?: continue
            val char = parts[1].trim()
            val hex = parts[2].trim()
            val bytes = hexToBytes(hex) ?: continue

            when (char) {
                "ACC" -> {
                    val n = decodeAcc(bytes, ts, accOutput)
                    if (n > 0) accCount += n
                }
                "BVP" -> {
                    val (n, ibiN, hrN) = decodeBvp(bytes, ts, bvpOutput, ibiOutput, hrOutput)
                    if (n > 0) bvpCount += n
                    if (ibiN > 0) ibiCount += ibiN
                    if (hrN > 0) hrCount += hrN
                }
                "EDA" -> {
                    val n = decodeEda(bytes, ts, edaOutput)
                    if (n > 0) edaCount += n
                }
                "TEMP" -> {
                    val n = decodeTemp(bytes, ts, tempOutput)
                    if (n > 0) tempCount += n
                }
            }
        }

        Log.d(TAG, "Decoded: ACC=$accCount BVP=$bvpCount IBI=$ibiCount HR=$hrCount EDA=$edaCount TEMP=$tempCount " +
                "dropped=$droppedPackets gaps=$largeGaps EDA stats: ${edaDecoder.getStats()}")

        return DecodedSession(accCount, bvpCount, edaCount, tempCount, ibiCount, hrCount, droppedPackets, largeGaps)
    }

    private fun checkGap(
        seq: Int, lastSeq: Int,
        ts: Double, lastTs: Double,
        samplesPerPacket: Int, packetDuration: Double
    ): Int {
        if (lastSeq < 0) return 0
        val expected = (lastSeq + 1) and 0xFF
        if (seq == expected) return 0
        val gap = ((seq - lastSeq + 256) % 256) - 1
        if (gap !in 1..200) return 0
        droppedPackets += gap
        val gapDuration = if (ts - lastTs > 0) ts - lastTs else gap * packetDuration
        if (gapDuration <= E4PacketFormat.GAP_THRESHOLD_SECONDS) return 0
        largeGaps++
        return gap * samplesPerPacket
    }

    private fun decodeAcc(bytes: ByteArray, ts: Double, out: OutputStream): Int {
        if (bytes.size < E4PacketFormat.PACKET_SIZE) return 0
        val seq = E4PacketFormat.getSequenceByte(bytes)
        var count = 0

        val nanCount = checkGap(seq, lastAccSeq, ts, lastAccTimestamp,
            E4PacketFormat.ACC_SAMPLES_PER_PACKET, E4PacketFormat.ACC_PACKET_DURATION)
        if (nanCount > 0) {
            val gapStart = lastAccTimestamp + E4PacketFormat.ACC_PACKET_DURATION
            for (g in 0 until nanCount) {
                val gapTs = gapStart + (g * E4PacketFormat.ACC_SAMPLE_PERIOD)
                out.write("%.6f, NaN, NaN, NaN, NaN, NaN, NaN\n".format(Locale.US, gapTs).toByteArray())
                count++
            }
        }
        lastAccSeq = seq; lastAccTimestamp = ts

        for (i in 0 until E4PacketFormat.ACC_SAMPLES_PER_PACKET) {
            val offset = i * 3
            if (offset + 2 >= E4PacketFormat.ACC_DATA_BYTES) break

            val (xG, yG, zG) = E4PacketFormat.decodeAccTriplet(bytes, offset)
            val (fX, fY, fZ) = accFilter.filter(xG, yG, zG)
            val sampleTs = ts + (i * E4PacketFormat.ACC_SAMPLE_PERIOD)

            val mag = kotlin.math.sqrt(fX * fX + fY * fY + fZ * fZ)
            bvpProcessor.setAccMagnitude(mag)

            out.write("%.6f, %.6f, %.6f, %.6f, %.6f, %.6f, %.6f\n".format(
                Locale.US, sampleTs, xG, yG, zG, fX, fY, fZ
            ).toByteArray())
            count++
        }
        return count
    }

    private fun decodeBvp(bytes: ByteArray, ts: Double, out: OutputStream, ibiOut: OutputStream, hrOut: OutputStream): Triple<Int, Int, Int> {
        if (bytes.size < E4PacketFormat.PACKET_SIZE) return Triple(0, 0, 0)

        var count = 0
        var hrWrittenCount = 0
        var ibiWrittenCount = 0

        bvpProcessor.onSampleDecoded = { sample ->
            val sampleTs = ts + (count * (1.0 / 64.0))

//            // DEBUG: Log every 100th sample to see filtered values
//            if (bvpSampleCounter % 100 == 0) {
//                Log.d(TAG, "BVP[$bvpSampleCounter] filtered=${sample.filteredBvp} prev=$prevBvpSample prevPrev=$prevPrevBvpSample lastPeak=$lastPeakTs")
//            }
//
//            // Log zero crossings
//            if (prevBvpSample > 0f && sample.filteredBvp <= 0f) {
//                Log.d(TAG, "ZERO-CROSSING at sample $bvpSampleCounter, ts=$sampleTs, prevPrev=$prevPrevBvpSample prev=$prevBvpSample cur=${sample.filteredBvp} lastPeak=$lastPeakTs")
//            }

            out.write("%.6f, %.6f, %.6f\n".format(
                Locale.US, sampleTs, sample.rawBvp, sample.filteredBvp
            ).toByteArray())

            // IBI detection: simple zero-crossing on filtered BVP
            // Detect positive-to-negative zero crossing (peak just passed)
            if (prevBvpSample > 0f && sample.filteredBvp <= 0f) {
                if (lastPeakTs > 0.0) {
                    val ibi = sampleTs - lastPeakTs
                    if (ibi in 0.3..2.0) { // Valid IBI: 30-200 BPM
                        ibiOut.write("%.6f, %.4f\n".format(Locale.US, sampleTs, ibi).toByteArray())
                        ibiWrittenCount++
                    }
                }
                lastPeakTs = sampleTs
            }
            prevPrevBvpSample = prevBvpSample
            prevBvpSample = sample.filteredBvp

            if (bvpSampleCounter % 64 == 0) {
                val currentHr = bvpProcessor.heartRate.value
                if (currentHr > 0f) {
                    hrOut.write("%.6f, %.2f\n".format(Locale.US, sampleTs, currentHr).toByteArray())
                    hrWrittenCount++
                }
            }
            bvpSampleCounter++
            count++
        }

        bvpProcessor.processPacket(bytes)
        return Triple(count, ibiWrittenCount, hrWrittenCount)
    }

    /**
     * Decode EDA — VERIFIED to match Python E4_Parser.py exactly:
     * - Type B floats: apply floatToConductance() (getConductanceE4 formula)
     * - Type A ADC: SKIP entirely
     * - No additional filtering
     *
     * Output: both "eda" and "eda_filtered" columns get the same value
     * (no filter applied, matching Python behavior)
     */
    private fun decodeEda(bytes: ByteArray, ts: Double, out: OutputStream): Int {
        if (bytes.size < E4PacketFormat.PACKET_SIZE) return 0

        val packetType = E4PacketFormat.classifyEdaPacket(bytes)

        when (packetType) {
            E4PacketFormat.EdaPacketType.TYPE_B_FLOAT -> {
                val results = mutableListOf<Triple<Double, Float, Float>>()
                edaDecoder.onEdaSampleWithFiltered = { sampleTs, raw, filtered ->
                    results.add(Triple(sampleTs, raw, filtered))
                }
                edaDecoder.processPacket(bytes, ts)

                var count = 0
                for ((sampleTs, raw, filtered) in results) {
                    val wristStatus = evaluateOfflineWristStatus(filtered)
                    val statusLabel = if (wristStatus == 1) "" else "not worn"

                    out.write("%.6f, %.6f, %.6f, %s\n".format(
                        Locale.US, sampleTs, raw, filtered, statusLabel
                    ).toByteArray())
                    count++
                }
                return count
            }
            E4PacketFormat.EdaPacketType.TYPE_A_ADC -> {
                // Skip — Type B floats are authoritative for E4 fw > 317
                return 0
            }
            else -> return 0
        }
    }

    private fun decodeTemp(bytes: ByteArray, ts: Double, out: OutputStream): Int {
        if (bytes.size < E4PacketFormat.PACKET_SIZE) return 0
        var count = 0

        for (i in 0 until E4PacketFormat.TEMP_SAMPLES_PER_PACKET) {
            val offset = i * 2
            if (offset + 1 >= E4PacketFormat.TEMP_DATA_BYTES) break

            val tempC = E4PacketFormat.decodeTempSample(bytes, offset) ?: continue
            val filtered = tempFilter.filter(tempC)
            val sampleTs = ts + (i * E4PacketFormat.TEMP_SAMPLE_PERIOD)

            // Feed RAW temp into wrist detection (not filtered)
            updateTempForWrist(tempC)

            out.write("%.6f, %.2f, %.2f\n".format(Locale.US, sampleTs, tempC, filtered).toByteArray())
            count++
        }
        return count
    }
    private fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.replace(" ", "").lowercase()
        if (clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i ->
                val idx = i * 2
                ((Character.digit(clean[idx], 16) shl 4) +
                        Character.digit(clean[idx + 1], 16)).toByte()
            }
        } catch (_: Exception) { null }
    }



    /**
     * Call this from decodeTemp() to feed temperature into wrist detection.
     */
    private fun updateTempForWrist(rawTempC: Float) {
        tempBuffer.addLast(rawTempC)
        if (tempBuffer.size > 8) tempBuffer.removeFirst()
        lastRawTempValue = rawTempC

        // Detect rapid temperature drop (> 1°C in 8 samples)
        if (tempBuffer.size >= 4) {
            val recent = tempBuffer.takeLast(4).average().toFloat()
            val older = tempBuffer.take(4).average().toFloat()
            tempDropDetected = (older - recent) > 1.0f
        }
    }

    /**
     * Offline wrist detection using EDA variance + temperature assist.
     *
     * From real E4 data:
     * - ON-WRIST:  stable EDA (variance < 0.0005, range < 0.02), temp > 30°C
     * - OFF-WRIST: erratic EDA jumps OR temp dropping rapidly OR temp < 25°C
     *
     * Temperature is key for detecting RE-WEARING:
     * EDA stays erratic for 30-60s after putting device back on,
     * but temperature recovers within seconds (28°C → 34°C).
     */
    private fun evaluateOfflineWristStatus(filteredEda: Float): Int {
        edaWristBuffer.addLast(filteredEda)
        if (edaWristBuffer.size > 20) edaWristBuffer.removeFirst()

        if (edaWristBuffer.size < 4) {
            currentWristStatus = 1
            return currentWristStatus
        }

        val currentMean = edaWristBuffer.average().toFloat()
        val variance = edaWristBuffer.map { (it - currentMean).let { d -> d * d } }
            .average().toFloat()
        val range = (edaWristBuffer.maxOrNull() ?: 0f) - (edaWristBuffer.minOrNull() ?: 0f)
        val maxVal = edaWristBuffer.maxOrNull() ?: 0f

        // OFF-WRIST indicators:
        // 1. Large range in EDA window (on-wrist changes < 0.02 µS/sample)
        // 2. High variance
        // 3. Impossibly high EDA value
        // 4. Temperature dropping rapidly (watch being removed)
        // 5. Temperature below skin threshold (< 28°C = not on skin)
        val isLikelyOff = (range > 0.15f) ||
                (variance > 0.005f) ||
                (maxVal > 2.0f) ||
                tempDropDetected ||
                (lastRawTempValue > 0f && lastRawTempValue < 28.0f)

        // ON-WRIST: stable EDA AND warm temperature
        // Temperature > 30°C is the KEY indicator for re-wearing detection
        // even when EDA is still settling
        val isLikelyOn = (variance < 0.001f) &&
                (range < 0.03f) &&
                (currentMean > 0.005f) &&
                (maxVal < 1.5f) &&
                (lastRawTempValue == 0f || lastRawTempValue > 30.0f)

        // ALTERNATIVE on-wrist: temperature recovered but EDA still settling
        // If temp > 33°C, device is definitely on skin regardless of EDA instability
        val tempConfirmsOnWrist = (lastRawTempValue > 33.0f) && !tempDropDetected

        if (currentWristStatus == -1) {
            currentWristStatus = if (isLikelyOff) 0 else 1
            return currentWristStatus
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

        return currentWristStatus
    }
}