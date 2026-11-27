package com.haseeb.recorder.recording

import com.haseeb.recorder.recording.RecordingTrackHealth.Warning
import org.junit.Assert.*
import org.junit.Test

class RecordingTrackHealthTest {
    @Test fun healthySynchronizedTracksProduceNoWarning() {
        val health = RecordingTrackHealth()
        for (i in 0..60) {
            val pts = i * 100_000L
            health.recordVideoSample(pts)
            health.recordAudioSample(pts + 20_000L)
        }
        assertEquals(Warning.NONE, health.evaluate(muxerStarted = true, hasAudio = true))
    }

    @Test fun detectsLargeInternalVideoGap() {
        val health = RecordingTrackHealth()
        health.recordVideoSample(1_000_000L)
        health.recordVideoSample(3_500_000L)
        health.recordAudioSample(3_500_000L)
        assertEquals(Warning.VIDEO_STALLED, health.evaluate(muxerStarted = true, hasAudio = true))
    }

    @Test fun detectsAudioOnlyTailWhenVideoStoppedEarly() {
        val health = RecordingTrackHealth()
        health.recordVideoSample(0L)
        health.recordVideoSample(1_000_000L)
        health.recordAudioSample(2_800_000L)
        assertEquals(Warning.AUDIO_ONLY_TAIL, health.evaluate(muxerStarted = true, hasAudio = true))
    }

    @Test fun detectsZeroVideoSamplesAfterMuxerStarted() {
        val health = RecordingTrackHealth()
        health.recordAudioSample(1_000_000L)
        assertEquals(Warning.VIDEO_STALLED, health.evaluate(muxerStarted = true, hasAudio = true))
    }

    @Test fun dropsTrailingAudioPastToleranceOnlyDuringShutdown() {
        val health = RecordingTrackHealth()
        health.recordVideoSample(5_000_000L)
        assertFalse(health.shouldDropTrailingAudioSample(6_000_000L, stopRequested = false))
        assertFalse(health.shouldDropTrailingAudioSample(5_400_000L, stopRequested = true))
        assertTrue(health.shouldDropTrailingAudioSample(5_600_000L, stopRequested = true))
    }

    @Test fun videoOnlyRecordingIgnoresMissingAudio() {
        val health = RecordingTrackHealth()
        health.recordVideoSample(0L)
        health.recordVideoSample(100_000L)
        assertEquals(Warning.NONE, health.evaluate(muxerStarted = true, hasAudio = false))
    }
}
