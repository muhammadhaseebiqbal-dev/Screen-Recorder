package com.haseeb.recorder.recording

import org.junit.Assert.*
import org.junit.Test

class AudioMixLimiterTest {
    private fun decode(pcm: ByteArray) = List(pcm.size / 2) {
        ((pcm[it * 2].toInt() and 255) or (pcm[it * 2 + 1].toInt() shl 8)).toShort().toInt()
    }
    @Test fun overlappingPeaksKeepWaveformInsteadOfClipping() {
        val pcm = ByteArray(8)
        val source = shortArrayOf(30000, 15000, -15000, -30000)
        AudioMixLimiter().mix(source, 4, source, 4, pcm, false)
        assertEquals(listOf(32767, 16383, -16383, -32767), decode(pcm))
    }
    @Test fun quietSourcesKeepOriginalLevels() {
        val pcm = ByteArray(4)
        AudioMixLimiter().mix(shortArrayOf(100, -200), 2, shortArrayOf(200, -100), 2, pcm, false)
        assertEquals(listOf(300, -300), decode(pcm))
    }
    @Test fun missingSourcesDoNotReplayPreviousSamples() {
        val pcm = ByteArray(4)
        AudioMixLimiter().mix(shortArrayOf(100, 32000), 1, shortArrayOf(200, 32000), 0, pcm, false)
        assertEquals(listOf(100, 0), decode(pcm))
    }
    @Test fun muteProducesSilence() {
        val pcm = ByteArray(4)
        val source = shortArrayOf(30000, -30000)
        AudioMixLimiter().mix(source, 2, source, 2, pcm, true)
        assertEquals(listOf(0, 0), decode(pcm))
    }
    @Test fun attenuationRecoversGraduallyAfterPeak() {
        val limiter = AudioMixLimiter()
        val pcm = ByteArray(2)
        limiter.mix(shortArrayOf(30000), 1, shortArrayOf(30000), 1, pcm, false)
        limiter.mix(shortArrayOf(1000), 1, shortArrayOf(0), 0, pcm, false)
        assertTrue(decode(pcm)[0] in 540..570)
        repeat(30) { limiter.mix(shortArrayOf(1000), 1, shortArrayOf(0), 0, pcm, false) }
        assertEquals(1000, decode(pcm)[0])
    }
}
