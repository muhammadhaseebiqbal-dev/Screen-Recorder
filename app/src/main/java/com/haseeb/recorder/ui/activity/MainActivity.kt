package com.haseeb.recorder.ui.activity

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
import com.haseeb.recorder.R
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.data.VideoFile
import com.haseeb.recorder.recording.ScreenRecordService
import com.haseeb.recorder.ui.adapter.VideoAdapter
import com.haseeb.recorder.ui.dialog.SettingsBottomSheet
import com.haseeb.recorder.util.*
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
     * Wraps the base context with the user-selected locale configuration.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
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
        handleIncomingIntent(intent)
    }

    /*
     * Handles new incoming intent to open settings when launched via system shortcuts.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /*
     * Inspects the intent action and displays the settings sheet if requested.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_APPLICATION_PREFERENCES) {
            val existing = supportFragmentManager.findFragmentByTag("SettingsBottomSheet")
            if (existing == null) {
                SettingsBottomSheet.newInstance().show(supportFragmentManager, "SettingsBottomSheet")
            }
        }
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
        loadVideos()
    }

    /*
     * Checks and requests runtime permissions adapted for the current Android version.
     */
    private fun checkAndRequestRuntimePermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
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
            setIconResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_screen_record)
            text = if (isRecording) getString(R.string.MainActivity_record_stop) else getString(R.string.MainActivity_record_start)
        }
    }

    /*
     * Loads screen recordings rapidly from MediaStore and immediately populates the user interface.
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
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT
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
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val duration = cursor.getLong(durCol)
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateCol)
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)

                    if (size > 0L) {
                        val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                        val fallbackQuality = if (width > 0 && height > 0) "${kotlin.math.min(width, height)}p" else null
                        videos.add(VideoFile(id, uri, name, duration, size, dateAdded, fallbackQuality))
                    }
                }
            }

            val customUriString = configManager.customStorageUri
            if (!customUriString.isNullOrEmpty()) {
                try {
                    val treeUri = Uri.parse(customUriString)
                    val pickedDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                    if (pickedDir != null && pickedDir.isDirectory) {
                        val docFiles = pickedDir.listFiles()
                        for (docFile in docFiles) {
                            if (docFile.isFile && docFile.name?.startsWith("ScreenRecord_") == true && docFile.name?.endsWith(".mp4") == true) {
                                val alreadyAdded = videos.any { it.name == docFile.name }
                                if (!alreadyAdded && docFile.length() > 0L) {
                                    val generatedId = (docFile.name ?: "").hashCode().toLong()
                                    videos.add(
                                        VideoFile(
                                            id = generatedId,
                                            uri = docFile.uri,
                                            name = docFile.name ?: "",
                                            duration = 0L,
                                            size = docFile.length(),
                                            dateAdded = docFile.lastModified() / 1000,
                                            qualityFps = null
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            videos.sortByDescending { it.dateAdded }

            val initialList = ArrayList(videos)
            withContext(Dispatchers.Main) {
                adapter.submitList(initialList)
                val isEmpty = initialList.isEmpty()
                binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            }

            if (videos.isNotEmpty()) {
                var hasUpdates = false
                val enrichedVideos = videos.map { video ->
                    val (extractedDuration, qualityFps) = VideoMetadataUtils.extractMetadata(this@MainActivity, video.uri)
                    val finalDuration = if (extractedDuration > 0L) extractedDuration else video.duration
                    if (finalDuration != video.duration || qualityFps != video.qualityFps) {
                        hasUpdates = true
                        video.copy(duration = finalDuration, qualityFps = qualityFps ?: video.qualityFps)
                    } else {
                        video
                    }
                }
                if (hasUpdates) {
                    withContext(Dispatchers.Main) {
                        adapter.submitList(enrichedVideos)
                    }
                }
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