package com.haseeb.recorder.recording

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/** Hardware verification tests for VoIP/communication audio coexistence and capture policy detection. */
@RunWith(AndroidJUnit4::class)
class VoipAudioHardwareVerificationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun grantMicPermission() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @Test fun detectsAllowCaptureByNonePolicyOnRealPlaybackStream() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val track = createToneTrack(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        try {
            track.play()
            val snapshots = awaitPlaybackSnapshots(audioManager)
            assertTrue("Expected active playback configuration", snapshots.isNotEmpty())
            val voipIssue = VoipAudioDiagnostics.evaluateIssue(
                systemAudioRequested = true,
                hasNonZeroSystemAudio = false,
                observedCallMode = false,
                snapshots = snapshots.map { VoipAudioDiagnostics.PlaybackEntry(it.first, it.second) }
            )
            assertEquals(VoipAudioDiagnostics.CaptureIssue.APP_CAPTURE_BLOCKED, voipIssue)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    @Test fun standardMicCapturesCleanlyDuringSimulatedVoipCommunicationCallMode() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousMode = audioManager.mode
        val track = createToneTrack(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.ALLOW_CAPTURE_BY_ALL)
        var micRecord: AudioRecord? = null
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            track.play()
            SystemClock.sleep(150)

            val sources = VoipAudioDiagnostics.selectMicrophoneSources(
                simultaneousSystemAudio = true,
                audioMode = audioManager.mode,
                manufacturer = Build.MANUFACTURER ?: "",
                brand = Build.BRAND ?: ""
            )
            assertEquals(MediaRecorder.AudioSource.MIC, sources.first())

            val minBuf = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            assertTrue(minBuf > 0)
            micRecord = AudioRecord(
                sources.first(),
                48_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
            assertEquals(AudioRecord.STATE_INITIALIZED, micRecord.state)
            micRecord.startRecording()

            val buf = ShortArray(960)
            val read = micRecord.read(buf, 0, buf.size)
            assertTrue("Expected positive AudioRecord read during VoIP call, got $read", read > 0)

            val voipIssue = VoipAudioDiagnostics.evaluateIssue(
                systemAudioRequested = true,
                hasNonZeroSystemAudio = false,
                observedCallMode = VoipAudioDiagnostics.isCallAudioMode(audioManager.mode),
                snapshots = emptyList()
            )
            assertEquals(VoipAudioDiagnostics.CaptureIssue.VOICE_CALL_RESTRICTED, voipIssue)
        } finally {
            runCatching { micRecord?.stop() }
            runCatching { micRecord?.release() }
            runCatching { track.stop() }
            runCatching { track.release() }
            runCatching { audioManager.mode = previousMode }
        }
    }

    private fun awaitPlaybackSnapshots(audioManager: AudioManager): List<Pair<Int, Int>> {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val list = audioManager.activePlaybackConfigurations.map {
                it.audioAttributes.usage to it.audioAttributes.allowedCapturePolicy
            }
            if (list.isNotEmpty()) return list
            SystemClock.sleep(80)
        }
        return emptyList()
    }

    private fun createToneTrack(usage: Int, capturePolicy: Int): AudioTrack {
        val rate = 48_000
        val samples = ShortArray(rate / 2) { i ->
            (sin(2.0 * Math.PI * 440.0 * i / rate) * 8000.0).toInt().toShort()
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowedCapturePolicy(capturePolicy)
                }
            }
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .apply {
                write(samples, 0, samples.size)
                setLoopPoints(0, samples.size, -1)
            }
    }
}
