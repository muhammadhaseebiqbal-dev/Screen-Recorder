package com.haseeb.recorder.recording

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder

/**
 * Pure policy helpers for VoIP / messenger audio compatibility (such as WeChat calls and voice
 * playback), microphone HAL coexistence, and post-recording restriction diagnostics.
 */
internal object VoipAudioDiagnostics {
    enum class CaptureIssue {
        NONE,
        VOICE_CALL_RESTRICTED,
        APP_CAPTURE_BLOCKED
    }

    data class PlaybackEntry(
        val usage: Int,
        val allowedCapturePolicy: Int
    )

    data class VoipCapability(
        val hasVoiceCommCapturePermission: Boolean,
        val hasPrivilegedPlaybackPermission: Boolean
    ) {
        val supportsPrivilegedVoipCapture: Boolean
            get() = hasVoiceCommCapturePermission || hasPrivilegedPlaybackPermission
    }

    private val communicationHalSensitiveVendors = setOf(
        "xiaomi", "redmi", "poco", "blackshark",
        "oppo", "realme", "oneplus", "vivo", "iqoo",
        "meizu", "tecno", "infinix"
    )

    fun isCommunicationHalSensitiveVendor(manufacturer: String, brand: String): Boolean {
        val m = manufacturer.trim().lowercase()
        val b = brand.trim().lowercase()
        return m in communicationHalSensitiveVendors || b in communicationHalSensitiveVendors
    }

    fun isCallAudioMode(audioMode: Int): Boolean =
        audioMode == AudioManager.MODE_IN_COMMUNICATION || audioMode == AudioManager.MODE_IN_CALL

    fun isVoiceCommunicationUsage(usage: Int): Boolean =
        usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
            usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING

    fun selectMicrophoneSources(
        simultaneousSystemAudio: Boolean,
        audioMode: Int,
        manufacturer: String,
        brand: String,
        forceStandardMic: Boolean = false
    ): IntArray {
        if (!simultaneousSystemAudio ||
            forceStandardMic ||
            isCallAudioMode(audioMode) ||
            isCommunicationHalSensitiveVendor(manufacturer, brand)
        ) {
            return intArrayOf(MediaRecorder.AudioSource.MIC)
        }
        return intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
    }

    fun playbackUsagesForCapture(capability: VoipCapability): IntArray {
        return if (capability.hasVoiceCommCapturePermission) {
            intArrayOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.USAGE_ASSISTANT,
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
            )
        } else {
            intArrayOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.USAGE_ASSISTANT
            )
        }
    }

    fun evaluateIssue(
        systemAudioRequested: Boolean,
        hasNonZeroSystemAudio: Boolean,
        observedCallMode: Boolean,
        snapshots: List<PlaybackEntry>
    ): CaptureIssue {
        if (!systemAudioRequested || hasNonZeroSystemAudio) return CaptureIssue.NONE
        if (snapshots.any { it.allowedCapturePolicy == AudioAttributes.ALLOW_CAPTURE_BY_NONE }) {
            return CaptureIssue.APP_CAPTURE_BLOCKED
        }
        if (observedCallMode || snapshots.any { isVoiceCommunicationUsage(it.usage) }) {
            return CaptureIssue.VOICE_CALL_RESTRICTED
        }
        return CaptureIssue.NONE
    }
}
