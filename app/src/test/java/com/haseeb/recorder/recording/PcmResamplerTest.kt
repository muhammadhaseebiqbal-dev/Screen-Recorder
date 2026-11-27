package com.haseeb.recorder.recording

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PcmResamplerTest {
    @Test fun upsamples16kTo48kWithAccurateLengthAndDcPreservation() {
        val resampler = PcmResampler(16_000, 48_000)
        val input = ShortArray(320) { 12_000 }
        val out = resampler.process(input)
        // 320 samples @ 16kHz (~20 ms) -> ~960 samples @ 48kHz
        assertTrue("Expected ~960 output samples, got ${out.size}", out.size in 958..962)
        for (s in out) {
            assertTrue("DC value drifted: $s", abs(s.toInt() - 12_000) <= 2)
        }
    }

    @Test fun preservesContinuityAcrossSequentialChunks() {
        val resampler = PcmResampler(16_000, 48_000)
        val chunk1 = ShortArray(160) { i -> (i * 100).toShort() }
        val chunk2 = ShortArray(160) { i -> ((160 + i) * 100).toShort() }
        val out1 = resampler.process(chunk1)
        val out2 = resampler.process(chunk2)
        assertTrue(out1.isNotEmpty() && out2.isNotEmpty())
        val diffAtBoundary = abs(out2.first().toInt() - out1.last().toInt())
        assertTrue("Unexpected discontinuity at chunk boundary: $diffAtBoundary", diffAtBoundary < 250)
    }

    @Test fun passthroughWhenRatesMatch() {
        val resampler = PcmResampler(48_000, 48_000)
        val input = shortArrayOf(10, -20, 30, -40)
        val out = resampler.process(input)
        assertArrayEquals(input, out)
    }

    @Test fun containsNonZeroSamplesDetectsSignalWithinSlice() {
        val buf = shortArrayOf(0, 0, 15, 0, 0)
        assertFalse(PcmResampler.containsNonZeroSamples(buf, 0, 2))
        assertTrue(PcmResampler.containsNonZeroSamples(buf, 1, 3))
        assertFalse(PcmResampler.containsNonZeroSamples(ShortArray(10), 0, 10))
    }
}
