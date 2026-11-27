package com.haseeb.recorder.recording

import android.media.AudioAttributes
import android.media.MediaRecorder
import com.haseeb.recorder.recording.SystemAudioPolicy.Diagnostic
import com.haseeb.recorder.recording.SystemAudioPolicy.PlaybackSnapshot
import org.junit.Assert.*
import org.junit.Test

class SystemAudioPolicyTest {
    @Test fun avoidsVoiceCommunicationOnHyperOsAndXiaomiDevicesWhenSystemAudioActive() {
        val sources = SystemAudioPolicy.microphoneSources(
            simultaneousSystemAudio = true,
            manufacturer = "Xiaomi",
            brand = "Redmi"
        )
        assertArrayEquals(intArrayOf(MediaRecorder.AudioSource.MIC), sources)
    }

    @Test fun usesVoiceCommunicationWithMicFallbackOnGenericDevicesWhenNotForced() {
        val sources = SystemAudioPolicy.microphoneSources(
            simultaneousSystemAudio = true,
            manufacturer = "Google",
            brand = "google"
        )
        assertArrayEquals(
            intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC),
            sources
        )
    }

    @Test fun forcedStandardMicOverridesVoiceCommunication() {
        val sources = SystemAudioPolicy.microphoneSources(
            simultaneousSystemAudio = true,
            manufacturer = "Google",
            brand = "google",
            forceStandardMic = true
        )
        assertArrayEquals(intArrayOf(MediaRecorder.AudioSource.MIC), sources)
    }

    @Test fun triggersDynamicFallbackWhenVoiceCommunicationSilencesCapturablePlayback() {
        val activeMedia = listOf(PlaybackSnapshot(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_ALL))
        assertFalse(SystemAudioPolicy.shouldFallbackToStandardMic(true, 1_500L, false, activeMedia))
        assertTrue(SystemAudioPolicy.shouldFallbackToStandardMic(true, 2_500L, false, activeMedia))
        assertFalse(SystemAudioPolicy.shouldFallbackToStandardMic(true, 2_500L, true, activeMedia))
        assertFalse(SystemAudioPolicy.shouldFallbackToStandardMic(false, 2_500L, false, activeMedia))
    }

    @Test fun classifiesUnavailableRestrictedAndNonMediaPlaybackCorrectly() {
        assertEquals(
            Diagnostic.NONE,
            SystemAudioPolicy.evaluateDiagnostic(false, false, false, emptyList())
        )
        assertEquals(
            Diagnostic.UNAVAILABLE,
            SystemAudioPolicy.evaluateDiagnostic(true, false, false, emptyList())
        )
        assertEquals(
            Diagnostic.RESTRICTED_BY_APP,
            SystemAudioPolicy.evaluateDiagnostic(
                true, true, false,
                listOf(PlaybackSnapshot(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_NONE))
            )
        )
        assertEquals(
            Diagnostic.NON_MEDIA_USAGE,
            SystemAudioPolicy.evaluateDiagnostic(
                true, true, false,
                listOf(PlaybackSnapshot(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.ALLOW_CAPTURE_BY_ALL))
            )
        )
        assertEquals(
            Diagnostic.NONE,
            SystemAudioPolicy.evaluateDiagnostic(
                true, true, true,
                listOf(PlaybackSnapshot(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_NONE))
            )
        )
    }
}
