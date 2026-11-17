package com.empatica.e4companion.processing

import com.empatica.e4companion.decoder.E4PacketFormat
import com.empatica.e4companion.decoder.EdaFilter

class EdaDecoder {
    private val calibrator = E4EdaCalibrator()
    private val edaFilter = EdaFilter()

    var onEdaSample: ((Double, Float) -> Unit)? = null
    var onEdaSampleWithFiltered: ((Double, Float, Float) -> Unit)? = null

    private var typeACount = 0
    private var typeBCount = 0
    private var unknownCount = 0

    companion object {
        private const val TAG = "EdaDecoder"
    }

    fun setDeviceId(hardwareId: String) {
        calibrator.setDeviceOffset(hardwareId)
    }

    fun processPacket(data: ByteArray, timestamp: Double) {
        if (data.size < E4PacketFormat.PACKET_SIZE) return

        when (E4PacketFormat.classifyEdaPacket(data)) {
            E4PacketFormat.EdaPacketType.TYPE_B_FLOAT -> {
                processFloatPacket(data, timestamp)
                typeBCount++
            }
            E4PacketFormat.EdaPacketType.TYPE_A_ADC -> {
                // SKIP: Type A requires 2-bit flag state machine from
                // PacketHandler_handlePacketForE4 (uVar20 >> 10 & 3)
                // that tracks slowAdcAccumulator across packets.
                // Type B floats are authoritative for fw > 317.
                typeACount++
            }
            else -> {
                unknownCount++
            }
        }
    }

    private fun processFloatPacket(data: ByteArray, timestamp: Double) {
        val floats = E4PacketFormat.decodeEdaFloats(data) ?: return
        for (i in floats.indices) {
            val sampleTs = timestamp + (i * E4PacketFormat.EDA_SAMPLE_PERIOD)
            val conductance = calibrator.floatToConductance(floats[i])
            val filtered = edaFilter.filter(conductance)

            onEdaSample?.invoke(sampleTs, conductance)
            onEdaSampleWithFiltered?.invoke(sampleTs, conductance, filtered)
        }
    }

    fun reset() {
        calibrator.reset()
        edaFilter.reset()
        typeACount = 0
        typeBCount = 0
        unknownCount = 0
    }

    fun getStats(): String = "TypeA=$typeACount TypeB=$typeBCount Unknown=$unknownCount"
}