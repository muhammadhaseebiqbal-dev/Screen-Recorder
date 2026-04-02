package com.haseeb.recorder

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.haseeb.recorder.databinding.ActivityMainBinding
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_PERMISSIONS = 2001
        private const val TAB_ALL = 0
        private const val TAB_RECENTS = 1
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VideoAdapter
    private var useMic = false
    private var useSystemAudio = false
    private var allVideos = listOf<VideoFile>()
    private var currentTab = TAB_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = VideoAdapter(
            onPlayClick = ::playVideo,
            onRenameClick = ::renameVideo,
            onDeleteClick = ::deleteVideo
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // ── Tabs ──────────────────────────────────────────────────
        setupTabs()

        // ── Info Button ───────────────────────────────────────────
        binding.btnInfo.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // ── Control Bar ───────────────────────────────────────────
        updateToggleUi()
        binding.btnToggleMic.setOnClickListener {
            useMic = !useMic
            updateToggleUi()
        }
        binding.btnToggleSystemAudio.setOnClickListener {
            useSystemAudio = !useSystemAudio
            updateToggleUi()
        }

        binding.fabRecord.setOnClickListener {
            if (ScreenRecordService.isRecording) {
                val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_STOP
                }
                startService(stopIntent)
                updateRecordingStateUi(false)
            } else {
                val intent = Intent(this, MediaProjectionPermissionActivity::class.java).apply {
                    putExtra("RECORD_MIC", useMic)
                    putExtra("RECORD_SYSTEM_AUDIO", useSystemAudio)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            }
        }

        // Request overlay permission
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        requestPerms()

        // ── FAB entrance animation ────────────────────────────────
        binding.bottomControlBar.translationY = 200f
        binding.bottomControlBar.alpha = 0f
        binding.bottomControlBar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(300)
            .setInterpolator(OvershootInterpolator(1.0f))
            .start()
    }

    // ═══════════════════════════════════════════════════════════════
    //  TABS
    // ═══════════════════════════════════════════════════════════════
    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("All"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Recents"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: TAB_ALL
                applyFilter()
                binding.recyclerView.scheduleLayoutAnimation()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun applyFilter() {
        val filtered = when (currentTab) {
            TAB_RECENTS -> {
                val past48Hours = System.currentTimeMillis() / 1000 - (48 * 60 * 60)
                allVideos.filter { it.dateAdded >= past48Hours }
            }
            else -> allVideos
        }
        adapter.submitList(filtered)
        updateEmptyState(filtered)
    }

    // ═══════════════════════════════════════════════════════════════
    //  UI STATE
    // ═══════════════════════════════════════════════════════════════
    private fun updateRecordingStateUi(isRecording: Boolean) {
        if (isRecording) {
            binding.btnToggleMic.visibility = View.GONE
            binding.btnToggleSystemAudio.visibility = View.GONE
            binding.fabRecord.setImageResource(R.drawable.ic_stop)
        } else {
            binding.btnToggleMic.visibility = View.VISIBLE
            binding.btnToggleSystemAudio.visibility = View.VISIBLE
            binding.fabRecord.setImageResource(R.drawable.ic_screen_record)
        }
    }

    private fun updateToggleUi() {
        binding.btnToggleMic.setImageResource(if (useMic) R.drawable.ic_mic else R.drawable.ic_mic_off)
        binding.btnToggleMic.alpha = if (useMic) 1.0f else 0.5f
        binding.btnToggleSystemAudio.setImageResource(if (useSystemAudio) R.drawable.ic_audio else R.drawable.ic_audio_off)
        binding.btnToggleSystemAudio.alpha = if (useSystemAudio) 1.0f else 0.5f
    }

    private fun updateEmptyState(videos: List<VideoFile>) {
        if (videos.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════════════════════════
    override fun onResume() {
        super.onResume()
        updateRecordingStateUi(ScreenRecordService.isRecording)

        val readPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.READ_MEDIA_VIDEO
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, readPerm) == PackageManager.PERMISSION_GRANTED) {
            loadVideos()
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            perms.add(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            @Suppress("DEPRECATION")
            perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            @Suppress("DEPRECATION")
            perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            loadVideos()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) loadVideos()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1002 && resultCode == RESULT_OK) {
            loadVideos()
            Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  DATA
    // ═══════════════════════════════════════════════════════════════
    @SuppressLint("Range")
    private fun loadVideos() {
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
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: continue
                val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))
                if (size == 0L) continue

                val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                videos.add(VideoFile(id, uri, name, duration, size, dateAdded))
            }
        }

        allVideos = videos
        applyFilter()
    }

    // ═══════════════════════════════════════════════════════════════
    //  ACTIONS
    // ═══════════════════════════════════════════════════════════════
    private fun playVideo(video: VideoFile) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(video.uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) }
        catch (e: Exception) { Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show() }
    }

    private fun renameVideo(video: VideoFile) {
        val input = EditText(this).apply {
            setText(video.name.removeSuffix(".mp4"))
            // Resolve theme-aware text color (black in light, white in dark)
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            setTextColor(ContextCompat.getColor(context, typedValue.resourceId))
            context.theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
            setHintTextColor(ContextCompat.getColor(context, typedValue.resourceId))
            setPadding(48, 32, 48, 32)
            background = null
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Rename Recording")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank() || newName == video.name.removeSuffix(".mp4")) return@setPositiveButton
                val finalName = "$newName.mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
                }
                try {
                    val updated = contentResolver.update(video.uri, values, null, null)
                    if (updated > 0) {
                        loadVideos()
                    } else {
                        Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (securityException: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val recoverableSecurityException = securityException as?
                            android.app.RecoverableSecurityException
                        recoverableSecurityException?.userAction?.actionIntent?.intentSender?.let { sender ->
                            startIntentSenderForResult(sender, 1001, null, 0, 0, 0)
                        } ?: Toast.makeText(this, "Rename failed — permission denied", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Rename failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteVideo(video: VideoFile) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Delete Recording")
            .setMessage("Are you sure you want to delete this recording?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    contentResolver.delete(video.uri, null, null)
                    loadVideos()
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intentSender = MediaStore.createDeleteRequest(contentResolver, listOf(video.uri)).intentSender
                            startIntentSenderForResult(intentSender, 1002, null, 0, 0, 0, null)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                        }
                    } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        try {
                            val recoverableSecurityException = e as? android.app.RecoverableSecurityException
                            if (recoverableSecurityException != null) {
                                startIntentSenderForResult(recoverableSecurityException.userAction.actionIntent.intentSender, 1002, null, 0, 0, 0, null)
                            } else {
                                Toast.makeText(this@MainActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                         Toast.makeText(this@MainActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
