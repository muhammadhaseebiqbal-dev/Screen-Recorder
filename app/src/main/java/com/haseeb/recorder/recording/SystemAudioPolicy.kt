package com.haseeb.recorder.recording

import android.media.AudioAttributes
import android.media.MediaRecorder

/** Pure policy helpers for microphone/system-audio coexistence and capture diagnostics. */
internal object SystemAudioPolicy {
    enum class Diagnostic {
        NONE,
        UNAVAILABLE,
        RESTRICTED_BY_APP,
        NON_MEDIA_USAGE
    }

    data class PlaybackSnapshot(
        val usage: Int,
        val allowedCapturePolicy: Int
    )

    private val communicationHalSensitiveVendors = setOf(
        "xiaomi", "redmi", "poco", "blackshark",
        "oppo", "realme", "oneplus", "vivo", "iqoo",
        "meizu", "tecno", "infinix"
    )

    fun isCommunicationHalSensitiveDevice(manufacturer: String, brand: String): Boolean {
        val m = manufacturer.trim().lowercase()
        val b = brand.trim().lowercase()
        return m in communicationHalSensitiveVendors || b in communicationHalSensitiveVendors
    }

    fun microphoneSources(
        simultaneousSystemAudio: Boolean,
        manufacturer: String,
        brand: String,
        forceStandardMic: Boolean = false
    ): IntArray {
        if (!simultaneousSystemAudio || forceStandardMic || isCommunicationHalSensitiveDevice(manufacturer, brand)) {
            return intArrayOf(MediaRecorder.AudioSource.MIC)
        }
        return intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
    }

    fun shouldFallbackToStandardMic(
        usingVoiceCommunication: Boolean,
        elapsedWithAudioMs: Long,
        hasNonZeroSystemAudio: Boolean,
        snapshots: List<PlaybackSnapshot>
    ): Boolean {
        if (!usingVoiceCommunication || hasNonZeroSystemAudio || elapsedWithAudioMs < 2_000L) return false
        return snapshots.any { isCapturableMedia(it) }
    }

    fun evaluateDiagnostic(
        systemAudioRequested: Boolean,
        systemAudioInitialized: Boolean,
        hasNonZeroSystemAudio: Boolean,
        snapshots: List<PlaybackSnapshot>
    ): Diagnostic {
        if (!systemAudioRequested) return Diagnostic.NONE
        if (!systemAudioInitialized) return Diagnostic.UNAVAILABLE
        if (hasNonZeroSystemAudio || snapshots.isEmpty()) return Diagnostic.NONE
        if (snapshots.any { it.allowedCapturePolicy != AudioAttributes.ALLOW_CAPTURE_BY_ALL }) {
            return Diagnostic.RESTRICTED_BY_APP
        }
        if (snapshots.none { isCapturableUsage(it.usage) }) {
            return Diagnostic.NON_MEDIA_USAGE
        }
        return Diagnostic.NONE
    }

    private fun isCapturableMedia(snapshot: PlaybackSnapshot): Boolean =
        isCapturableUsage(snapshot.usage) && snapshot.allowedCapturePolicy == AudioAttributes.ALLOW_CAPTURE_BY_ALL

    fun isCapturableUsage(usage: Int): Boolean =
        usage == AudioAttributes.USAGE_MEDIA ||
            usage == AudioAttributes.USAGE_GAME ||
            usage == AudioAttributes.USAGE_UNKNOWN
}
