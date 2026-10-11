package com.haseeb.recorder.recording

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
import com.haseeb.recorder.R
import com.haseeb.recorder.camera.CameraOverlayService
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.draw.DrawOverlayService
import com.haseeb.recorder.overlay.RecordingOverlayService
import com.haseeb.recorder.privileged.PrivilegedAccessManager
import com.haseeb.recorder.ui.activity.MainActivity
import com.haseeb.recorder.ui.activity.CameraPermissionActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/*
 * Professional screen recording service.
 * Handles display capture, hardware encoding, audio multiplexing, and lock screen lifecycle.
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
        const val ACTION_TOGGLE_MUTE = "com.haseeb.recorder.ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_CAMERA = "com.haseeb.recorder.ACTION_TOGGLE_CAMERA"
        const val ACTION_REFRESH_CAMERA = "com.haseeb.recorder.ACTION_REFRESH_CAMERA"
        const val ACTION_TOGGLE_DRAW = "com.haseeb.recorder.ACTION_TOGGLE_DRAW"
        
        const val ACTION_STATE_CHANGED = "com.haseeb.recorder.ACTION_STATE_CHANGED"
        const val EXTRA_STATE = "extra_state"
        const val STATE_START = "state_start"
        const val STATE_STOP = "state_stop"
        const val STATE_PAUSE = "state_pause"
        const val STATE_RESUME = "state_resume"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        @Volatile var isStarting = false
            private set
        @Volatile var isRecording = false
            private set
        @Volatile var isPaused = false
            private set
        @Volatile var isMuted = false
            private set

        @Volatile private var pauseStartTimeUs: Long = 0
        @Volatile private var totalPausedDurationUs: Long = 0
        
        @Volatile private var lastVideoWrittenPtsUs = 0L
        @Volatile private var lastAudioWrittenPtsUs = 0L
    }

    @Volatile private var serviceDestroyed = false
    private var startupPending = false
    private var sessionUsesRoot = false
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var configManager: ConfigManager

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var scaledDensity = 0

    private val showTouchesOverride = ShowTouchesOverride(
        readEnabled = {
            try {
                Settings.System.getInt(contentResolver, "show_touches", 0) != 0
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read Show Taps; leaving the setting unchanged", e)
                null
            }
        },
        writeEnabled = PrivilegedAccessManager::setShowTouches
    )
    private var hasChangedImmersive = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var outputFilePath: String = ""
    private var outputFileDescriptor: ParcelFileDescriptor? = null
    private var outputMediaStoreUri: Uri? = null

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var inputSurface: Surface? = null
    private var rotationRenderer: AutoRotationRenderer? = null
    private var captureScale = 1.0
    private var captureWidth = 0
    private var captureHeight = 0
    private var displayListenerRegistered = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT < 34) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
                resizeCapture(metrics.widthPixels, metrics.heightPixels)
            }
        }
    }

    private var micRecord: AudioRecord? = null
    private var microphoneEffects: MicrophoneEffects? = null
    private var cancelMicrophoneEcho = false
    private var sysRecord: AudioRecord? = null

    @Volatile private var audioRecordingThread: Thread? = null
    @Volatile private var videoEncoderThread: Thread? = null
    @Volatile private var stopRequested = false
    private var hasAnyAudio = false
    private var isScreenOffPaused = false
    private var isScreenStateReceiverRegistered = false

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (configManager.isPauseOnScreenOff && isRecording && !isPaused) {
                        isScreenOffPaused = true
                        pauseRecording()
                    }
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    if (configManager.isPauseOnScreenOff && isRecording && isPaused && isScreenOffPaused) {
                        isScreenOffPaused = false
                        resumeRecording()
                    }
                }
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            resizeCapture(width, height)
        }

        override fun onStop() {
            mainHandler.post {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /*
     * Returns null as binding is not supported for this foreground service.
     */
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

        val widthRatio = screenWidth.toFloat() / physicalMaxRes.first.toFloat()
        scaledDensity = (screenDensity * widthRatio).toInt().coerceAtLeast(120)
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
            ACTION_TOGGLE_MUTE -> {
                isMuted = !isMuted
                RecordingStateManager.onMuteToggled(isMuted)
                updateNotification()
                sendStateBroadcast("state_mute_changed")
            }
            ACTION_TOGGLE_DRAW -> {
                if (DrawOverlayService.isDrawRunning) {
                    stopService(Intent(this, DrawOverlayService::class.java))
                } else {
                    startService(Intent(this, DrawOverlayService::class.java))
                }
                mainHandler.postDelayed({
                    updateNotification()
                    RecordingStateManager.notifyListeners()
                    sendStateBroadcast("state_draw_changed")
                }, 150)
            }
            ACTION_TOGGLE_CAMERA -> {
                if (CameraOverlayService.isCameraRunning) {
                    stopService(Intent(this, CameraOverlayService::class.java))
                } else {
                    if (isRecording) CameraPermissionActivity.launch(this)
                }
                mainHandler.postDelayed({
                    updateNotification()
                    RecordingStateManager.notifyListeners()
                    sendStateBroadcast("state_camera_changed")
                }, 150)
            }
            ACTION_REFRESH_CAMERA -> if (isRecording) updateNotification()
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
        if (isRecording || isStarting) return
        isStarting = true
        startupPending = true
        sessionUsesRoot = PrivilegedAccessManager.usesRoot
        RecordingStateManager.notifyListeners()
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

        if (configManager.isRecordingOverlayEnabled) {
            startService(Intent(this, RecordingOverlayService::class.java))
        }

        acquireWakeLock()

        totalPausedDurationUs = 0
        lastVideoWrittenPtsUs = 0
        lastAudioWrittenPtsUs = 0
        
        if (!PrivilegedAccessManager.isPermissionGranted()) {
            isStarting = false
            startupPending = false
            startRecording(resultCode, data)
            return
        }
        PrivilegedAccessManager.runAsync(sessionUsesRoot) {
            if (!serviceDestroyed) applyPrivilegedFeaturesOnStart()
            mainHandler.post {
                startupPending = false
                if (!serviceDestroyed) {
                    isStarting = false
                    startRecording(resultCode, data)
                }
                RecordingStateManager.notifyListeners()
            }
        }
    }

    /*
     * Acquires partial wake lock to maintain CPU execution during lock screen capture.
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenRecorder:ActiveRecording").apply {
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock acquisition failed: ${e.message}")
        }
    }

    /*
     * Releases active wake lock when recording concludes.
     */
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock release failed: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    /*
     * Sets up encoders, creates the virtual display with calculated resolutions, and starts background threads.
     */
    @SuppressLint("Range")
    private fun startRecording(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, mainHandler)

        val physicalMaxRes = configManager.getMaxSupportedResolution()
        val targetRes = configManager.getScaledResolution()
        screenWidth = targetRes.first
        screenHeight = targetRes.second
        val widthRatio = screenWidth.toFloat() / physicalMaxRes.first.toFloat()
        scaledDensity = (screenDensity * widthRatio).toInt().coerceAtLeast(120)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ScreenRecord_$timestamp.mp4"

        val customUriString = configManager.customStorageUri
        var customSaved = false

        if (!customUriString.isNullOrEmpty()) {
            try {
                val treeUri = Uri.parse(customUriString)
                val pickedDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri)
                if (pickedDir != null && pickedDir.canWrite()) {
                    val newFile = pickedDir.createFile("video/mp4", fileName)
                    if (newFile != null) {
                        outputMediaStoreUri = newFile.uri
                        outputFileDescriptor = contentResolver.openFileDescriptor(newFile.uri, "rw")
                        customSaved = true
                    }
                }
            } catch (e: Exception) {
                customSaved = false
            }
        }

        if (!customSaved) {
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
                val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                val recorderDir = File(moviesDir, "ScreenRecorder").apply { if (!exists()) mkdirs() }
                outputFilePath = File(recorderDir, fileName).absolutePath
            }
        }

        val hasMicPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val wantMic = configManager.isMicEnabled && hasMicPerm
        val wantSysAudio = configManager.isSystemAudioEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        hasAnyAudio = wantMic || wantSysAudio
        stopRequested = false

        try {
            val videoMime = if (configManager.videoEncoder == ConfigManager.ENCODER_HEVC && isH265Supported()) {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }

            val targetFps = configManager.videoFps
            val videoFormat = MediaFormat.createVideoFormat(videoMime, screenWidth, screenHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, configManager.getOptimalVideoBitrate())
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, targetFps.toFloat())
                setInteger(MediaFormat.KEY_CAPTURE_RATE, targetFps)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, (1_000_000L / targetFps).coerceAtLeast(1000L))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }

            videoEncoder = MediaCodec.createEncoderByType(videoMime).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            if (configManager.videoOrientation == ConfigManager.ORIENTATION_AUTO) {
                captureScale = screenWidth.toDouble() / physicalMaxRes.first
                captureWidth = screenWidth
                captureHeight = screenHeight
                rotationRenderer = AutoRotationRenderer(checkNotNull(inputSurface), screenWidth, screenHeight) { error ->
                    mainHandler.post {
                        Log.e(TAG, "Rotation rendering failed", error)
                        stopRecording()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
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
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rotationRenderer?.surface ?: inputSurface, null, null
            )

            if (rotationRenderer != null && Build.VERSION.SDK_INT < 34) {
                getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, mainHandler)
                displayListenerRegistered = true
            }

            if (hasAnyAudio) {
                micRecord?.startRecording()
                sysRecord?.startRecording()
                audioRecordingThread = Thread({ drainAudioEncoder() }, "AudioThread").apply { start() }
            }
            videoEncoderThread = Thread({ drainVideoEncoder() }, "VideoThread").apply { start() }

            isRecording = true
            isPaused = false
            val initialMuted = !hasAnyAudio
            isMuted = initialMuted
            RecordingStateManager.onRecordingStarted(SystemClock.elapsedRealtime(), initialMuted)
            sendStateBroadcast(STATE_START)

            registerScreenStateReceiver()

        } catch (e: Exception) {
            Log.e(TAG, "Start failed: ${e.message}")
            PrivilegedAccessManager.runAsync(sessionUsesRoot) { restorePrivilegedFeaturesOnStop() }
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun resizeCapture(width: Int, height: Int) {
        val renderer = rotationRenderer ?: return
        val display = virtualDisplay ?: return
        if (stopRequested || width <= 0 || height <= 0) return
        val (newWidth, newHeight) = RecordingFrameLayout.captureSize(width, height, captureScale)
        if (captureWidth == newWidth && captureHeight == newHeight) return
        try {
            display.setSurface(null)
            renderer.resize(newWidth, newHeight)
            display.resize(newWidth, newHeight, scaledDensity)
            captureWidth = newWidth
            captureHeight = newHeight
            if (!isPaused) display.setSurface(renderer.surface)
        } catch (e: Exception) {
            Log.e(TAG, "Capture resize failed", e)
            stopRecording()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /*
     * Registers screen state broadcast receiver for lock screen and display power events.
     */
    private fun registerScreenStateReceiver() {
        if (!isScreenStateReceiverRegistered) {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenStateReceiver, filter)
            isScreenStateReceiverRegistered = true
        }
    }

    /*
     * Unregisters screen state broadcast receiver cleanly on recording termination.
     */
    private fun unregisterScreenStateReceiver() {
        if (isScreenStateReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Exception) {}
            isScreenStateReceiverRegistered = false
        }
    }

    /*
     * Applies advanced settings through the selected backend before capture starts.
     */
    private fun applyPrivilegedFeaturesOnStart() {
        if (PrivilegedAccessManager.isPermissionGranted()) {
            if (configManager.isShizukuShowTouchesEnabled) {
                showTouchesOverride.enableForRecording()
            }
            if (configManager.isShizukuHideSystemBarsEnabled) {
                val success = PrivilegedAccessManager.setImmersiveMode(true)
                if (success) hasChangedImmersive = true
            }
            if (configManager.isShizukuEnhancedAudioEnabled) {
                PrivilegedAccessManager.grantEnhancedAudioPermission(this)
            }
            if (configManager.isShizukuDirectRecordingEnabled) {
                PrivilegedAccessManager.grantProjectMediaPermission(this)
            }
        }
    }

    /*
     * Restores system settings through the backend that started this session.
     */
    private fun restorePrivilegedFeaturesOnStop(onlyChanged: Boolean = false) {
        showTouchesOverride.restore()
        if (PrivilegedAccessManager.isPermissionGranted()) {
            if (hasChangedImmersive || (!onlyChanged && configManager.isShizukuHideSystemBarsEnabled)) {
                PrivilegedAccessManager.setImmersiveMode(false)
                hasChangedImmersive = false
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

        cancelMicrophoneEcho = wantMic && wantSysAudio
        if (wantMic) micRecord = createAudioRecord(isMic = true)
        if (wantSysAudio) sysRecord = createAudioRecord(isMic = false)
    }

    private fun createAudioRecord(isMic: Boolean): AudioRecord {
        val sampleRate = 48_000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuf > 0) { "Unsupported audio capture format" }
        val record = if (isMic) {
            createMicrophoneRecord(sampleRate, minBuf * 4)
        } else {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("Audio capture did not initialize")
        }
        if (isMic) {
            microphoneEffects?.close()
            microphoneEffects = MicrophoneEffects(record.audioSessionId, cancelMicrophoneEcho)
        }
        return record
    }

    private fun createMicrophoneRecord(sampleRate: Int, bufferSize: Int): AudioRecord {
        val sources = if (cancelMicrophoneEcho) {
            intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
        } else intArrayOf(MediaRecorder.AudioSource.MIC)
        for (source in sources) {
            try {
                val record = AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                if (record.state == AudioRecord.STATE_INITIALIZED) return record
                record.release()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Microphone source $source unavailable", e)
            }
        }
        error("Microphone capture did not initialize")
    }

    private var micRetryAfterMs = 0L
    private var systemRetryAfterMs = 0L

    private fun readAudioSource(isMic: Boolean, buffer: ShortArray, offset: Int, count: Int): Int {
        if (stopRequested) return 0
        var record = if (isMic) micRecord else sysRecord
        val now = SystemClock.elapsedRealtime()
        val retryAfter = if (isMic) micRetryAfterMs else systemRetryAfterMs
        if (record == null && now >= retryAfter) {
            try {
                record = createAudioRecord(isMic)
                if (stopRequested) {
                    record.release()
                    return 0
                }
                record.startRecording()
                if (isMic) micRecord = record else sysRecord = record
            } catch (e: Exception) {
                if (isMic) { microphoneEffects?.close(); microphoneEffects = null }
                try { record?.release() } catch (_: Exception) {}
                Log.w(TAG, "Audio source restart failed (mic=$isMic)", e)
                if (isMic) micRetryAfterMs = now + 1000 else systemRetryAfterMs = now + 1000
                return 0
            }
        }
        record ?: return 0
        val samples = try {
            record.read(buffer, offset, count, AudioRecord.READ_NON_BLOCKING)
        } catch (e: Exception) {
            if (!stopRequested) Log.w(TAG, "Audio source read failed (mic=$isMic)", e)
            AudioRecord.ERROR_DEAD_OBJECT
        }
        if (samples < 0 && !stopRequested) {
            Log.w(TAG, "Audio source returned $samples (mic=$isMic); recreating capture")
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
            if (isMic) {
                microphoneEffects?.close(); microphoneEffects = null
                micRecord = null
                micRetryAfterMs = now + 1000
            } else {
                sysRecord = null
                systemRetryAfterMs = now + 1000
            }
        }
        return samples.coerceAtLeast(0)
    }

    /*
     * Continuously processes and multiplexes audio from system and microphone streams.
     */
    private fun drainAudioEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoder = audioEncoder ?: return
        val hasMic = micRecord != null
        val hasSystem = sysRecord != null
        val pump = AudioFramePump(
            readMic = { buffer, offset, count -> if (hasMic) readAudioSource(true, buffer, offset, count) else 0 },
            readSystem = { buffer, offset, count -> if (hasSystem) readAudioSource(false, buffer, offset, count) else 0 },
            submit = { pcm, ptsUs ->
                val inputIndex = encoder.dequeueInputBuffer(0)
                if (inputIndex < 0) {
                    false
                } else {
                    val input = checkNotNull(encoder.getInputBuffer(inputIndex))
                    input.clear()
                    input.put(pcm)
                    encoder.queueInputBuffer(inputIndex, 0, pcm.size, ptsUs, 0)
                    true
                }
            }
        )
        while (!stopRequested) {
            // Drain independently of source availability, pauses and codec backpressure.
            drainEncoderOutput(encoder, bufferInfo, isAudio = true)
            pump.step(System.nanoTime() / 1000, isPaused, isMuted)
            SystemClock.sleep(2)
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
        virtualDisplay?.setSurface(null)
        rotationRenderer?.close()
        rotationRenderer = null
        stopRequested = true
        isPaused = false
        isMuted = false
        RecordingStateManager.onRecordingStopped()

        stopService(Intent(this, RecordingOverlayService::class.java))
        stopService(Intent(this, CameraOverlayService::class.java))
        stopService(Intent(this, DrawOverlayService::class.java))

        PrivilegedAccessManager.runAsync(sessionUsesRoot) { restorePrivilegedFeaturesOnStop() }
        unregisterScreenStateReceiver()
        releaseWakeLock()

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
        if (displayListenerRegistered) {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        virtualDisplay?.release(); virtualDisplay = null
        rotationRenderer?.close(); rotationRenderer = null
        microphoneEffects?.close(); microphoneEffects = null
        micRecord?.release(); micRecord = null
        sysRecord?.release(); sysRecord = null
        try { audioEncoder?.stop() } catch (_: Exception) {}; audioEncoder?.release(); audioEncoder = null
        try { videoEncoder?.stop() } catch (_: Exception) {}; videoEncoder?.release(); videoEncoder = null
        inputSurface?.release(); inputSurface = null
        try { mediaMuxer?.release() } catch (_: Exception) {}; mediaMuxer = null
        muxerStarted = false
        try { outputFileDescriptor?.close() } catch (_: Exception) {}; outputFileDescriptor = null
        mediaProjection?.stop(); mediaProjection = null
        releaseWakeLock()
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
        RecordingStateManager.onRecordingPaused()
        sendStateBroadcast(STATE_PAUSE)
        updateNotification()
    }

    /*
     * Continues display capture sequence avoiding time jump glitches within final output.
     */
    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        val resumeTimeUs = System.nanoTime() / 1000
        totalPausedDurationUs += (resumeTimeUs - pauseStartTimeUs)
        isPaused = false
        (rotationRenderer?.surface ?: inputSurface)?.let { virtualDisplay?.setSurface(it) }
        RecordingStateManager.onRecordingResumed()
        sendStateBroadcast(STATE_RESUME)
        updateNotification()
    }

    /*
     * Formats duration in milliseconds to HH:mm:ss or mm:ss display format.
     */
    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hours = minutes / 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes % 60, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /*
     * Crafts the continuous system notification with dynamic pause, draw, mute, camera, and stop controls.
     */
    private fun buildRecordingNotification(): Notification {
        val stopIntent = PendingIntent.getService(this, 0, Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pauseResumeIntent = PendingIntent.getService(this, 1, Intent(this, ScreenRecordService::class.java).apply { action = if (isPaused) ACTION_RESUME else ACTION_PAUSE }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val muteIntent = PendingIntent.getService(this, 2, Intent(this, ScreenRecordService::class.java).apply { action = ACTION_TOGGLE_MUTE }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cameraIntent = PendingIntent.getActivity(this, 3, Intent(this, CameraPermissionActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val drawIntent = PendingIntent.getService(this, 4, Intent(this, ScreenRecordService::class.java).apply { action = ACTION_TOGGLE_DRAW }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val contentIntent = PendingIntent.getActivity(
            this,
            5,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseTitle = if (isPaused) getString(R.string.ScreenRecordService_notif_btn_resume) else getString(R.string.ScreenRecordService_notif_btn_pause)
        val pauseIcon = if (isPaused) R.drawable.ic_play else R.drawable.ic_pause

        val muteTitle = if (isMuted) getString(R.string.ScreenRecordService_notif_btn_unmute) else getString(R.string.ScreenRecordService_notif_btn_mute)
        val muteIcon = if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic

        val cameraTitle = if (CameraOverlayService.isCameraRunning) getString(R.string.ScreenRecordService_notif_btn_camera_off) else getString(R.string.ScreenRecordService_notif_btn_camera_on)
        val drawTitle = if (DrawOverlayService.isDrawRunning) getString(R.string.ScreenRecordService_notif_btn_draw_off) else getString(R.string.ScreenRecordService_notif_btn_draw_on)

        val activeElapsedMs = RecordingStateManager.getElapsedMillis()
        val formattedPausedTime = formatDuration(activeElapsedMs)
        val notifText = if (isPaused) {
            getString(R.string.ScreenRecordService_notif_text_paused_format, formattedPausedTime)
        } else {
            getString(R.string.ScreenRecordService_notif_text)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ScreenRecordService_notif_title))
            .setContentText(notifText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notifText))
            .setSmallIcon(R.drawable.ic_screen_record)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(pauseIcon, pauseTitle, pauseResumeIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.ScreenRecordService_notif_btn_stop), stopIntent)
            .addAction(muteIcon, muteTitle, muteIntent)
            .addAction(R.drawable.ic_camera, cameraTitle, cameraIntent)
            .addAction(R.drawable.ic_draw, drawTitle, drawIntent)

        if (isPaused) {
            builder.setUsesChronometer(false)
            builder.setShowWhen(false)
        } else {
            val chronometerWhen = System.currentTimeMillis() - activeElapsedMs
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(false)
            builder.setShowWhen(true)
            builder.setWhen(chronometerWhen)
        }

        return builder.build()
    }

    /*
     * Updates the foreground notification dynamically when states change.
     */
    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildRecordingNotification())
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

    /*
     * Ensures all services, receivers, and hardware captures are cleanly released on destroy.
     */
    override fun onDestroy() {
        serviceDestroyed = true
        if (startupPending) {
            // Enqueue rollback before a later session can enqueue its startup commands.
            PrivilegedAccessManager.runAsync(sessionUsesRoot) { restorePrivilegedFeaturesOnStop(onlyChanged = true) }
        }
        isStarting = false
        stopRecording()
        releaseWakeLock()
        stopService(Intent(this, RecordingOverlayService::class.java))
        RecordingStateManager.notifyListeners()
        super.onDestroy()
    }
}
