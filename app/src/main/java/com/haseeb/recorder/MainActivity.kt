package com.haseeb.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.haseeb.recorder.databinding.ActivityMainBinding
import kotlinx.coroutines.*

/**
 * Main activity for the Screen Recorder application.
 * Manages video list loading, dynamic permission handling for all Android versions,
 * and background service synchronization.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS_CODE = 2001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: ConfigManager
    private lateinit var adapter: VideoAdapter

    /**
     * Receives recording state updates from the ScreenRecordService.
     * Ensures the UI FAB and text stay synced with the actual recording status.
     */
    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenRecordService.ACTION_STATE_CHANGED) {
                syncRecordingUi()
            }
        }
    }

    /**
     * Observes changes in the MediaStore Video database.
     * Automatically triggers [loadVideos] when a new recording is saved or deleted.
     */
    private val videoObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            loadVideos()
        }
    }

    /**
     * Entry point of the activity. Sets up UI, initializes managers,
     * registers receivers, and triggers the initial permission health check.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeComponents()
        setupRecyclerView()
        setupClickListeners()
        registerSystemObservers()
        
        // Critical: Check all permissions immediately on launch
        performFullPermissionCheck()
    }

    /**
     * Initializes core managers and support components.
     */
    private fun initializeComponents() {
        configManager = ConfigManager(this)
        setSupportActionBar(binding.toolbar)
    }

    /**
     * Registers receivers for service state changes and MediaStore content updates.
     * Includes compatibility checks for Android 13+ (Tiramisu) receiver flags.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSystemObservers() {
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            videoObserver
        )

        val filter = IntentFilter(ScreenRecordService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(recordingStateReceiver, filter)
        }
    }

    /**
     * Sets up the RecyclerView for displaying recorded videos.
     */
    private fun setupRecyclerView() {
        adapter = VideoAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    /**
     * Sets up click listeners with tactile haptic feedback.
     */
    private fun setupClickListeners() {
        binding.fabRecord.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            handleRecordAction()
        }

        binding.btnSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            SettingsBottomSheet.newInstance().show(supportFragmentManager, "SettingsBottomSheet")
        }
    }

    /**
     * Orchestrates the verification of all required permissions:
     * Runtime (Camera/Mic/Storage), Overlay, and System Write Settings.
     */
    private fun performFullPermissionCheck() {
        if (!checkAndRequestRuntimePermissions()) return
        if (!checkOverlayPermission()) return
        if (!checkWriteSettingsPermission()) return
        
        // If all clear, load the data
        loadVideos()
    }

    /**
     * Checks for standard runtime permissions based on the device Android version.
     * Handles the shift from Storage to Media permissions in newer Android APIs.
     */
    private fun checkAndRequestRuntimePermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        return if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS_CODE)
            false
        } else {
            true
        }
    }

    /**
     * Validates if the app can draw over other apps (Overlay).
     * Redirects to system settings if permission is missing.
     */
    private fun checkOverlayPermission(): Boolean {
        return if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            false
        } else {
            true
        }
    }

    /**
     * Validates if the app can modify system settings.
     * This is crucial for advanced recorder features that might toggle system states.
     */
    private fun checkWriteSettingsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                false
            } else {
                true
            }
        } else {
            true
        }
    }

    /**
     * Handles logic for starting or stopping a recording session.
     * Acts as a gateway to the MediaProjection permission activity.
     */
    private fun handleRecordAction() {
        if (ScreenRecordService.isRecording) {
            val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            }
            startService(stopIntent)
        } else {
            val intent = Intent(this, MediaProjectionPermissionActivity::class.java).apply {
                putExtra("RECORD_MIC", configManager.isMicEnabled)
                putExtra("RECORD_SYSTEM_AUDIO", configManager.isSystemAudioEnabled)
            }
            startActivity(intent)
        }
    }

    /**
     * Syncs the Floating Action Button (FAB) UI state with the service.
     */
    private fun syncRecordingUi() {
        val isRecording = ScreenRecordService.isRecording
        binding.fabRecord.apply {
            setIconResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_screen_record)
            text = if (isRecording) "Stop" else getString(R.string.MainActivity_start_recording)
        }
    }

    /**
     * Asynchronously loads screen recording files from MediaStore.
     * Filters files based on naming conventions to ensure only app-related videos appear.
     */
    @SuppressLint("Range")
    private fun loadVideos() {
        lifecycleScope.launch(Dispatchers.IO) {
            val videos = mutableListOf<VideoFile>()
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DATA
            )

            val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("ScreenRecord_%.mp4", "%ScreenRecorder%")

            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: ""
                    val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
                    val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))

                    if (size > 0L) {
                        val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                        videos.add(VideoFile(id, uri, name, duration, size, dateAdded))
                    }
                }
            }

            withContext(Dispatchers.Main) {
                adapter.submitList(videos)
                binding.emptyView.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (videos.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    /**
     * Refreshes recording state and checks permissions every time user returns to the app.
     */
    override fun onResume() {
        super.onResume()
        syncRecordingUi()
        performFullPermissionCheck()
    }

    /**
     * Clean up resources to prevent memory leaks.
     */
    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(videoObserver)
        unregisterReceiver(recordingStateReceiver)
    }

    /**
     * Callback handler for runtime permission requests.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                performFullPermissionCheck()
            }
        }
    }
}
