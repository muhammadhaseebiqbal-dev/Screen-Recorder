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
import android.view.ViewGroup

import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.haseeb.recorder.databinding.ActivityMainBinding
import kotlinx.coroutines.*

/*
 * Main activity of the Screen Recorder app.
 * Manages permissions, video list display, recording controls,
 * and edge-to-edge window insets.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS_CODE = 2001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: ConfigManager
    private lateinit var adapter: VideoAdapter

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenRecordService.ACTION_STATE_CHANGED) {
                syncRecordingUi()
            }
        }
    }

    private val videoObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            loadVideos()
        }
    }

    /*
     * Sets up edge-to-edge drawing, binds the layout, initializes all components,
     * applies window insets, and triggers the permission check flow.
     */
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        binding.root.applyTopInsets()
        binding.appBarLayout.applySystemBarInsets()
        binding.nestedScrollView.applyBottomInsets()
        binding.fabRecord.applyBottomMargin()
        
        initializeComponents()
        setupRecyclerView()
        setupClickListeners()
        registerSystemObservers()
        performFullPermissionCheck()
    }

    /*
     * Initializes ConfigManager and sets the support action bar.
     */
    private fun initializeComponents() {
        configManager = ConfigManager(this)
        setSupportActionBar(binding.toolbar)
    }

    /*
     * Sets up the RecyclerView with a LinearLayoutManager and the video adapter.
     */
    private fun setupRecyclerView() {
        adapter = VideoAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    /*
     * Attaches click listeners to the record FAB and settings button.
     */
    private fun setupClickListeners() {
        binding.fabRecord.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            handleRecordAction()
        }

        binding.btnSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            SettingsBottomSheet.newInstance().show(supportFragmentManager, "SettingsBottomSheet")
        }
    }

    /*
     * Starts the sequential permission validation flow.
     * Only loads videos if all permissions are already granted.
     */
    private fun performFullPermissionCheck() {
        if (!checkAndRequestRuntimePermissions()) return
        if (!checkOverlayPermission()) return
        if (!checkWriteSettingsPermission()) return
        loadVideos()
    }

    /*
     * Checks and requests runtime permissions adapted for the current Android version.
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

    /*
     * Checks overlay permission and opens the system settings page if not granted.
     */
    private fun checkOverlayPermission(): Boolean {
        return if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            false
        } else true
    }

    /*
     * Checks write settings permission and opens the system settings page if not granted.
     */
    private fun checkWriteSettingsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            false
        } else true
    }

    /*
     * Starts or stops recording based on the current service state.
     */
    private fun handleRecordAction() {
        if (ScreenRecordService.isRecording) {
            startService(Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            })
        } else {
            val intent = Intent(this, MediaProjectionPermissionActivity::class.java).apply {
                putExtra("RECORD_MIC", configManager.isMicEnabled)
                putExtra("RECORD_SYSTEM_AUDIO", configManager.isSystemAudioEnabled)
            }
            startActivity(intent)
        }
    }

    /*
     * Updates the FAB icon and label to match the current recording state.
     */
    private fun syncRecordingUi() {
        val isRecording = ScreenRecordService.isRecording
        binding.fabRecord.apply {
            // setImageResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_screen_record)
            setIconResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_screen_record)
            text = if (isRecording) getString(R.string.MainActivity_record_stop) else getString(R.string.MainActivity_record_start)
        }
    }

    /*
     * Loads screen recordings from MediaStore on a background thread.
     * Updates the adapter and toggles the empty view on the main thread.
     * If the list is empty, the FAB is shown explicitly in case it was
     * hidden by the scroll behavior and has no content to trigger a re-show.
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
                MediaStore.Video.Media.DATE_ADDED
            )

            val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("ScreenRecord_%.mp4")

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
                val isEmpty = videos.isEmpty()
                binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            }
        }
    }

    /*
     * Registers the MediaStore content observer and the recording state broadcast receiver.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSystemObservers() {
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoObserver
        )
        val filter = IntentFilter(ScreenRecordService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(recordingStateReceiver, filter)
        }
    }

    /*
     * Syncs the FAB state whenever the activity comes to the foreground.
     */
    override fun onResume() {
        super.onResume()
        syncRecordingUi()
    }

    /*
     * Unregisters the content observer and broadcast receiver to prevent memory leaks.
     */
    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(videoObserver)
        unregisterReceiver(recordingStateReceiver)
    }
}