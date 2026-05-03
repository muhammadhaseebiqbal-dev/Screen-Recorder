package com.haseeb.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

/*
 * Professional screen recording service.
 * Dynamically handles resolution scaling and safe automated toggling of device settings.
 */
class ScreenRecordService : Service() {

    companion object {
        const val TAG = "ScreenRecordService"
        const val CHANNEL_ID = "screen_record_channel"
        const val NOTIFICATION_ID = 1
        
        const val ACTION_START = "com.haseeb.recorder.ACTION_START"
        const val ACTION_STOP = "com.haseeb.recorder.ACTION_STOP"
        const val ACTION_PAUSE = "com.haseeb.recorder.ACTION_PAUSE"
        const val ACTION_RESUME = "com.haseeb.recorder.ACTION_RESUME"
        
        const val ACTION_STATE_CHANGED = "com.haseeb.recorder.ACTION_STATE_CHANGED"
        const val EXTRA_STATE = "extra_state"
        const val STATE_START = "state_start"
        const val STATE_STOP = "state_stop"
        const val STATE_PAUSE = "state_pause"
        const val STATE_RESUME = "state_resume"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        @Volatile var isRecording = false
            private set
        @Volatile var isPaused = false
            private set

        @Volatile private var pauseStartTimeUs: Long = 0
        @Volatile private var totalPausedDurationUs: Long = 0
        
        @Volatile private var lastVideoWrittenPtsUs = 0L
        @Volatile private var lastAudioWrittenPtsUs = 0L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var configManager: ConfigManager

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var scaledDensity = 0

    private var originalShowTouchesState = 0
    private var hasChangedShowTouches = false

    private var outputFilePath: String = ""
    private var outputFileDescriptor: android.os.ParcelFileDescriptor? = null
    private var outputMediaStoreUri: Uri? = null

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var inputSurface: Surface? = null

    private var micRecord: AudioRecord? = null
    private var sysRecord: AudioRecord? = null

    @Volatile private var audioRecordingThread: Thread? = null
    @Volatile private var videoEncoderThread: Thread? = null
    @Volatile private var stopRequested = false
    private var hasAnyAudio = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /*
     * Initializes configuration and calculates the exact target resolution and scaled density.
     */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        configManager = ConfigManager(this)

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenDensity = metrics.densityDpi

        val physicalMaxRes = configManager.getMaxSupportedResolution()
        val targetRes = configManager.getScaledResolution()
        
        screenWidth = targetRes.first
        screenHeight = targetRes.second

        // Scale the density based on width reduction to prevent UI elements from appearing massive
        val widthRatio = screenWidth.toFloat() / physicalMaxRes.first.toFloat()
        scaledDensity = (screenDensity * widthRatio).toInt()
    }

    /*
     * Intercepts service commands to control the recording lifecycle.
     */
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
                if (data != null) startRecordingSession(resultCode, data)
            }
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /*
     * Triggers the foreground notification and initializes data before recording starts.
     */
    private fun startRecordingSession(resultCode: Int, data: Intent) {
        val wantsAudio = (configManager.isMicEnabled || configManager.isSystemAudioEnabled)
        val hasAudioPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val canRecordAudio = wantsAudio && hasAudioPerm

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsTypes = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (canRecordAudio) fgsTypes = fgsTypes or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, buildRecordingNotification(), fgsTypes)
        } else {
            startForeground(NOTIFICATION_ID, buildRecordingNotification())
        }

        startService(Intent(this, RecordingOverlayService::class.java))

        totalPausedDurationUs = 0
        lastVideoWrittenPtsUs = 0
        lastAudioWrittenPtsUs = 0
        
        startRecording(resultCode, data)
    }

    /*
     * Sets up encoders, creates the virtual display with calculated resolutions, and starts background threads.
     */
    @SuppressLint("Range")
    private fun startRecording(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, mainHandler)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ScreenRecord_$timestamp.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ScreenRecorder")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                outputMediaStoreUri = it
                outputFileDescriptor = contentResolver.openFileDescriptor(it, "rw")
            }
        } else {
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val recorderDir = File(dcimDir, "ScreenRecorder").apply { if (!exists()) mkdirs() }
            outputFilePath = File(recorderDir, fileName).absolutePath
        }

        val hasMicPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val wantMic = configManager.isMicEnabled && hasMicPerm
        val wantSysAudio = configManager.isSystemAudioEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        hasAnyAudio = wantMic || wantSysAudio
        stopRequested = false

        try {
            val videoMime = if (configManager.useHEVC && isH265Supported()) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

            val videoFormat = MediaFormat.createVideoFormat(videoMime, screenWidth, screenHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, configManager.getOptimalVideoBitrate())
                setInteger(MediaFormat.KEY_FRAME_RATE, configManager.videoFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }

            videoEncoder = MediaCodec.createEncoderByType(videoMime).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            if (hasAnyAudio) setupAudioEncoders(wantMic, wantSysAudio)

            mediaMuxer = if (outputFileDescriptor != null) {
                MediaMuxer(outputFileDescriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                getString(R.string.ScreenRecordService_virtual_display_name),
                screenWidth, screenHeight, scaledDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null
            )

            if (hasAnyAudio) {
                micRecord?.startRecording()
                sysRecord?.startRecording()
                audioRecordingThread = Thread({ drainAudioEncoder() }, "AudioThread").apply { start() }
            }
            videoEncoderThread = Thread({ drainVideoEncoder() }, "VideoThread").apply { start() }

            isRecording = true
            isPaused = false
            sendStateBroadcast(STATE_START)

            applyShowTouchesSettingSafe()

        } catch (e: Exception) {
            Log.e(TAG, "Start failed: ${e.message}")
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /*
     * Safely enables device touch feedback.
     * Prevents crash if system denies permission without WRITE_SETTINGS enabled.
     */
    private fun applyShowTouchesSettingSafe() {
        if (configManager.showTouches) {
            try {
                originalShowTouchesState = Settings.System.getInt(contentResolver, "show_touches", 0)
                Settings.System.putInt(contentResolver, "show_touches", 1)
                hasChangedShowTouches = true
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot enable show_touches automatically without WRITE_SETTINGS permission.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply show_touches setting")
            }
        }
    }

    /*
     * Safely restores the previous state of the device touch feedback.
     */
    private fun restoreShowTouchesSettingSafe() {
        if (hasChangedShowTouches) {
            try {
                Settings.System.putInt(contentResolver, "show_touches", originalShowTouchesState)
                hasChangedShowTouches = false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore show_touches setting")
            }
        }
    }

    /*
     * Configures the audio encoders based on selected sources.
     */
    private fun setupAudioEncoders(wantMic: Boolean, wantSysAudio: Boolean) {
        val sampleRate = 48000
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        if (wantMic) {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            micRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        }

        if (wantSysAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            sysRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        }
    }

    /*
     * Continuously processes and multiplexes audio from system and microphone streams.
     */
    private fun drainAudioEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoder = audioEncoder ?: return
        val frameSamples = 1024
        val micBuf = ShortArray(frameSamples)
        val sysBuf = ShortArray(frameSamples)
        val mixBuf = ShortArray(frameSamples)
        val tmpBytes = ByteArray(frameSamples * 2)

        while (!stopRequested) {
            if (isPaused) {
                SystemClock.sleep(10)
                continue
            }

            val micSamples = micRecord?.read(micBuf, 0, frameSamples) ?: 0
            val sysSamples = sysRecord?.read(sysBuf, 0, frameSamples) ?: 0
            val validSamples = maxOf(micSamples, sysSamples, 0)

            if (validSamples > 0) {
                for (i in 0 until validSamples) {
                    val mVal = if (i < micSamples) micBuf[i].toInt() else 0
                    val sVal = if (i < sysSamples) sysBuf[i].toInt() else 0
                    mixBuf[i] = (mVal + sVal).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }

                val bb = ByteBuffer.wrap(tmpBytes).order(ByteOrder.LITTLE_ENDIAN)
                bb.clear()
                for (i in 0 until validSamples) bb.putShort(mixBuf[i])

                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    encoder.getInputBuffer(inputIndex)?.apply {
                        clear()
                        put(tmpBytes, 0, validSamples * 2)
                        encoder.queueInputBuffer(inputIndex, 0, validSamples * 2, System.nanoTime() / 1000, 0)
                    }
                }
            }
            drainEncoderOutput(encoder, bufferInfo, isAudio = true)
        }
        val eosIndex = encoder.dequeueInputBuffer(10_000)
        if (eosIndex >= 0) encoder.queueInputBuffer(eosIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drainEncoderOutput(encoder, bufferInfo, isAudio = true, untilEos = true)
    }

    /*
     * Triggers the video encoding queue to be written to the muxer continuously.
     */
    private fun drainVideoEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoder = videoEncoder ?: return
        while (!stopRequested) {
            drainEncoderOutput(encoder, bufferInfo, isAudio = false)
            SystemClock.sleep(5)
        }
        encoder.signalEndOfInputStream()
        drainEncoderOutput(encoder, bufferInfo, isAudio = false, untilEos = true)
    }

    /*
     * Dispatches processed audio and video streams efficiently to the final MP4 file.
     */
    private fun drainEncoderOutput(encoder: MediaCodec, bufferInfo: MediaCodec.BufferInfo, isAudio: Boolean, untilEos: Boolean = false) {
        val timeout = if (untilEos) 10_000L else 0L
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeout)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(this) {
                        if (isAudio) audioTrackIndex = mediaMuxer!!.addTrack(encoder.outputFormat)
                        else videoTrackIndex = mediaMuxer!!.addTrack(encoder.outputFormat)
                        if (!muxerStarted && videoTrackIndex >= 0 && (!hasAnyAudio || audioTrackIndex >= 0)) {
                            mediaMuxer?.start()
                            muxerStarted = true
                        }
                    }
                }
                outputIndex >= 0 -> {
                    val outputBuf = encoder.getOutputBuffer(outputIndex)
                    if (outputBuf != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        synchronized(this) {
                            if (muxerStarted && bufferInfo.size > 0) {
                                var pts = bufferInfo.presentationTimeUs - totalPausedDurationUs
                                
                                if (isAudio) {
                                    if (pts <= lastAudioWrittenPtsUs) pts = lastAudioWrittenPtsUs + 10 
                                    lastAudioWrittenPtsUs = pts
                                } else {
                                    if (pts <= lastVideoWrittenPtsUs) pts = lastVideoWrittenPtsUs + 10
                                    lastVideoWrittenPtsUs = pts
                                }
                                
                                bufferInfo.presentationTimeUs = pts
                                try {
                                    mediaMuxer?.writeSampleData(if (isAudio) audioTrackIndex else videoTrackIndex, outputBuf, bufferInfo)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Muxer write failed: ${e.message}")
                                }
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /*
     * Ensures proper shutdown process by terminating operations seamlessly.
     */
    private fun stopRecording() {
        if (!isRecording) return
        stopRequested = true
        isPaused = false

        restoreShowTouchesSettingSafe()
        stopService(Intent(this, RecordingOverlayService::class.java))

        try { micRecord?.stop() } catch (_: Exception) {}
        try { sysRecord?.stop() } catch (_: Exception) {}
        try { audioRecordingThread?.join(1000) } catch (_: Exception) {}
        try { videoEncoderThread?.join(1000) } catch (_: Exception) {}
        try { if (muxerStarted) mediaMuxer?.stop() } catch (_: Exception) {}

        cleanup()
        isRecording = false
        sendStateBroadcast(STATE_STOP)
    }

    /*
     * Thoroughly frees system memory objects required by projection services.
     */
    private fun cleanup() {
        micRecord?.release(); micRecord = null
        sysRecord?.release(); sysRecord = null
        try { audioEncoder?.stop() } catch (_: Exception) {}; audioEncoder?.release(); audioEncoder = null
        try { videoEncoder?.stop() } catch (_: Exception) {}; videoEncoder?.release(); videoEncoder = null
        inputSurface?.release(); inputSurface = null
        try { mediaMuxer?.release() } catch (_: Exception) {}; mediaMuxer = null
        muxerStarted = false
        try { outputFileDescriptor?.close() } catch (_: Exception) {}; outputFileDescriptor = null
        virtualDisplay?.release(); virtualDisplay = null
        mediaProjection?.stop(); mediaProjection = null
    }

    /*
     * Dispatches intent to app components signaling recording phase change.
     */
    private fun sendStateBroadcast(state: String) {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).apply { putExtra(EXTRA_STATE, state) })
    }

    /*
     * Intercepts continuous screen drawing temporarily allowing zero-waste pause mechanisms.
     */
    private fun pauseRecording() {
        if (!isRecording || isPaused) return
        isPaused = true
        pauseStartTimeUs = System.nanoTime() / 1000
        virtualDisplay?.setSurface(null)
        sendStateBroadcast(STATE_PAUSE)
    }

    /*
     * Continues display capture sequence avoiding time jump glitches within final output.
     */
    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        val resumeTimeUs = System.nanoTime() / 1000
        totalPausedDurationUs += (resumeTimeUs - pauseStartTimeUs)
        isPaused = false
        inputSurface?.let { virtualDisplay?.setSurface(it) }
        sendStateBroadcast(STATE_RESUME)
    }

    /*
     * Crafts the continuous system notification to prevent OS-level service termination.
     */
    private fun buildRecordingNotification(): Notification {
        val stopIntent = PendingIntent.getService(this, 0, Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ScreenRecordService_notif_title))
            .setContentText(getString(R.string.ScreenRecordService_notif_text))
            .setSmallIcon(R.drawable.ic_screen_record)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, getString(R.string.ScreenRecordService_notif_btn_stop), stopIntent)
            .build()
    }

    /*
     * Generates a modern Notification Channel mandatory for foreground functionality.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.ScreenRecordService_notif_title), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /*
     * Dynamically verifies codec HEVC compliance on varying target hardware architectures.
     */
    private fun isH265Supported(): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) return true
            }
        }
        return false
    }
}
