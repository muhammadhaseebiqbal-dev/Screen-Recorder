package com.haseeb.recorder.recording

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/** Process the microphone session only; digital playback must retain its original audio. */
internal class MicrophoneEffects(sessionId: Int, cancelEcho: Boolean) : AutoCloseable {
    private val effects = mutableListOf<AudioEffect>()

    init {
        enable("noise suppression") {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
        }
        if (cancelEcho) enable("echo cancellation") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
        }
    }

    private fun enable(name: String, create: () -> AudioEffect?) {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect == null) {
                Log.i(ScreenRecordService.TAG, "Microphone $name unavailable")
                return
            }
            if (effect.setEnabled(true) == AudioEffect.SUCCESS) {
                effects.add(effect)
                Log.i(ScreenRecordService.TAG, "Microphone $name enabled")
            } else {
                effect.release()
                Log.w(ScreenRecordService.TAG, "Microphone $name could not be enabled")
            }
        } catch (e: RuntimeException) {
            try { effect?.release() } catch (_: RuntimeException) {}
            Log.w(ScreenRecordService.TAG, "Microphone $name unavailable; capture continues", e)
        }
    }

    override fun close() {
        effects.forEach { try { it.release() } catch (_: RuntimeException) {} }
        effects.clear()
    }
}
