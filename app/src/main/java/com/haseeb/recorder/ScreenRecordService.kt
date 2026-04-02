package com.haseeb.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {

    companion object {
        const val TAG = "ScreenRecordService"
        const val CHANNEL_ID = "screen_record_channel"
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_ID_DONE = 2
        const val ACTION_START = "com.haseeb.recorder.ACTION_START"
        const val ACTION_STOP = "com.haseeb.recorder.ACTION_STOP"
        const val ACTION_PAUSE = "com.haseeb.recorder.ACTION_PAUSE"
        const val ACTION_RESUME = "com.haseeb.recorder.ACTION_RESUME"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        var isRecording = false
            private set
        var isPaused = false
            private set

        var isMicEnabled = false
        var isInternalAudioEnabled = false

        interface AudioStateListener { fun onAudioStateChanged() }
        var audioStateListener: AudioStateListener? = null
    }

    // ── Audio quality constants ──────────────────────────────────
    private val SAMPLE_RATE = 48000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val CHANNEL_COUNT = 1
    private val AUDIO_BIT_RATE = 320_000   // 320 kbps AAC

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var outputFilePath: String = ""
    private var outputFileDescriptor: android.os.ParcelFileDescriptor? = null
    private var outputMediaStoreUri: Uri? = null

    // Unified MediaCodec pipeline
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var inputSurface: Surface? = null

    // Audio sources
    private var micRecord: AudioRecord? = null
    private var sysRecord: AudioRecord? = null

    @Volatile private var audioRecordingThread: Thread? = null
    @Volatile private var videoEncoderThread: Thread? = null
    @Volatile private var stopRequested = false

    // Whether we need any audio at all
    private var hasAnyAudio = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system")
            mainHandler.post {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        if (screenWidth % 2 != 0) screenWidth--
        if (screenHeight % 2 != 0) screenHeight--
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data != null) {
                    val hasAudioPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val wantsAudio = (isMicEnabled || isInternalAudioEnabled) && hasAudioPerm

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        var fgsTypes = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        if (wantsAudio) {
                            fgsTypes = fgsTypes or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        }
                        startForeground(NOTIFICATION_ID, buildRecordingNotification(), fgsTypes)
                    } else {
                        startForeground(NOTIFICATION_ID, buildRecordingNotification())
                    }
                    startRecording(resultCode, data)
                }
            }
            ACTION_PAUSE -> {
                pauseRecording()
            }
            ACTION_RESUME -> {
                resumeRecording()
            }
            ACTION_STOP -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun pauseRecording() {
        if (!isRecording || isPaused) return
        isPaused = true
        Log.d(TAG, "Recording paused — data will be discarded until resumed")
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        isPaused = false
        Log.d(TAG, "Recording resumed")
    }

    // ═══════════════════════════════════════════════════════════════
    //  START RECORDING — Unified MediaCodec pipeline
    // ═══════════════════════════════════════════════════════════════
    private fun startRecording(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, mainHandler)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ScreenRecord_$timestamp.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Scoped storage: use MediaStore
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ScreenRecorder")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                val pfd = contentResolver.openFileDescriptor(uri, "rw")
                if (pfd != null) {
                    outputFilePath = uri.toString()
                    outputFileDescriptor = pfd
                    outputMediaStoreUri = uri
                }
            }
            // Fallback if MediaStore fails
            if (outputFileDescriptor == null) {
                val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val recorderDir = File(dcimDir, "ScreenRecorder")
                if (!recorderDir.exists()) recorderDir.mkdirs()
                outputFilePath = File(recorderDir, fileName).absolutePath
            }
        } else {
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val recorderDir = File(dcimDir, "ScreenRecorder")
            if (!recorderDir.exists()) recorderDir.mkdirs()
            outputFilePath = File(recorderDir, fileName).absolutePath
        }

        val hasMicPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val wantMic = isMicEnabled && hasMicPerm
        val wantSysAudio = isInternalAudioEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        hasAnyAudio = wantMic || wantSysAudio

        stopRequested = false

        try {
            // ── Video encoder ────────────────────────────────────────
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            // ── Audio encoder (only if we need audio) ────────────────
            if (hasAnyAudio) {
                val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
            }

            // ── Mic AudioRecord ──────────────────────────────────────
            if (wantMic) {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                micRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBuf * 4
                )
            }

            // ── System audio AudioRecord (API 29+) ──────────────────
            if (wantSysAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                sysRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .build()
            }

            // ── MediaMuxer ───────────────────────────────────────────
            mediaMuxer = if (outputFileDescriptor != null) {
                MediaMuxer(outputFileDescriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
            videoTrackIndex = -1
            audioTrackIndex = -1
            muxerStarted = false

            // ── Virtual Display → video encoder ──────────────────────
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecorder",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            // ── Start audio capture + encoding thread ────────────────
            if (hasAnyAudio) {
                micRecord?.startRecording()
                sysRecord?.startRecording()
                audioRecordingThread = Thread({ drainAudioEncoder() }, "AudioEncoderThread").apply { start() }
            }

            // ── Start video drain thread ─────────────────────────────
            videoEncoderThread = Thread({ drainVideoEncoder() }, "VideoEncoderThread").apply { start() }

            isRecording = true
            Log.d(TAG, "Recording started → $outputFilePath (mic=$wantMic, sysAudio=$wantSysAudio)")
            startService(Intent(this, RecordingOverlayService::class.java))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AUDIO: Dual-source PCM mixer → AAC encoder → muxer
    // ═══════════════════════════════════════════════════════════════
    private fun drainAudioEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoder = audioEncoder ?: return
        val frameSamples = 1024        // AAC frame size
        val frameBytes = frameSamples * 2  // 16-bit PCM → 2 bytes per sample

        val micBuf = ShortArray(frameSamples)
        val sysBuf = ShortArray(frameSamples)
        val mixBuf = ShortArray(frameSamples)
        val tmpBytes = ByteArray(frameBytes)

        val hasMic = micRecord != null
        val hasSys = sysRecord != null

        while (!stopRequested) {
            // Always read from sources to prevent buffer overrun
            val micSamples = if (hasMic) micRecord!!.read(micBuf, 0, frameSamples) else 0
            val sysSamples = if (hasSys) sysRecord!!.read(sysBuf, 0, frameSamples) else 0

            // When paused, discard the audio data
            if (isPaused) continue

            val validSamples = maxOf(micSamples, sysSamples, 0)
            if (validSamples <= 0) continue

            // ── Mix ──────────────────────────────────────────────────
            for (i in 0 until validSamples) {
                val mVal = if (hasMic && i < micSamples) micBuf[i].toInt() else 0
                val sVal = if (hasSys && i < sysSamples) sysBuf[i].toInt() else 0
                // Clamp to Short range to prevent distortion
                mixBuf[i] = (mVal + sVal).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            // ── Convert ShortArray → ByteArray (little endian) ──────
            val bb = ByteBuffer.wrap(tmpBytes).order(ByteOrder.LITTLE_ENDIAN)
            bb.clear()
            for (i in 0 until validSamples) bb.putShort(mixBuf[i])

            val pcmByteCount = validSamples * 2

            // ── Feed PCM to AAC encoder ──────────────────────────────
            val inputIndex = encoder.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuf = encoder.getInputBuffer(inputIndex)!!
                inputBuf.clear()
                inputBuf.put(tmpBytes, 0, pcmByteCount)
                encoder.queueInputBuffer(inputIndex, 0, pcmByteCount, System.nanoTime() / 1000, 0)
            }

            // ── Drain encoded AAC output → muxer ─────────────────────
            drainEncoderOutput(encoder, bufferInfo, isAudio = true)
        }

        // Signal EOS
        val eosIndex = encoder.dequeueInputBuffer(10_000)
        if (eosIndex >= 0) {
            encoder.queueInputBuffer(eosIndex, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainEncoderOutput(encoder, bufferInfo, isAudio = true, untilEos = true)
    }

    // ═══════════════════════════════════════════════════════════════
    //  VIDEO: Surface encoder → muxer
    // ═══════════════════════════════════════════════════════════════
    private fun drainVideoEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoder = videoEncoder ?: return

        while (!stopRequested) {
            // Always drain to prevent encoder backup (VirtualDisplay keeps sending frames)
            drainEncoderOutput(encoder, bufferInfo, isAudio = false)
            // Small sleep to avoid busy-looping
            try { Thread.sleep(5) } catch (_: InterruptedException) {}
        }

        // Signal EOS for video
        encoder.signalEndOfInputStream()
        drainEncoderOutput(encoder, bufferInfo, isAudio = false, untilEos = true)
    }

    // ═══════════════════════════════════════════════════════════════
    //  SHARED: Drain encoded output from a MediaCodec into the muxer
    // ═══════════════════════════════════════════════════════════════
    private fun drainEncoderOutput(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        isAudio: Boolean,
        untilEos: Boolean = false
    ) {
        val timeout = if (untilEos) 10_000L else 0L

        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeout)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(this) {
                        if (isAudio) {
                            audioTrackIndex = mediaMuxer!!.addTrack(encoder.outputFormat)
                        } else {
                            videoTrackIndex = mediaMuxer!!.addTrack(encoder.outputFormat)
                        }
                        maybeStartMuxer()
                    }
                }
                outputIndex >= 0 -> {
                    val outputBuf = encoder.getOutputBuffer(outputIndex)
                    val isCodecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

                    if (outputBuf == null || isCodecConfig) {
                        // Nothing useful in this buffer — release and move on
                        encoder.releaseOutputBuffer(outputIndex, false)
                    } else {
                        synchronized(this) {
                            if (muxerStarted && bufferInfo.size > 0 && !isPaused) {
                                outputBuf.position(bufferInfo.offset)
                                outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val trackIdx = if (isAudio) audioTrackIndex else videoTrackIndex
                                mediaMuxer?.writeSampleData(trackIdx, outputBuf, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
                else -> {
                    return
                }
            }
        }
    }

    @Synchronized
    private fun maybeStartMuxer() {
        val needAudio = hasAnyAudio
        val audioReady = !needAudio || audioTrackIndex >= 0
        if (!muxerStarted && videoTrackIndex >= 0 && audioReady) {
            mediaMuxer?.start()
            muxerStarted = true
            Log.d(TAG, "MediaMuxer started (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex)")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  STOP
    // ═══════════════════════════════════════════════════════════════
    private fun stopRecording() {
        if (!isRecording) return

        stopRequested = true
        isPaused = false

        // Stop audio sources
        try { micRecord?.stop() } catch (_: Exception) {}
        try { sysRecord?.stop() } catch (_: Exception) {}

        // Wait for threads to finish
        try { audioRecordingThread?.join(5000) } catch (_: Exception) {}
        try { videoEncoderThread?.join(5000) } catch (_: Exception) {}

        try { mediaMuxer?.stop() } catch (e: Exception) {
            Log.e(TAG, "Error stopping muxer", e)
        }

        cleanup()

        // Handle save notification
        if (outputMediaStoreUri != null) {
            // MediaStore path — file is already visible
            val uri = outputMediaStoreUri!!
            mainHandler.post {
                Toast.makeText(this, "Recording saved to DCIM/ScreenRecorder", Toast.LENGTH_LONG).show()
                showSavedNotification("ScreenRecord.mp4", uri)
            }
        } else {
            val file = File(outputFilePath)
            if (file.exists() && file.length() > 0L) {
                MediaScannerConnection.scanFile(this, arrayOf(outputFilePath), arrayOf("video/mp4")) { _, uri ->
                    Log.d(TAG, "Media scan complete, uri=$uri")
                    mainHandler.post {
                        Toast.makeText(this, "Recording saved to DCIM/ScreenRecorder", Toast.LENGTH_LONG).show()
                        if (uri != null) showSavedNotification(file.name, uri)
                    }
                }
            } else {
                file.delete()
                mainHandler.post {
                    Toast.makeText(this, "Recording failed — no video data captured", Toast.LENGTH_LONG).show()
                }
            }
        }

        isRecording = false
        outputMediaStoreUri = null
        Log.d(TAG, "Recording stopped → $outputFilePath")
        stopService(Intent(this, RecordingOverlayService::class.java))
    }

    private fun cleanup() {
        // Audio sources
        micRecord?.release()
        micRecord = null
        sysRecord?.release()
        sysRecord = null

        // Encoders
        try { audioEncoder?.stop() } catch (_: Exception) {}
        audioEncoder?.release()
        audioEncoder = null
        try { videoEncoder?.stop() } catch (_: Exception) {}
        videoEncoder?.release()
        videoEncoder = null

        inputSurface?.release()
        inputSurface = null
        try { mediaMuxer?.release() } catch (_: Exception) {}
        mediaMuxer = null
        muxerStarted = false

        // Close file descriptor
        try { outputFileDescriptor?.close() } catch (_: Exception) {}
        outputFileDescriptor = null

        // Common
        virtualDisplay?.release()
        virtualDisplay = null
        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        mediaProjection?.stop()
        mediaProjection = null
    }

    /** Notification visible during recording with a Stop button. */
    private fun buildRecordingNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Recording")
            .setContentText("Tap Stop to finish")
            .setSmallIcon(R.drawable.ic_screen_record)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setContentIntent(stopPendingIntent)
            .build()
    }

    /** Notification shown after recording saved — click to open in video player. */
    private fun showSavedNotification(fileName: String, videoUri: Uri) {
        val playIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videoUri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingPlay = PendingIntent.getActivity(
            this, 3, playIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingApp = PendingIntent.getActivity(
            this, 4, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording saved")
            .setContentText(fileName)
            .setSmallIcon(R.drawable.ic_screen_record)
            .setAutoCancel(true)
            .setContentIntent(pendingPlay)
            .addAction(R.drawable.ic_screen_record, "Open App", pendingApp)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID_DONE, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Screen Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Active screen recording notification"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
