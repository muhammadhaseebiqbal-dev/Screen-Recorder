package com.haseeb.recorder.recording

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import com.haseeb.recorder.recording.VoipAudioDiagnostics.CaptureIssue
import com.haseeb.recorder.recording.VoipAudioDiagnostics.PlaybackEntry
import com.haseeb.recorder.recording.VoipAudioDiagnostics.VoipCapability
import org.junit.Assert.*
import org.junit.Test

class VoipAudioDiagnosticsTest {
    @Test fun usesStandardMicOnXiaomiRedmiDevicesAndDuringVoipCalls() {
        assertArrayEquals(
            intArrayOf(MediaRecorder.AudioSource.MIC),
            VoipAudioDiagnostics.selectMicrophoneSources(
                simultaneousSystemAudio = true,
                audioMode = AudioManager.MODE_NORMAL,
                manufacturer = "Xiaomi",
                brand = "Redmi"
            )
        )
        assertArrayEquals(
            intArrayOf(MediaRecorder.AudioSource.MIC),
            VoipAudioDiagnostics.selectMicrophoneSources(
                simultaneousSystemAudio = true,
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                manufacturer = "Google",
                brand = "google"
            )
        )
        assertArrayEquals(
            intArrayOf(MediaRecorder.AudioSource.MIC),
            VoipAudioDiagnostics.selectMicrophoneSources(
                simultaneousSystemAudio = true,
                audioMode = AudioManager.MODE_IN_CALL,
                manufacturer = "Samsung",
                brand = "samsung"
            )
        )
    }

    @Test fun allowsVoiceCommunicationSourceOnlyOnGenericIdleDevicesWhenNotForced() {
        assertArrayEquals(
            intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC),
            VoipAudioDiagnostics.selectMicrophoneSources(
                simultaneousSystemAudio = true,
                audioMode = AudioManager.MODE_NORMAL,
                manufacturer = "Google",
                brand = "google"
            )
        )
        assertArrayEquals(
            intArrayOf(MediaRecorder.AudioSource.MIC),
            VoipAudioDiagnostics.selectMicrophoneSources(
                simultaneousSystemAudio = true,
                audioMode = AudioManager.MODE_NORMAL,
                manufacturer = "Google",
                brand = "google",
                forceStandardMic = true
            )
        )
    }

    @Test fun includesVoiceCommunicationUsagesOnlyWhenPrivilegedVoiceCommGranted() {
        val unprivileged = VoipAudioDiagnostics.playbackUsagesForCapture(VoipCapability(false, true)).toSet()
        assertFalse(unprivileged.contains(AudioAttributes.USAGE_VOICE_COMMUNICATION))
        assertTrue(unprivileged.contains(AudioAttributes.USAGE_MEDIA))
        assertTrue(unprivileged.contains(AudioAttributes.USAGE_ASSISTANT))

        val privileged = VoipAudioDiagnostics.playbackUsagesForCapture(VoipCapability(true, false)).toSet()
        assertTrue(privileged.contains(AudioAttributes.USAGE_VOICE_COMMUNICATION))
        assertTrue(privileged.contains(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING))
    }

    @Test fun classifiesVoipCallIsolationAndAppOptOutCorrectly() {
        assertEquals(
            CaptureIssue.APP_CAPTURE_BLOCKED,
            VoipAudioDiagnostics.evaluateIssue(
                systemAudioRequested = true,
                hasNonZeroSystemAudio = false,
                observedCallMode = false,
                snapshots = listOf(PlaybackEntry(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_NONE))
            )
        )
        assertEquals(
            CaptureIssue.VOICE_CALL_RESTRICTED,
            VoipAudioDiagnostics.evaluateIssue(
                systemAudioRequested = true,
                hasNonZeroSystemAudio = false,
                observedCallMode = true,
                snapshots = emptyList()
            )
        )
        assertEquals(
            CaptureIssue.VOICE_CALL_RESTRICTED,
            VoipAudioDiagnostics.evaluateIssue(
                systemAudioRequested = true,
                hasNonZeroSystemAudio = false,
                observedCallMode = false,
                snapshots = listOf(PlaybackEntry(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.ALLOW_CAPTURE_BY_ALL))
            )
        )
        assertEquals(
            CaptureIssue.NONE,
            VoipAudioDiagnostics.evaluateIssue(
                systemAudioRequested = true,
                hasNonZeroSystemAudio = true,
                observedCallMode = true,
                snapshots = listOf(PlaybackEntry(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.ALLOW_CAPTURE_BY_NONE))
            )
        )
    }
}
