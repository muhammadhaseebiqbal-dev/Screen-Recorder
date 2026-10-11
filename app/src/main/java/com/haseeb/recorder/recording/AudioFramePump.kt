package com.haseeb.recorder.recording

/** Collects non-blocking PCM reads without letting either source stall the encoder. */
internal class AudioFramePump(
    private val readMic: (ShortArray, Int, Int) -> Int,
    private val readSystem: (ShortArray, Int, Int) -> Int,
    private val submit: (ByteArray, Long) -> Boolean,
    private val frameSamples: Int = 1024,
    sampleRate: Int = 48_000
) {
    private val mic = ShortArray(frameSamples)
    private val system = ShortArray(frameSamples)
    private val pcm = ByteArray(frameSamples * 2)
    private val limiter = AudioMixLimiter()
    private val frameDurationUs = frameSamples * 1_000_000L / sampleRate
    private var micCount = 0
    private var systemCount = 0
    private var deadlineUs = 0L
    private var pending = false
    private var pendingPtsUs = 0L

    fun step(nowUs: Long, paused: Boolean, muted: Boolean) {
        if (deadlineUs == 0L) deadlineUs = nowUs + frameDurationUs
        if (paused) {
            // Keep draining the capture buffers during a pause; never replay paused audio.
            readMic(mic, 0, frameSamples)
            readSystem(system, 0, frameSamples)
            micCount = 0
            systemCount = 0
            pending = false
            deadlineUs = nowUs + frameDurationUs
            return
        }
        if (!pending) {
            micCount += readAvailable(readMic, mic, micCount)
            systemCount += readAvailable(readSystem, system, systemCount)
            if (nowUs < deadlineUs) return
            limiter.mix(mic, micCount, system, systemCount, pcm, muted)
            pendingPtsUs = nowUs - frameDurationUs
            pending = true
        }
        // Retain a frame while the codec is backpressured instead of dropping its PCM.
        if (submit(pcm, pendingPtsUs)) {
            pending = false
            micCount = 0
            systemCount = 0
            deadlineUs = maxOf(deadlineUs + frameDurationUs, nowUs)
        }
    }

    private fun readAvailable(
        read: (ShortArray, Int, Int) -> Int,
        buffer: ShortArray,
        count: Int
    ): Int {
        if (count == frameSamples) return 0
        return read(buffer, count, frameSamples - count).coerceIn(0, frameSamples - count)
    }
}
