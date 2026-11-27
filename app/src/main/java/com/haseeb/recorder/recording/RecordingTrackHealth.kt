package com.haseeb.recorder.recording

/**
 * Monitors video/audio sample timestamps written to the muxer to guard against audio-only
 * tails during shutdown and surface dropped/stalled video warnings.
 */
internal class RecordingTrackHealth(
    private val videoGapWarningUs: Long = DEFAULT_VIDEO_GAP_WARNING_US,
    private val audioTailWarningUs: Long = DEFAULT_AUDIO_TAIL_WARNING_US,
    private val audioTailGuardUs: Long = DEFAULT_AUDIO_TAIL_GUARD_US
) {
    enum class Warning { NONE, VIDEO_STALLED, AUDIO_ONLY_TAIL }

    companion object {
        const val DEFAULT_VIDEO_GAP_WARNING_US = 2_000_000L
        const val DEFAULT_AUDIO_TAIL_WARNING_US = 1_500_000L
        const val DEFAULT_AUDIO_TAIL_GUARD_US = 500_000L
    }

    var videoSampleCount: Long = 0L
        private set
    var audioSampleCount: Long = 0L
        private set
    var lastVideoPtsUs: Long = -1L
        private set
    var lastAudioPtsUs: Long = -1L
        private set
    var maxVideoGapUs: Long = 0L
        private set

    fun reset() {
        videoSampleCount = 0L
        audioSampleCount = 0L
        lastVideoPtsUs = -1L
        lastAudioPtsUs = -1L
        maxVideoGapUs = 0L
    }

    fun recordVideoSample(ptsUs: Long) {
        if (lastVideoPtsUs >= 0L) {
            val gap = ptsUs - lastVideoPtsUs
            if (gap > maxVideoGapUs) maxVideoGapUs = gap
        }
        lastVideoPtsUs = ptsUs
        videoSampleCount++
    }

    fun recordAudioSample(ptsUs: Long) {
        lastAudioPtsUs = ptsUs
        audioSampleCount++
    }

    fun shouldDropTrailingAudioSample(ptsUs: Long, stopRequested: Boolean): Boolean {
        return stopRequested && lastVideoPtsUs >= 0L && ptsUs > lastVideoPtsUs + audioTailGuardUs
    }

    fun evaluate(muxerStarted: Boolean, hasAudio: Boolean): Warning {
        if (!muxerStarted) return Warning.NONE
        if (videoSampleCount == 0L || maxVideoGapUs >= videoGapWarningUs) {
            return Warning.VIDEO_STALLED
        }
        if (hasAudio && audioSampleCount > 0L && lastAudioPtsUs - lastVideoPtsUs >= audioTailWarningUs) {
            return Warning.AUDIO_ONLY_TAIL
        }
        return Warning.NONE
    }
}
