package com.haseeb.recorder.recording

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import com.haseeb.recorder.privileged.PrivilegedAccessManager
import com.haseeb.recorder.shizuku.IEnhancedAudioService
import com.haseeb.recorder.shizuku.ShizukuManager
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages privileged 16 kHz mono playback capture (via in-process AudioPlaybackCaptureConfiguration
 * or a Shizuku UserService pipe) and resamples PCM16 frames to the 48 kHz encoder rate.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class PrivilegedAudioCapture private constructor(
    private val inProcessRecord: AudioRecord?,
    private val resampler: PcmResampler,
    private val shizukuConnection: ServiceConnection?,
    private val userServiceArgs: Shizuku.UserServiceArgs?,
    private val remoteService: IEnhancedAudioService?,
    private val readPipe: ParcelFileDescriptor?
) {
    @Volatile private var pipeReaderThread: Thread? = null
    private val ringBuffer = ShortArray(EnhancedAudioDiagnostics.STANDARD_SAMPLE_RATE)
    private var ringRead = 0
    private var ringWrite = 0
    private var ringCount = 0
    private val ringLock = Any()

    private val scratchIn = ShortArray(1024)

    @Volatile private var closed = false

    val sampleRate: Int = EnhancedAudioDiagnostics.PRIVILEGED_CAPTURE_SAMPLE_RATE

    /** Non-blocking read into a 48 kHz target buffer. */
    fun readSamples(target: ShortArray, offset: Int, maxCount: Int): Int {
        if (closed || maxCount <= 0) return 0
        val record = inProcessRecord
        if (record != null) {
            val wantedIn = ((maxCount * resamplerRateRatio()).toInt()).coerceIn(1, scratchIn.size)
            val read = try {
                record.read(scratchIn, 0, wantedIn, AudioRecord.READ_NON_BLOCKING)
            } catch (_: Throwable) {
                return 0
            }
            if (read <= 0) return 0
            val resampled = resampler.process(scratchIn, 0, read)
            val toCopy = resampled.size.coerceAtMost(maxCount)
            System.arraycopy(resampled, 0, target, offset, toCopy)
            if (resampled.size > toCopy) {
                enqueueRing(resampled, toCopy, resampled.size - toCopy)
            }
            return toCopy
        }
        return dequeueRing(target, offset, maxCount)
    }

    private fun resamplerRateRatio(): Double =
        EnhancedAudioDiagnostics.PRIVILEGED_CAPTURE_SAMPLE_RATE.toDouble() /
            EnhancedAudioDiagnostics.STANDARD_SAMPLE_RATE.toDouble()

    private fun enqueueRing(samples: ShortArray, start: Int, count: Int) {
        if (count <= 0) return
        synchronized(ringLock) {
            for (i in 0 until count) {
                if (ringCount == ringBuffer.size) {
                    ringRead = (ringRead + 1) % ringBuffer.size
                    ringCount--
                }
                ringBuffer[ringWrite] = samples[start + i]
                ringWrite = (ringWrite + 1) % ringBuffer.size
                ringCount++
            }
        }
    }

    private fun dequeueRing(target: ShortArray, offset: Int, maxCount: Int): Int {
        synchronized(ringLock) {
            val avail = ringCount.coerceAtMost(maxCount)
            for (i in 0 until avail) {
                target[offset + i] = ringBuffer[ringRead]
                ringRead = (ringRead + 1) % ringBuffer.size
            }
            ringCount -= avail
            return avail
        }
    }

    fun startIfInProcess() {
        inProcessRecord?.startRecording()
    }

    fun stopAndRelease() {
        if (closed) return
        closed = true
        runCatching { inProcessRecord?.stop() }
        runCatching { inProcessRecord?.release() }
        runCatching { remoteService?.stopPrivilegedCapture() }
        runCatching { readPipe?.close() }
        runCatching { pipeReaderThread?.join(300) }
        if (userServiceArgs != null && shizukuConnection != null) {
            ShizukuManager.unbindEnhancedAudioService(userServiceArgs, shizukuConnection)
        }
    }

    private fun startPipePump(pipe: ParcelFileDescriptor) {
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pipe)
        val raw = ByteArray(1280)
        val shorts = ShortArray(640)
        pipeReaderThread = Thread({
            try {
                while (!closed) {
                    val bytesRead = stream.read(raw)
                    if (bytesRead < 0) break
                    val sampleCount = bytesRead / 2
                    for (i in 0 until sampleCount) {
                        val lo = raw[i * 2].toInt() and 0xFF
                        val hi = raw[i * 2 + 1].toInt() shl 8
                        shorts[i] = (hi or lo).toShort()
                    }
                    if (sampleCount > 0) {
                        val up = resampler.process(shorts, 0, sampleCount)
                        enqueueRing(up, 0, up.size)
                    }
                }
            } catch (_: IOException) {
            } finally {
                runCatching { stream.close() }
            }
        }, "PrivilegedPipeReader").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        private const val TAG = "PrivilegedAudioCapture"

        fun tryCreateInProcess(
            mediaProjection: MediaProjection,
            excludeUid: Int
        ): PrivilegedAudioCapture? {
            return try {
                val builder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(excludeUid)

                val mixingRuleBuilderField = AudioPlaybackCaptureConfiguration.Builder::class.java
                    .getDeclaredField("mAudioMixingRuleBuilder").apply { isAccessible = true }
                val mixingRuleBuilder = mixingRuleBuilderField.get(builder)
                mixingRuleBuilder.javaClass
                    .getMethod("allowPrivilegedPlaybackCapture", Boolean::class.javaPrimitiveType)
                    .invoke(mixingRuleBuilder, true)

                val config = builder.build()
                val rate = EnhancedAudioDiagnostics.PRIVILEGED_CAPTURE_SAMPLE_RATE
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)

                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return null
                }
                PrivilegedAudioCapture(
                    inProcessRecord = record,
                    resampler = PcmResampler(rate, EnhancedAudioDiagnostics.STANDARD_SAMPLE_RATE),
                    shizukuConnection = null,
                    userServiceArgs = null,
                    remoteService = null,
                    readPipe = null
                )
            } catch (t: Throwable) {
                Log.w(TAG, "In-process privileged capture unavailable: ${t.message}")
                null
            }
        }

        fun tryCreateShizukuPipe(
            context: Context,
            excludeUid: Int,
            sessionUsesRoot: Boolean
        ): PrivilegedAudioCapture? {
            if (sessionUsesRoot || PrivilegedAccessManager.usesRoot || !ShizukuManager.isPermissionGranted()) {
                return null
            }
            val latch = CountDownLatch(1)
            var remote: IEnhancedAudioService? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    remote = IEnhancedAudioService.Stub.asInterface(service)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    remote = null
                }
            }
            val args = ShizukuManager.bindEnhancedAudioService(context, connection) ?: return null
            val bound = runCatching { latch.await(1_500, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            val svc = remote
            if (!bound || svc == null) {
                ShizukuManager.unbindEnhancedAudioService(args, connection)
                return null
            }
            val pipe = try {
                ParcelFileDescriptor.createPipe()
            } catch (_: IOException) {
                ShizukuManager.unbindEnhancedAudioService(args, connection)
                return null
            }
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            val sampleRate = try {
                val res = svc.startPrivilegedCapture(writeEnd, excludeUid)
                runCatching { writeEnd.close() }
                res
            } catch (t: Throwable) {
                runCatching { writeEnd.close() }
                runCatching { readEnd.close() }
                Log.w(TAG, "Remote startPrivilegedCapture failed: ${t.message}")
                ShizukuManager.unbindEnhancedAudioService(args, connection)
                return null
            }
            if (sampleRate <= 0) {
                val err = runCatching { svc.lastError }.getOrNull()
                Log.w(TAG, "Shizuku privileged AudioPolicy unavailable: $err")
                runCatching { readEnd.close() }
                ShizukuManager.unbindEnhancedAudioService(args, connection)
                return null
            }
            return PrivilegedAudioCapture(
                inProcessRecord = null,
                resampler = PcmResampler(sampleRate, EnhancedAudioDiagnostics.STANDARD_SAMPLE_RATE),
                shizukuConnection = connection,
                userServiceArgs = args,
                remoteService = svc,
                readPipe = readEnd
            ).also { it.startPipePump(readEnd) }
        }
    }
}
