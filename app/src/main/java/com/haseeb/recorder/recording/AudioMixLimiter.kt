package com.haseeb.recorder.recording

import kotlin.math.abs

/** Attenuate overlapping peaks together instead of flattening them with hard clipping. */
internal class AudioMixLimiter {
    private var gain = 1.0

    fun mix(mic: ShortArray, micCount: Int, system: ShortArray, systemCount: Int, output: ByteArray, muted: Boolean) {
        val samples = output.size / 2
        var peak = 0
        for (i in 0 until samples) peak = maxOf(peak, abs(sample(mic, micCount, system, systemCount, i)))
        val requiredGain = if (peak > Short.MAX_VALUE) Short.MAX_VALUE.toDouble() / peak else 1.0
        // Immediate attack protects this frame; gradual release avoids pumping between adjacent frames.
        gain = minOf(requiredGain, gain + 0.02)
        for (i in 0 until samples) {
            val value = if (muted) 0 else (sample(mic, micCount, system, systemCount, i) * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[i * 2] = value.toByte()
            output[i * 2 + 1] = (value shr 8).toByte()
        }
    }

    private fun sample(mic: ShortArray, micCount: Int, system: ShortArray, systemCount: Int, i: Int): Int =
        (if (i < micCount) mic[i].toInt() else 0) + (if (i < systemCount) system[i].toInt() else 0)
}
