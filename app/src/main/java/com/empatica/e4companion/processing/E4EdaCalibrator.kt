package com.empatica.e4companion.processing

/**
 * EDA calibration for Empatica E4 — verified to match Python exactly.
 *
 * Port of getConductanceE4() from libempac.so:
 * For Type B float packets: floats are corrected voltages that
 * still need the final formula applied.
 *
 * VERIFIED: Output matches E4_EDA_Conductance.py E4EdaFromFloat
 */
class E4EdaCalibrator {

    companion object {
        private const val ADC_TO_VOLTAGE = 0.00263671875
        private const val CORRECTION_A = -21.28
        private const val CORRECTION_B = 42.08
        private const val TABLE_PERIOD = 117
        private const val DEFAULT_OFS = 9800
    }

    private var deviceOffset: Float = 0.0f
    private var calibrationVoltage: Double = 0.0
    private var isFirstSample = true
    private var matrixValue: Double = 0.0
    private var matrixComputed = false

    // For Type B float processing — matches Python's E4EdaFromFloat
    private var floatCalibrationVoltage: Float = 0f
    private var isFirstFloatSample = true

    fun setDeviceOffset(hardwareId: String) {
        // Device-specific offsets from hardware characterization
        // These are NOT used for Type B float processing (floats are pre-calibrated)
        deviceOffset = when (hardwareId.uppercase()) {
            "084D03" -> -0.1f
            "5E6D03" -> -0.42f
            "6E6D03" -> -0.24f
            "252103" -> -0.26f
            "593E03" -> -0.01f
            "3F2103" -> -0.002f
            else -> 0.0f
        }
    }

    /**
     * Convert Type B float32 values to conductance.
     *
     * EXACTLY matches Python's E4EdaFromFloat.process_float_packet():
     *   if sample_count == 0:
     *       calibration_voltage = float(np.float32(float_value))
     *   sample_count += 1
     *   fVar5 = calibration_voltage
     *   fVar4 = -21.28 / (fVar5 + 42.08)
     *   conductance = (fVar5 * -fVar4) + float_value * fVar4
     *   return max(0.0, conductance)
     */
    fun floatToConductance(floatValue: Float): Float {
        if (isFirstFloatSample) {
            // Store as Float32 — matches Python's float(np.float32(float_value))
            floatCalibrationVoltage = floatValue
            isFirstFloatSample = false
        }

        // Promote to Double for computation — matches Python's float64 arithmetic
        val fVar5 = floatCalibrationVoltage.toDouble()
        val fVar4 = CORRECTION_A / (fVar5 + CORRECTION_B)
        val conductance = (fVar5 * -fVar4) + floatValue.toDouble() * fVar4

        return maxOf(0f, conductance.toFloat())
    }

    /**
     * Convert Type A ADC values to conductance.
     * Currently SKIPPED in the pipeline — Type B floats are authoritative.
     * Kept for potential future use with proper characterization matrix.
     */
    fun adcToConductance(fastAdc: Int, slowAdc: Int): Float {
        ensureMatrixComputed()

        val rawVoltage = (fastAdc.toDouble() + 0.5) * ADC_TO_VOLTAGE
        val correctedVoltage = rawVoltage - matrixValue

        if (isFirstSample) {
            calibrationVoltage = correctedVoltage
            isFirstSample = false
        }

        val fVar5 = calibrationVoltage
        val fVar4 = CORRECTION_A / (fVar5 + CORRECTION_B)
        val conductance = (fVar5 * -fVar4) + correctedVoltage * fVar4

        return maxOf(0f, conductance.toFloat())
    }

    private fun computeMatrixValue(ofs: Int): Double {
        val param1 = ofs and 0xFFFF
        if (param1 < 0x2D) return 0.0

        val uVar9 = param1 - 0x2D
        val iterations = uVar9 / 9 + 1
        var accumulator = 3.6056999999999997
        var currentOffset = uVar9

        val table = EdaCharacterization.DELTA_TABLE_117

        for (i in 0 until iterations) {
            val tableIdx = currentOffset % TABLE_PERIOD
            accumulator += table[tableIdx]
            currentOffset -= 9
        }

        return accumulator
    }

    private fun ensureMatrixComputed() {
        if (!matrixComputed) {
            matrixValue = computeMatrixValue(DEFAULT_OFS)
            matrixComputed = true
        }
    }

    fun decodePacket(bytes: ByteArray): List<Pair<Int, Int>>? {
        if (bytes.size < 20) return null
        val values = mutableListOf<Int>()
        var byteIdx = 0
        var bitOffset = 4

        for (i in 0 until 12) {
            if (byteIdx + 1 >= 19) break
            val highPart = ((bytes[byteIdx].toInt() and 0xFF) shl bitOffset) and 0xFFF
            val bitsFromFirst = 8 - bitOffset
            val lowPart = (bytes[byteIdx + 1].toInt() and 0xFF) shr bitsFromFirst
            values.add((highPart or lowPart) and 0xFFF)

            val newBitOffset = bitOffset + 4
            if (newBitOffset < 8) {
                bitOffset = newBitOffset
                byteIdx += 1
            } else {
                bitOffset = 4
                byteIdx += 2
            }
        }
        if (values.size < 2) return null
        return (0 until values.size / 2).map {
            Pair(values[it * 2], values[it * 2 + 1])
        }
    }

    fun reset() {
        isFirstSample = true
        calibrationVoltage = 0.0
        isFirstFloatSample = true
        floatCalibrationVoltage = 0f
    }
}