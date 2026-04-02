package com.haseeb.recorder

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.haseeb.recorder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_PERMISSIONS = 2001
        const val PREFS_NAME = "screen_recorder_prefs"
        const val PREF_MIC_ENABLED = "pref_mic_enabled_by_default"
        const val PREF_INTERNAL_AUDIO_ENABLED = "pref_internal_audio_enabled_by_default"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VideoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = VideoAdapter(
            onPlayClick = ::playVideo,
            onRenameClick = ::renameVideo
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // Setup FAB for instant recording from gallery
        binding.fabRecord.setOnClickListener {
            val intent = Intent(this, MediaProjectionPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }

        // Settings icon opens the audio defaults dialog
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Request overlay permission
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        requestPerms()
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val switchMic = dialogView.findViewById<Switch>(R.id.switchMicDefault)
        val switchAudio = dialogView.findViewById<Switch>(R.id.switchAudioDefault)

        switchMic.isChecked = prefs.getBoolean(PREF_MIC_ENABLED, true)
        switchAudio.isChecked = prefs.getBoolean(PREF_INTERNAL_AUDIO_ENABLED, true)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.settings_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                prefs.edit()
                    .putBoolean(PREF_MIC_ENABLED, switchMic.isChecked)
                    .putBoolean(PREF_INTERNAL_AUDIO_ENABLED, switchAudio.isChecked)
                    .apply()
                Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
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

        // Query ALL mp4 files that match our folder name
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
                if (size == 0L) continue // Skip failed recordings

                val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                videos.add(VideoFile(id, uri, name, duration, size, dateAdded))
            }
        }

        adapter.submitList(videos)
        binding.subtitleText.text = "${videos.size} video${if (videos.size != 1) "s" else ""}"

        if (videos.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

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
            setTextColor(0xFFF2F2F7.toInt())
            setHintTextColor(0xFF8A8A8E.toInt())
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
                    contentResolver.update(video.uri, values, null, null)
                    loadVideos()
                } catch (e: Exception) {
                    Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
