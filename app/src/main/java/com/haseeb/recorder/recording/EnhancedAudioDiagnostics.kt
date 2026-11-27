package com.haseeb.recorder.recording

import android.media.AudioAttributes

/** Pure capability and playback-restriction classification for enhanced internal audio capture. */
internal object EnhancedAudioDiagnostics {
    enum class BlockReason {
        NONE,
        ALLOW_CAPTURE_BY_NONE,
        ALLOW_CAPTURE_BY_SYSTEM_NO_PRIVILEGE,
        NON_CAPTURABLE_USAGE,
        PRIVILEGED_BACKEND_UNAVAILABLE
    }

    data class PlaybackSnapshot(
        val usage: Int,
        val allowedCapturePolicy: Int
    )

    data class CapabilitySummary(
        val appHasCaptureAudioOutput: Boolean,
        val appHasCaptureMediaOutput: Boolean,
        val shizukuRemoteHasCaptureMedia: Boolean,
        val shizukuRemoteHasCaptureAudio: Boolean
    ) {
        val hasAppPrivilegedCapture: Boolean
            get() = appHasCaptureAudioOutput || appHasCaptureMediaOutput

        val hasRemotePrivilegedCapture: Boolean
            get() = shizukuRemoteHasCaptureMedia || shizukuRemoteHasCaptureAudio

        val hasAnyPrivilegedCapture: Boolean
            get() = hasAppPrivilegedCapture || hasRemotePrivilegedCapture
    }

    const val STANDARD_SAMPLE_RATE = 48_000
    const val PRIVILEGED_CAPTURE_SAMPLE_RATE = 16_000

    fun isStandardCapturableUsage(usage: Int): Boolean =
        usage == AudioAttributes.USAGE_MEDIA ||
            usage == AudioAttributes.USAGE_GAME ||
            usage == AudioAttributes.USAGE_UNKNOWN

    fun shouldAttemptPrivilegedFallback(
        enhancedAudioEnabled: Boolean,
        hasNonZeroSystemAudio: Boolean,
        elapsedMs: Long,
        snapshots: List<PlaybackSnapshot>,
        privilegedCaptureAttempted: Boolean
    ): Boolean {
        if (!enhancedAudioEnabled || hasNonZeroSystemAudio || privilegedCaptureAttempted) return false
        if (elapsedMs < 1_000L) return false
        return snapshots.any {
            isStandardCapturableUsage(it.usage) &&
                it.allowedCapturePolicy == AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM
        }
    }

    fun evaluateBlockReason(
        systemAudioRequested: Boolean,
        enhancedAudioEnabled: Boolean,
        hasNonZeroSystemAudio: Boolean,
        snapshots: List<PlaybackSnapshot>,
        capabilities: CapabilitySummary,
        privilegedCaptureActive: Boolean
    ): BlockReason {
        if (!systemAudioRequested || hasNonZeroSystemAudio || snapshots.isEmpty()) {
            return BlockReason.NONE
        }
        val mediaOrGame = snapshots.filter { isStandardCapturableUsage(it.usage) }
        if (mediaOrGame.any { it.allowedCapturePolicy == AudioAttributes.ALLOW_CAPTURE_BY_NONE }) {
            // Android's audio policy blocks ALLOW_CAPTURE_BY_NONE across third-party apps even under Shizuku/Root.
            return BlockReason.ALLOW_CAPTURE_BY_NONE
        }
        if (mediaOrGame.any { it.allowedCapturePolicy == AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM }) {
            return if (!capabilities.hasAnyPrivilegedCapture && !privilegedCaptureActive) {
                if (enhancedAudioEnabled) {
                    BlockReason.PRIVILEGED_BACKEND_UNAVAILABLE
                } else {
                    BlockReason.ALLOW_CAPTURE_BY_SYSTEM_NO_PRIVILEGE
                }
            } else {
                BlockReason.ALLOW_CAPTURE_BY_SYSTEM_NO_PRIVILEGE
            }
        }
        if (mediaOrGame.isEmpty()) {
            return BlockReason.NON_CAPTURABLE_USAGE
        }
        return BlockReason.NONE
    }
}
