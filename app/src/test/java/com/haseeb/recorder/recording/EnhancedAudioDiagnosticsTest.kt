package com.haseeb.recorder.recording

import android.media.AudioAttributes
import com.haseeb.recorder.recording.EnhancedAudioDiagnostics.BlockReason
import com.haseeb.recorder.recording.EnhancedAudioDiagnostics.CapabilitySummary
import com.haseeb.recorder.recording.EnhancedAudioDiagnostics.PlaybackSnapshot
import org.junit.Assert.*
import org.junit.Test

class EnhancedAudioDiagnosticsTest {
    private val noPrivileges = CapabilitySummary(false, false, false, false)
    private val shizukuMediaPrivilege = CapabilitySummary(false, false, true, false)
    private val appAudioOutputPrivilege = CapabilitySummary(true, false, false, false)

    @Test fun allowCaptureByNoneIsDetectedEvenWithPrivilegedBackends() {
        val snapshots = listOf(
            PlaybackSnapshot(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        )
        assertEquals(
            BlockReason.ALLOW_CAPTURE_BY_NONE,
            EnhancedAudioDiagnostics.evaluateBlockReason(
                systemAudioRequested = true,
                enhancedAudioEnabled = true,
                hasNonZeroSystemAudio = false,
                snapshots = snapshots,
                capabilities = CapabilitySummary(true, true, true, true),
                privilegedCaptureActive = true
            )
        )
    }

    @Test fun allowCaptureBySystemTriggersPrivilegedFallbackOnlyWhenEligible() {
        val sysProtected = listOf(
            PlaybackSnapshot(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM)
        )
        assertFalse(EnhancedAudioDiagnostics.shouldAttemptPrivilegedFallback(true, false, 500L, sysProtected, false))
        assertTrue(EnhancedAudioDiagnostics.shouldAttemptPrivilegedFallback(true, false, 1_200L, sysProtected, false))
        assertFalse(EnhancedAudioDiagnostics.shouldAttemptPrivilegedFallback(true, true, 1_200L, sysProtected, false))
        assertFalse(EnhancedAudioDiagnostics.shouldAttemptPrivilegedFallback(true, false, 1_200L, sysProtected, true))
        assertFalse(EnhancedAudioDiagnostics.shouldAttemptPrivilegedFallback(false, false, 1_200L, sysProtected, false))
    }

    @Test fun classifiesSystemProtectedAndNonMediaStreamsClearly() {
        val bySystem = listOf(
            PlaybackSnapshot(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM)
        )
        assertEquals(
            BlockReason.PRIVILEGED_BACKEND_UNAVAILABLE,
            EnhancedAudioDiagnostics.evaluateBlockReason(true, true, false, bySystem, noPrivileges, false)
        )
        assertEquals(
            BlockReason.ALLOW_CAPTURE_BY_SYSTEM_NO_PRIVILEGE,
            EnhancedAudioDiagnostics.evaluateBlockReason(true, false, false, bySystem, noPrivileges, false)
        )
        assertEquals(
            BlockReason.ALLOW_CAPTURE_BY_SYSTEM_NO_PRIVILEGE,
            EnhancedAudioDiagnostics.evaluateBlockReason(true, true, false, bySystem, shizukuMediaPrivilege, true)
        )
        val nonMedia = listOf(
            PlaybackSnapshot(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.ALLOW_CAPTURE_BY_ALL)
        )
        assertEquals(
            BlockReason.NON_CAPTURABLE_USAGE,
            EnhancedAudioDiagnostics.evaluateBlockReason(true, true, false, nonMedia, appAudioOutputPrivilege, false)
        )
    }

    @Test fun capabilitySummaryDoesNotReportPrivilegedWithoutRealCapturePermission() {
        assertFalse(noPrivileges.hasAnyPrivilegedCapture)
        assertFalse(noPrivileges.hasAppPrivilegedCapture)
        assertTrue(shizukuMediaPrivilege.hasRemotePrivilegedCapture)
        assertTrue(shizukuMediaPrivilege.hasAnyPrivilegedCapture)
        assertTrue(appAudioOutputPrivilege.hasAppPrivilegedCapture)
    }

    @Test fun nonZeroAudioOrNoSnapshotsProducesNoWarning() {
        assertEquals(
            BlockReason.NONE,
            EnhancedAudioDiagnostics.evaluateBlockReason(
                systemAudioRequested = true,
                enhancedAudioEnabled = true,
                hasNonZeroSystemAudio = true,
                snapshots = listOf(PlaybackSnapshot(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_NONE)),
                capabilities = noPrivileges,
                privilegedCaptureActive = false
            )
        )
        assertEquals(
            BlockReason.NONE,
            EnhancedAudioDiagnostics.evaluateBlockReason(
                systemAudioRequested = true,
                enhancedAudioEnabled = true,
                hasNonZeroSystemAudio = false,
                snapshots = emptyList(),
                capabilities = noPrivileges,
                privilegedCaptureActive = false
            )
        )
    }
}
