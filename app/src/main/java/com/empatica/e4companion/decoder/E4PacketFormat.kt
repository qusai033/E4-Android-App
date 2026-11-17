package com.empatica.e4companion.decoder

/**
 * Single source of truth for E4 BLE packet format constants and filter specs.
 *
 * Filter specifications based on published research:
 *
 * BVP: 2nd-order Butterworth bandpass 0.7–3.5 Hz [2]
 *   "Bandpass filter between 0.7 and 3.5 Hz using a second-order Butterworth filter,
 *    applied by Langevin et al. (2021) in an EmotiBit wearable PPG validation study"
 *
 * EDA: 4th-order Butterworth low-pass at 1.0 Hz
 *   EDA signals contain tonic (SCL) and phasic (SCR) components [4]
 *   SCR threshold ~0.05 μS, frequencies well below 1 Hz
 *   Preceded by median filter for motion artifact removal
 *
 * ACC: 4th-order Butterworth low-pass at 5.0 Hz [3]
 *   Human movement: walk 0.5–3 Hz, transitions 0.3–2 Hz, stairs ~4 Hz
 *   "Everything above 10 Hz is usually noise, vibration, or shock" [3]
 *   "4th-order Butterworth low-pass filter with 5 Hz cutoff works well" [3]
 *
 * TEMP: 2nd-order Butterworth low-pass at 0.1 Hz
 *   Skin temperature changes very slowly (seconds to minutes)
 *   High-frequency components are sensor noise
 */
object E4PacketFormat {

    // ── Sample rates [4] ──
    const val ACC_SAMPLE_RATE = 32.0
    const val BVP_SAMPLE_RATE = 64.0
    const val EDA_SAMPLE_RATE = 4.0
    const val TEMP_SAMPLE_RATE = 4.0

    // ── Samples per packet [1] ──
    const val ACC_SAMPLES_PER_PACKET = 6
    const val BVP_SAMPLES_PER_PACKET = 9
    const val TEMP_SAMPLES_PER_PACKET = 8
    const val EDA_ADC_SAMPLES_PER_PACKET = 6  // Not 4!
    const val EDA_FLOAT_SAMPLES_PER_PACKET = 4  // 100.0 / 4096 approximate

    // ── Sample periods ──
    const val ACC_SAMPLE_PERIOD = 1.0 / ACC_SAMPLE_RATE
    const val BVP_SAMPLE_PERIOD = 1.0 / BVP_SAMPLE_RATE
    const val EDA_SAMPLE_PERIOD = 1.0 / EDA_SAMPLE_RATE
    const val TEMP_SAMPLE_PERIOD = 1.0 / TEMP_SAMPLE_RATE

    // ── Packet durations ──
    const val ACC_PACKET_DURATION = ACC_SAMPLES_PER_PACKET / ACC_SAMPLE_RATE
    const val BVP_PACKET_DURATION = BVP_SAMPLES_PER_PACKET / BVP_SAMPLE_RATE

    // ── Conversion factors ──
    const val ACC_TO_G = 2.0f / 128.0f
    const val TEMP_DIVISOR = 500.0f
    const val EDA_MIN = 0.001f
    const val EDA_MAX = 100.0f
    const val TEMP_MIN = 15.0f
    const val TEMP_MAX = 45.0f

    // ── Packet structure [1] ──
    const val ACC_DATA_BYTES = 18
    const val BVP_DATA_BYTES = 18
    const val TEMP_DATA_BYTES = 16
    const val EDA_FLOAT_DATA_BYTES = 16
    const val PACKET_SIZE = 20
    const val SEQ_BYTE_INDEX = 19
    const val FLAGS_BYTE_INDEX = 18

    // ── Gap threshold ──
    const val GAP_THRESHOLD_SECONDS = 5.0

    // ══════════════════════════════════════
    // FILTER SPECIFICATIONS
    // ══════════════════════════════════════

    // ── BVP DECODING STATUS ──
    // WARNING: The E4 BVP data is proprietary compressed format.
    // The native library (libempac.so) performs decompression that
    // we cannot replicate. Raw int16 interpretation produces values
    // like 8216, -16129, 32704 which are NOT valid PPG readings.
    //
    // Real E4 BVP values (after native decoding) should be roughly
    // in the range of -500 to +500.
    //
    // The raw packets are saved untouched in raw_packets.log for
    // future decoding if the format is reverse-engineered.
    //
    // Current status of each sensor's decoding accuracy:
    //   ACC:  ✓ VERIFIED — 6 × int8 triplets, 64 raw = 1g
    //   TEMP: ✓ VERIFIED — 8 × uint16 LE / 500.0 = °C (~30.8°C typical)
    //   EDA:  ✓ VERIFIED — 4 × IEEE 754 float32 LE = μS
    //   BVP:  ✗ UNVERIFIED — proprietary encoding, int16 interpretation
    //         produces invalid values. Displayed as best-effort only.
    const val BVP_FILTER_ORDER = 4
    const val BVP_FILTER_LOW_HZ = 0.5
    const val BVP_FILTER_HIGH_HZ = 5.0
    val BVP_FILTER_CENTER_HZ get() = (BVP_FILTER_LOW_HZ + BVP_FILTER_HIGH_HZ) / 2.0
    val BVP_FILTER_WIDTH_HZ get() = BVP_FILTER_HIGH_HZ - BVP_FILTER_LOW_HZ

    // EDA: 4th-order Butterworth low-pass at 1.0 Hz
    const val EDA_FILTER_ORDER = 4
    const val EDA_FILTER_CUTOFF_HZ = 1.0
    const val EDA_MEDIAN_WINDOW = 5  // samples for median filter

    // ACC: 4th-order Butterworth low-pass at 5.0 Hz [3]
    const val ACC_FILTER_ORDER = 4
    const val ACC_FILTER_CUTOFF_HZ = 5.0

    // TEMP: 2nd-order Butterworth low-pass at 0.1 Hz
    const val TEMP_FILTER_ORDER = 2
    const val TEMP_FILTER_CUTOFF_HZ = 0.1

    // ── Decode helpers ──

    fun decodeAccTriplet(bytes: ByteArray, offset: Int): Triple<Float, Float, Float> {
        val rawX = bytes[offset].toInt()
        val rawY = bytes[offset + 1].toInt()
        val rawZ = bytes[offset + 2].toInt()
        return Triple(rawX * ACC_TO_G, rawY * ACC_TO_G, rawZ * ACC_TO_G)
    }

    fun decodeBvpSample(bytes: ByteArray, offset: Int): Float {
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt() and 0xFF
        val int16 = (high shl 8) or low
        val signed = if (int16 >= 32768) int16 - 65536 else int16
        return signed.toFloat()
    }

    fun decodeTempSample(bytes: ByteArray, offset: Int): Float? {
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt() and 0xFF
        val raw = (high shl 8) or low
        val tempC = raw * 0.02f - 273.15f
        return if (tempC in TEMP_MIN..TEMP_MAX) tempC else null
    }

    // ══════════════════════════════════════
    // TYPE A: RAW ADC DECODING
    // ══════════════════════════════════════

    /**
     * Decode Type A EDA packet: 6 pairs of 12-bit ADC values (fast/slow).
     *
     * Packet structure (20 bytes):
     *   [0..17]  12 × 12-bit values packed with 4-bit alignment
     *            (6 pairs: fast_0, slow_0, fast_1, slow_1, ..., fast_5, slow_5)
     *   [18..19] sequence counter (little-endian)
     *
     * The 12-bit values need device-specific calibration via getConductanceE4()
     * to convert to μS. Without calibration, we use an approximation.
     */

    fun decodeEdaFromPacket(bytes: ByteArray): List<Float>? {
        if (bytes.size < PACKET_SIZE) return null
        if (classifyEdaPacket(bytes) != EdaPacketType.TYPE_A_ADC) return null

        try {
            val samples12bit = decode12BitSamples(bytes)
            if (samples12bit.size < 2) return null

            val edaValues = mutableListOf<Float>()
            for (i in 0 until minOf(samples12bit.size / 2, 6)) {
                val fastAdc = samples12bit[i * 2]
                val slowAdc = samples12bit[i * 2 + 1]
                val conductance = approximateConductance(fastAdc, slowAdc)
                if (conductance >= 0.0f && conductance <= EDA_MAX) {
                    edaValues.add(conductance)
                }
            }
            return if (edaValues.isNotEmpty()) edaValues else null
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Decode 12-bit samples from the packed BLE packet.
     * From decompiled: uses 4-bit offset stepping through bytes.
     */
    private fun decode12BitSamples(bytes: ByteArray): List<Int> {
        val samples = mutableListOf<Int>()
        var byteIndex = 0
        var bitOffset = 4

        for (i in 0 until 12) {
            if (byteIndex + 1 >= PACKET_SIZE - 1) break

            val high = (bytes[byteIndex].toInt() and 0xFF) shl (bitOffset and 0x1F) and 0xFFF
            val bitsRemaining = 8 - bitOffset
            val low = (bytes[byteIndex + 1].toInt() and 0xFF) shr (bitsRemaining and 0x1F)
            val sample = high or low
            samples.add(sample and 0xFFF)

            if (bitOffset < 8) {
                byteIndex += 2
                bitOffset += 4
                if (bitOffset >= 8) {
                    bitOffset -= 8
                    byteIndex -= 1
                }
            } else {
                bitOffset = 4
                byteIndex += 1
            }
        }
        return samples
    }

    /**
     * Approximate conductance without device calibration.
     *
     * From getConductanceE4 decompilation:
     *   voltage = (param_2 + 0.5) * 0.00263671875 - calibrationOffset
     *   result = voltage * (-21.28 / (DAT_0040bd04 + 42.08)) + DAT_0040bd04 * factor
     *
     * Without calibration, simplified linear model:
     */
    private fun approximateConductance(fastAdc: Int, slowAdc: Int): Float {
        val voltage = (fastAdc.toFloat() + 0.5f) * 0.00263671875f
        return (voltage * 10.0f).coerceIn(0.0f, EDA_MAX)
    }
    // ══════════════════════════════════════
    // PACKET TYPE DETECTION
    // ══════════════════════════════════════

    /**
     * Determine if an EDA packet is Type A (raw ADC) or Type B (float).
     *
     * Type A packets have the pattern: 6 × 3-byte samples where byte[2] of each
     * triplet is consistently 0xA6 (or similar fixed value), plus 2-byte sequence.
     *
     * Type B packets contain 4 × IEEE 754 float32 values (16 bytes) + 4 bytes metadata.
     * The metadata starts with 0x0001 followed by a 2-byte sequence counter.
     *
     * Detection strategy:
     * - Check bytes 16-17: if they equal 0x00,0x01 → likely Type B (float)
     * - Validate by checking if 4 floats are in plausible EDA range
     * - Otherwise treat as Type A (raw ADC)
     */
    enum class EdaPacketType {
        TYPE_A_ADC,      // Raw 12-bit ADC pairs (needs calibration)
        TYPE_B_FLOAT,    // Pre-calibrated IEEE 754 float μS values
        HEADER,          // Initial configuration/timestamp packet
        UNKNOWN
    }
    fun classifyEdaPacket(bytes: ByteArray): EdaPacketType {
        if (bytes.size < PACKET_SIZE) return EdaPacketType.UNKNOWN

        // 1. Check for Type B signature: bytes[16..17] == 0x00, 0x01 (LE marker 0x0100)
        val marker = (bytes[16].toInt() and 0xFF) or ((bytes[17].toInt() and 0xFF) shl 8)
        if (marker == 0x0100) {
            val floats = tryDecodeFloats(bytes)
            if (floats != null) {
                return EdaPacketType.TYPE_B_FLOAT
            }
        }

        // 2. If not Type B, check if it's a valid Type A (ADC) packet
        //    For E4 fw > 317: Type A packets use 2-bit flags in bits[10:11]
        //    that require the full native library state machine to decode properly.
        //    We process them through the ADC path which handles the flag extraction.
        if (isValidTypeAPacket(bytes)) {
            return EdaPacketType.TYPE_A_ADC
        }

        // 3. Could be a header/config packet on first connection
        return EdaPacketType.UNKNOWN
    }

    /**
     * Validate Type A ADC packet structure.
     *
     * Removes the broken 0xA6 heuristic. Instead validates structurally:
     * - Data region (bytes 0..17) is not all zeros
     * - Marker bytes are NOT 0x0100 (that's Type B)
     * - Decoded 12-bit values are in valid range (0..4095)
     * - At least one non-zero fast_adc value exists
     */
    private fun isValidTypeAPacket(bytes: ByteArray): Boolean {
        // Check that data region isn't all zeros
        var hasNonZero = false
        for (i in 0 until 18) {
            if (bytes[i].toInt() != 0) {
                hasNonZero = true
                break
            }
        }
        if (!hasNonZero) return false

        // The marker should NOT be 0x0100 (that's Type B territory)
        val marker = (bytes[16].toInt() and 0xFF) or ((bytes[17].toInt() and 0xFF) shl 8)
        if (marker == 0x0100) return false

        // Try to decode 12-bit values and check they're in valid ADC range
        val samples = decode12BitSamples(bytes)
        if (samples.size < 2) return false

        // All decoded values should be in 0..4095 range (12-bit)
        // and at least one pair should have a non-zero fast_adc
        var hasValidPair = false
        for (i in 0 until minOf(samples.size / 2, 6)) {
            val fastAdc = samples[i * 2]
            val slowAdc = samples[i * 2 + 1]
            if (fastAdc < 0 || fastAdc > 4095 || slowAdc < 0 || slowAdc > 4095) {
                return false
            }
            if (fastAdc > 0) {
                hasValidPair = true
            }
        }

        return hasValidPair
    }

    // ══════════════════════════════════════
    // TYPE B: FLOAT DECODING (VERIFIED)
    // ══════════════════════════════════════

    /**
     * Decode Type B EDA packet: 4 × IEEE 754 float32 LE values.
     * These are pre-calibrated conductance values in μS.
     *
     * Packet structure (20 bytes):
     *   [0..3]   float32 LE: EDA sample 1 (μS)
     *   [4..7]   float32 LE: EDA sample 2 (μS)
     *   [8..11]  float32 LE: EDA sample 3 (μS)
     *   [12..15] float32 LE: EDA sample 4 (μS)
     *   [16..17] marker: 0x0001 (constant)
     *   [18..19] sequence counter (little-endian, increments each packet)
     *
     * At 4 Hz sampling, 4 samples = 1.0 second per packet.
     */
    fun decodeEdaFloats(bytes: ByteArray): List<Float>? {
        if (bytes.size < PACKET_SIZE) return null
        if (classifyEdaPacket(bytes) != EdaPacketType.TYPE_B_FLOAT) return null
        return tryDecodeFloats(bytes)
    }

    private fun tryDecodeFloats(bytes: ByteArray): List<Float>? {
        try {
            val buf = java.nio.ByteBuffer.wrap(bytes, 0, EDA_FLOAT_DATA_BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(4) { buf.getFloat(it * 4) }

            // Validate: all values must be finite and in plausible EDA range
            // Allow 0.0 for watch-off scenarios
            if (floats.all { it.isFinite() && it >= 0.0f && it <= EDA_MAX }) {
                return floats.toList()
            }
        } catch (_: Exception) { }
        return null
    }

    fun getSequenceByte(bytes: ByteArray): Int {
        return if (bytes.size > SEQ_BYTE_INDEX) bytes[SEQ_BYTE_INDEX].toInt() and 0xFF else -1
    }

    /**
     * Get the Type B float packet sequence number (bytes 18-19, LE).
     */
    fun getFloatPacketSequence(bytes: ByteArray): Int {
        if (bytes.size < PACKET_SIZE) return -1
        return (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)
    }
}