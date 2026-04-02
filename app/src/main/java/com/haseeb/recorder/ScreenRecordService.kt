package com.haseeb.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
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
        const val ACTION_TOGGLE_MIC = "com.haseeb.recorder.ACTION_TOGGLE_MIC"
        const val ACTION_TOGGLE_INTERNAL_AUDIO = "com.haseeb.recorder.ACTION_TOGGLE_INTERNAL_AUDIO"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        var isRecording = false
            private set

        var isMicEnabled = true
        var isInternalAudioEnabled = true

        interface AudioStateListener { fun onAudioStateChanged() }
        var audioStateListener: AudioStateListener? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var outputFilePath: String = ""

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
        // H.264 strictly requires even dimensions
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
                    // Initialise audio defaults from user preferences before each recording
                    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    isMicEnabled = prefs.getBoolean(MainActivity.PREF_MIC_ENABLED, true)
                    isInternalAudioEnabled = prefs.getBoolean(MainActivity.PREF_INTERNAL_AUDIO_ENABLED, true)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, buildRecordingNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else {
                        startForeground(NOTIFICATION_ID, buildRecordingNotification())
                    }
                    startRecording(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TOGGLE_MIC -> {
                isMicEnabled = !isMicEnabled
                audioStateListener?.onAudioStateChanged()
            }
            ACTION_TOGGLE_INTERNAL_AUDIO -> {
                isInternalAudioEnabled = !isInternalAudioEnabled
                audioStateListener?.onAudioStateChanged()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, mainHandler)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ScreenRecord_$timestamp.mp4"

        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val recorderDir = File(dcimDir, "ScreenRecorder")
        if (!recorderDir.exists()) recorderDir.mkdirs()
        val outputFile = File(recorderDir, fileName)
        outputFilePath = outputFile.absolutePath

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val hasMicPermission = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val actuallyRecordMic = isMicEnabled && hasMicPermission

        try {
            mediaRecorder?.apply {
                if (actuallyRecordMic) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (actuallyRecordMic) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000)
                    setAudioSamplingRate(44100)
                }
                setVideoSize(screenWidth, screenHeight)
                setVideoFrameRate(60)
                setVideoEncodingBitRate(8_000_000)
                setOutputFile(outputFilePath)
                prepare()
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecorder",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )

            mediaRecorder?.start()
            isRecording = true
            Log.d(TAG, "Recording started → $outputFilePath")
            startService(Intent(this, RecordingOverlayService::class.java))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        try { mediaRecorder?.stop() } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        }

        cleanup()

        val file = File(outputFilePath)
        if (file.exists() && file.length() > 0L) {
            // Trigger MediaScanner so native Gallery picks it up immediately
            MediaScannerConnection.scanFile(this, arrayOf(outputFilePath), arrayOf("video/mp4")) { _, uri ->
                Log.d(TAG, "Media scan complete, uri=$uri")
                mainHandler.post {
                    // Show toast on main thread
                    Toast.makeText(this, "Recording saved to DCIM/ScreenRecorder", Toast.LENGTH_LONG).show()
                    // Show a "Saved" notification that opens the video
                    if (uri != null) showSavedNotification(file.name, uri)
                }
            }
        } else {
            file.delete()
            mainHandler.post {
                Toast.makeText(this, "Recording failed — no video data captured", Toast.LENGTH_LONG).show()
            }
        }

        isRecording = false
        Log.d(TAG, "Recording stopped → $outputFilePath")
        stopService(Intent(this, RecordingOverlayService::class.java))
    }

    private fun cleanup() {
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null
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

        // Also add "Open app" action
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
            .setContentIntent(pendingPlay)   // Tap notification → play video
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
