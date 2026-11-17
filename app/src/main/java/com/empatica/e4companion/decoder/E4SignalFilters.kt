package com.empatica.e4companion.decoder

import uk.me.berndporr.iirj.Butterworth

/**
 * Signal filters for E4 sensor data.
 * Used by both real-time processors and the batch decoder.
 *
 * Each sensor gets appropriate filtering based on its signal characteristics:
 *
 * BVP (64 Hz): 2nd-order Butterworth bandpass 0.7–3.5 Hz [2]
 *   Removes baseline drift (below 0.7 Hz) and high-frequency noise (above 3.5 Hz)
 *   Preserves cardiac frequencies: 42–210 BPM
 *
 * EDA (4 Hz): Median filter (window=5) → 4th-order Butterworth low-pass 1.0 Hz
 *   Median filter removes motion artifact spikes
 *   Low-pass preserves tonic (SCL) and phasic (SCR) components [4]
 *   SCR events are well below 1 Hz
 *
 * ACC (32 Hz): 4th-order Butterworth low-pass 5.0 Hz [3]
 *   Human movement: walk 0.5–3 Hz, stairs ~4 Hz
 *   "Everything above 10 Hz is usually noise" [3]
 *   "4th-order Butterworth low-pass filter with 5 Hz cutoff works well" [3]
 *
 * TEMP (4 Hz): 2nd-order Butterworth low-pass 0.1 Hz
 *   Skin temperature changes over seconds to minutes
 *   Removes sensor noise while preserving thermal trends
 */

/**
 * BVP filter with motion artifact rejection for E4 wrist PPG.
 *
 * Three-stage pipeline:
 *
 * Stage 1: Median filter (window = 5 samples at 64 Hz = ~78ms)
 *   Removes sharp spike artifacts from wrist motion.
 *   78ms is much shorter than a cardiac cycle (~800ms at 75 BPM)
 *   so it preserves the PPG waveform shape while eliminating
 *   brief mechanical disturbances.
 *
 * Stage 2: 4th-order Butterworth bandpass 0.5–5.0 Hz
 *   - 0.5 Hz low cutoff removes baseline wander and Mayer waves
 *   - 5.0 Hz high cutoff preserves HR up to 240 BPM plus harmonics
 *     while rejecting powerline and high-frequency noise
 *   - 4th-order matches BioSPPy's BVP processing approach [3][4]
 *   - BioSPPy uses [1, 8] Hz but that's too wide for wrist PPG
 *   - NeuroKit2 langevin2021 uses [0.7, 3.5] Hz but that's too narrow [2]
 *
 * Stage 3: Moving average (window = 3 samples = ~47ms)
 *   Final smoothing pass to reduce remaining high-frequency ripple
 *   without distorting the PPG pulse wave morphology.
 */
class BvpFilter {
    private val bandpass = Butterworth().apply {
        bandPass(
            E4PacketFormat.BVP_FILTER_ORDER,
            E4PacketFormat.BVP_SAMPLE_RATE,
            E4PacketFormat.BVP_FILTER_CENTER_HZ,
            E4PacketFormat.BVP_FILTER_WIDTH_HZ
        )
    }

    // Stage 1: Median filter (5-sample window = ~78ms at 64 Hz)
    private val medianWindow = FloatArray(5)
    private var medianPos = 0
    private var medianFilled = false

    // Stage 3: Moving average (3-sample window = ~47ms)
    private val smoothWindow = FloatArray(3)
    private var smoothPos = 0
    private var smoothFilled = false

    fun filter(sample: Float): Float {
        // Stage 1: Median filter — removes spike artifacts
        medianWindow[medianPos] = sample
        medianPos = (medianPos + 1) % 5
        if (medianPos == 0) medianFilled = true

        val medianSize = if (medianFilled) 5 else maxOf(medianPos, 1)
        val sorted = medianWindow.take(medianSize).sorted()
        val median = sorted[medianSize / 2]

        // Stage 2: Butterworth bandpass 0.5–5.0 Hz
        val bandpassed = bandpass.filter(median.toDouble()).toFloat()

        // Stage 3: Moving average — final smoothing
        smoothWindow[smoothPos] = bandpassed
        smoothPos = (smoothPos + 1) % 3
        if (smoothPos == 0) smoothFilled = true

        return if (smoothFilled) {
            (smoothWindow[0] + smoothWindow[1] + smoothWindow[2]) / 3f
        } else {
            bandpassed
        }
    }

    fun reset() {
        bandpass.reset()
        medianWindow.fill(0f)
        medianPos = 0
        medianFilled = false
        smoothWindow.fill(0f)
        smoothPos = 0
        smoothFilled = false
    }
}

/**
 * EDA filter: Median filter (motion artifacts) → Butterworth low-pass 1.0 Hz
 *
 * The median filter removes sharp spikes from motion artifacts
 * while preserving the slow EDA fluctuations [4].
 * The Butterworth LPF then removes remaining HF noise.
 */
/**
 * EDA filter — exact port of the native library's ButterFilter for GSR.
 *
 * From DeviceManager_init [decompiled libempac.so]:
 *   ButterFilter_init((long)pvVar2 + 0x28);
 *   ButterFilter_setLowPassNormalizedFrequency(0x3e4ccccd, ..., 0);
 *
 * 0x3e4ccccd as float = 0.2 (normalized frequency)
 * Third param = 0 → 1st-order filter (param_2[6] = 0)
 *
 * At 4 Hz sample rate: cutoff = 0.2 * (4/2) = 0.4 Hz
 *
 * This matches the native ButterFilter_filtOnline for order 0 (1st order):
 *   y[n] = (b0*x[n] + b1*x[n-1] - a1*y[n-1]) / a0
 */
class EdaFilter {

    companion object {
        // 1st-order Butterworth low-pass, normalized frequency = 0.2
        // Computed via bilinear transform: ω = tan(π * 0.2) ≈ 0.726542528
        private const val NORMALIZED_FREQ = 0.2  // from 0x3e4ccccd

        // Precomputed coefficients for 1st-order Butterworth LPF at fn=0.2
        // ω = tan(π * 0.2) = 0.7265425947600478
        // a0 = 1 + ω = 1.7265425947600478
        // Numerator (b): [ω/(1+ω), ω/(1+ω)] = [0.42081, 0.42081]
        // Denominator (a): [1.0, (1-ω)/(1+ω)] but stored as [a0, a1] where
        //   the filter divides by a0 each step

        private val OMEGA = Math.tan(Math.PI * NORMALIZED_FREQ)
        private val A0 = 1.0 + OMEGA
        private val A1 = -(1.0 - OMEGA) // Note: stored negative in the native code
        private val B0 = OMEGA
        private val B1 = OMEGA
    }

    // State buffers matching native ButterFilter: pfVar6 (input) and pfVar7 (output)
    private var x1: Float = 0f  // x[n-1]
    private var y1: Float = 0f  // y[n-1]
    private var initialized = false

    /**
     * Filter one EDA sample — matches ButterFilter_filtOnline for order 0.
     *
     * Native code (1st order, param_2[6] == 0):
     *   *pfVar6 = param_1;  // x[n] = input
     *   *pfVar7 = (float)((double)((float)(pdVar3[1] * (double)pfVar6[1] +
     *             (double)param_1 * *pdVar3) -
     *             (float)(pdVar2[1] * (double)pfVar7[1])) / *pdVar2);
     *   pfVar6[1] = *pfVar6;  // x[n-1] = x[n]
     *   pfVar7[1] = *pfVar7;  // y[n-1] = y[n]
     */
    fun filter(input: Float): Float {
        if (!initialized) {
            // Native library initializes buffers to 0, but for EDA we can
            // prime with first value to avoid startup transient
            x1 = input
            y1 = input
            initialized = true
            return input
        }

        // Direct Form I, 1st order IIR — matches native ButterFilter_filtOnline
        // y[n] = (b0*x[n] + b1*x[n-1] - a1*y[n-1]) / a0
        val xn = input.toDouble()
        val yn = ((B0 * xn + B1 * x1.toDouble()) - (A1 * y1.toDouble())) / A0

        // Shift buffers
        x1 = input
        y1 = yn.toFloat()

        return y1
    }

    fun reset() {
        x1 = 0f
        y1 = 0f
        initialized = false
    }
}

/**
 * ACC filter: 4th-order Butterworth low-pass at 5.0 Hz [3]
 * One filter per axis (X, Y, Z) since they're independent signals.
 *
 * "For thigh-mounted IMUs at 100 Hz, a 4th-order Butterworth low-pass
 * filter with 5 Hz cutoff works well for general gait and transition
 * analysis. This retains natural motion frequencies while removing
 * high-frequency noise." [3]
 */
class AccFilter {
    private val filterX = Butterworth().apply {
        lowPass(
            E4PacketFormat.ACC_FILTER_ORDER,
            E4PacketFormat.ACC_SAMPLE_RATE,
            E4PacketFormat.ACC_FILTER_CUTOFF_HZ
        )
    }
    private val filterY = Butterworth().apply {
        lowPass(
            E4PacketFormat.ACC_FILTER_ORDER,
            E4PacketFormat.ACC_SAMPLE_RATE,
            E4PacketFormat.ACC_FILTER_CUTOFF_HZ
        )
    }
    private val filterZ = Butterworth().apply {
        lowPass(
            E4PacketFormat.ACC_FILTER_ORDER,
            E4PacketFormat.ACC_SAMPLE_RATE,
            E4PacketFormat.ACC_FILTER_CUTOFF_HZ
        )
    }

    fun filter(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        return Triple(
            filterX.filter(x.toDouble()).toFloat(),
            filterY.filter(y.toDouble()).toFloat(),
            filterZ.filter(z.toDouble()).toFloat()
        )
    }

    fun reset() {
        filterX.reset()
        filterY.reset()
        filterZ.reset()
    }
}

/**
 * TEMP filter: 2nd-order Butterworth low-pass at 0.1 Hz
 * Skin temperature changes very slowly — high-frequency components are noise.
 */
class TempFilter {
    private val filter = Butterworth().apply {
        lowPass(
            E4PacketFormat.TEMP_FILTER_ORDER,
            E4PacketFormat.TEMP_SAMPLE_RATE,
            E4PacketFormat.TEMP_FILTER_CUTOFF_HZ
        )
    }

    fun filter(sample: Float): Float {
        return filter.filter(sample.toDouble()).toFloat()
    }

    fun reset() {
        filter.reset()
    }
}