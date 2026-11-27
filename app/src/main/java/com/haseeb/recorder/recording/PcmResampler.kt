package com.haseeb.recorder.recording

import kotlin.math.roundToInt

/**
 * Stateful mono PCM16 resampler and buffer helpers for converting 16 kHz privileged playback
 * captures to the encoder's 48 kHz timeline without pitch or timing drift.
 */
internal class PcmResampler(
    private val inputRate: Int,
    private val outputRate: Int
) {
    private var previousSample: Short = 0
    private var hasPrevious = false
    private var fracPos = 0.0

    init {
        require(inputRate > 0 && outputRate > 0) { "Sample rates must be positive" }
    }

    fun reset() {
        previousSample = 0
        hasPrevious = false
        fracPos = 0.0
    }

    /**
     * Converts [inputCount] samples starting at [inputOffset] from [input] into linear
     * interpolated output samples at [outputRate].
     */
    fun process(input: ShortArray, inputOffset: Int = 0, inputCount: Int = input.size - inputOffset): ShortArray {
        if (inputCount <= 0) return ShortArray(0)
        if (inputRate == outputRate) {
            previousSample = input[inputOffset + inputCount - 1]
            hasPrevious = true
            return input.copyOfRange(inputOffset, inputOffset + inputCount)
        }

        val step = inputRate.toDouble() / outputRate.toDouble()
        val prev = if (hasPrevious) previousSample else input[inputOffset]
        val estimatedOut = ((inputCount / step) + 2).toInt().coerceAtLeast(1)
        val out = ShortArray(estimatedOut)
        var outCount = 0

        // Timeline relative to current chunk: -1 maps to prev, 0..inputCount-1 map to input.
        var pos = -1.0 + fracPos
        if (!hasPrevious) {
            pos = 0.0
        }
        val limit = (inputCount - 1).toDouble()
        while (pos <= limit + 1e-9 && outCount < out.size) {
            val baseIdx = kotlin.math.floor(pos).toInt()
            val alpha = (pos - baseIdx).coerceIn(0.0, 1.0)
            val s0 = if (baseIdx < 0) prev.toDouble() else input[inputOffset + baseIdx.coerceIn(0, inputCount - 1)].toDouble()
            val nextIdx = baseIdx + 1
            val s1 = if (nextIdx < 0) prev.toDouble() else input[inputOffset + nextIdx.coerceIn(0, inputCount - 1)].toDouble()
            val sample = (s0 + (s1 - s0) * alpha).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[outCount++] = sample.toShort()
            pos += step
        }

        fracPos = (pos - limit).coerceIn(0.0, 1.0)
        previousSample = input[inputOffset + inputCount - 1]
        hasPrevious = true
        return if (outCount == out.size) out else out.copyOf(outCount)
    }

    companion object {
        fun containsNonZeroSamples(buffer: ShortArray, offset: Int = 0, count: Int = buffer.size - offset): Boolean {
            val end = (offset + count).coerceAtMost(buffer.size)
            for (i in offset.coerceAtLeast(0) until end) {
                if (buffer[i].toInt() != 0) return true
            }
            return false
        }
    }
}
