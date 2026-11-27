package com.haseeb.recorder.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.haseeb.recorder.R
import com.haseeb.recorder.ui.activity.MainActivity
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** All muxer writes stay private. Only a finalized video is copied to the chosen destination. */
object RecordingSessionStore {
    private const val TAG = "RecordingSessionStore"
    private const val PREFS = "recording_session"
    private const val CHANNEL = "recording_recovery"
    private const val NOTIFICATION_ID = 3
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile var isFinishing = false
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasSession(context: Context): Boolean = prefs(context).let {
        it.contains("staging_path") || it.contains("output_uri")
    }

    fun isBusy(context: Context): Boolean =
        !InterruptedRecordingRecovery.canBeginNewSession(isFinishing, hasSession(context))

    fun begin(context: Context, fileName: String, treeUri: String?): File {
        check(!isBusy(context)) { "An earlier recording still needs recovery" }
        // noBackupFilesDir prevents a backup from restoring a stale session or temporary video.
        val directory = File(context.noBackupFilesDir, "recordings").apply {
            check(isDirectory || mkdirs()) { "Cannot create recording directory" }
        }
        val file = File(directory, "$fileName.partial")
        check(prefs(context).edit().putString("staging_path", file.absolutePath)
            .putString("file_name", fileName).putString("tree_uri", treeUri)
            .remove("output_uri").remove("copied").remove("published").commit()) { "Cannot persist recording session" }
        return file
    }

    fun recoverInterruptedSession(context: Context) = finishAsync(context, interrupted = true)

    @Synchronized
    fun finishAsync(context: Context, interrupted: Boolean = false, lowMemory: Boolean = false) {
        if (isFinishing || !hasSession(context)) return
        isFinishing = true
        val app = context.applicationContext
        worker.execute {
            try {
                recover(app, interrupted || lowMemory, lowMemory)
                showNotification(app)
            } catch (e: Exception) {
                Log.e(TAG, "Recording recovery failed; keeping the session for another attempt", e)
            } finally {
                isFinishing = false
                app.sendBroadcast(Intent(ScreenRecordService.ACTION_STATE_CHANGED).setPackage(app.packageName))
            }
        }
    }

    private fun recover(context: Context, reportSuccess: Boolean, lowMemory: Boolean): InterruptedRecordingRecovery.Result {
        val state = prefs(context)
        if (!state.contains("staging_path")) return recoverLegacySession(context)
        val staging = File(checkNotNull(state.getString("staging_path", null)))
        val name = checkNotNull(state.getString("file_name", null))
        return InterruptedRecordingRecovery(
            isPlayable = {
                if (state.getBoolean("published", false)) true else {
                    if (staging.exists() && !staging.canRead()) throw IOException("Recording is inaccessible")
                    isPlayable(staging)
                }
            },
            publish = {
                if (!state.getBoolean("published", false)) {
                    publish(context, staging, name, state.getString("tree_uri", null))
                    check(state.edit().putBoolean("published", true).commit()) { "Cannot checkpoint publication" }
                }
            },
            rememberNotice = { result ->
                rememberNotice(context, when (result) {
                    InterruptedRecordingRecovery.Result.SAVED ->
                        if (lowMemory) R.string.RecordingSessionStore_memory_saved else R.string.RecordingSessionStore_recovered
                    InterruptedRecordingRecovery.Result.REMOVED -> R.string.RecordingSessionStore_removed
                    InterruptedRecordingRecovery.Result.RETRY -> R.string.RecordingSessionStore_retry
                })
            },
            discard = {
                state.getString("output_uri", null)?.let { uriString ->
                    val uri = Uri.parse(uriString)
                    if (state.getString("tree_uri", null) == null) {
                        context.contentResolver.delete(uri, null, null)
                    } else {
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        check(doc == null || !doc.exists() || doc.delete()) { "Cannot remove pending document" }
                    }
                }
                check(!staging.exists() || staging.delete()) { "Cannot remove unfinished video" }
            },
            clear = {
                // The published checkpoint makes cleanup safe to retry even after the source is gone.
                check(!staging.exists() || staging.delete()) { "Cannot remove saved private copy" }
                check(state.edit().remove("staging_path").remove("file_name").remove("tree_uri")
                    .remove("output_uri").remove("copied").remove("published").commit()) { "Cannot clear recording session" }
            }
        ).recover(reportSuccess)
    }

    private fun isPlayable(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return inspectVideo { it.setDataSource(file.absolutePath) }
    }

    private fun inspectVideo(open: (MediaExtractor) -> Unit): Boolean {
        val extractor = MediaExtractor()
        return try {
            open(extractor)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString("mime")?.startsWith("video/") == true
            } ?: return false
            extractor.selectTrack(track)
            // A track alone is insufficient: an interrupted/empty muxer may have no video samples.
            extractor.sampleTime >= 0 && extractor.sampleTrackIndex == track
        } catch (e: Exception) {
            Log.w(TAG, "Interrupted recording has no readable video index", e)
            false
        } finally {
            extractor.release()
        }
    }

    /** Upgrade path for debug builds that wrote an active output_uri directly into the gallery. */
    private fun recoverLegacySession(context: Context): InterruptedRecordingRecovery.Result {
        val state = prefs(context)
        val uri = Uri.parse(checkNotNull(state.getString("output_uri", null)))
        return InterruptedRecordingRecovery(
            isPlayable = {
                // Open first so lost folder permission retains the marker instead of deleting the video.
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                descriptor?.use { inspectVideo { extractor -> extractor.setDataSource(it.fileDescriptor) } } == true
            },
            publish = {}, // Legacy files were already visible; keep a finalized one in place.
            rememberNotice = { result -> rememberNotice(context, when (result) {
                InterruptedRecordingRecovery.Result.SAVED -> R.string.RecordingSessionStore_recovered
                InterruptedRecordingRecovery.Result.REMOVED -> R.string.RecordingSessionStore_removed
                InterruptedRecordingRecovery.Result.RETRY -> R.string.RecordingSessionStore_retry
            }) },
            discard = {
                if (uri.scheme == "file") {
                    val file = File(checkNotNull(uri.path))
                    check(!file.exists() || file.delete())
                } else {
                    context.contentResolver.delete(uri, null, null)
                }
            },
            clear = { check(state.edit().remove("output_uri").commit()) }
        ).recover()
    }

    private fun publish(context: Context, staging: File, name: String, treeUri: String?) {
        val state = prefs(context)
        if (treeUri != null) {
            val directory = checkNotNull(DocumentFile.fromTreeUri(context, Uri.parse(treeUri)))
            check(directory.canWrite()) { "Recording folder is unavailable" }
            val oldUri = state.getString("output_uri", null)
            var document = oldUri?.let { DocumentFile.fromSingleUri(context, Uri.parse(it)) }
            if (document?.exists() != true) {
                // renameDocument can change the URI. Recover a rename completed just before a kill.
                document = if (state.getBoolean("copied", false)) directory.findFile(name) else null
            }
            if (document == null) {
                // A provider may expose documents immediately, so use a non-video temporary type/name.
                document = checkNotNull(directory.createFile("application/octet-stream", ".$name.pending"))
                saveDestination(context, document.uri)
            }
            if (!state.getBoolean("copied", false)) {
                copyTo(context, staging, document.uri)
                check(state.edit().putBoolean("copied", true).commit())
            }
            check(document.name == name || document.renameTo(name)) { "Cannot publish recording document" }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val previousUri = state.getString("output_uri", null)?.let(Uri::parse)
            val exists = previousUri != null && context.contentResolver.query(previousUri,
                arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
            val uri = previousUri?.takeIf { exists } ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ScreenRecorder")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                checkNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
                    .also { saveDestination(context, it) }
            }
            if (!state.getBoolean("copied", false)) {
                copyTo(context, staging, uri)
                check(state.edit().putBoolean("copied", true).commit())
            }
            check(context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1) { "Cannot publish pending video" }
        } else {
            val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir, "ScreenRecorder").apply { check(isDirectory || mkdirs()) }
            val pending = File(directory, ".$name.pending")
            staging.inputStream().use { input ->
                pending.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
            }
            check(pending.renameTo(File(directory, name))) { "Cannot publish private video" }
        }
    }

    private fun saveDestination(context: Context, uri: Uri) {
        check(prefs(context).edit().putString("output_uri", uri.toString())
            .putBoolean("copied", false).commit()) { "Cannot persist pending destination" }
    }

    private fun copyTo(context: Context, file: File, uri: Uri) {
        val descriptor = checkNotNull(context.contentResolver.openFileDescriptor(uri, "rwt"))
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            file.inputStream().use { it.copyTo(output) }
            output.flush()
            // SAF providers may return a pipe. Close still commits it; regular files can be synced.
            try { output.fd.sync() } catch (_: java.io.SyncFailedException) {}
        }
    }

    private fun rememberNotice(context: Context, message: Int) {
        // Store a stable key, not translated text or a resource ID that may change after an update.
        val key = context.resources.getResourceEntryName(message)
        check(prefs(context).edit().putString("notice", key).commit()) { "Cannot persist recovery notice" }
    }

    fun notice(context: Context): String? {
        val key = prefs(context).getString("notice", null) ?: return null
        val resource = when (key) {
            "RecordingSessionStore_recovered" -> R.string.RecordingSessionStore_recovered
            "RecordingSessionStore_removed" -> R.string.RecordingSessionStore_removed
            "RecordingSessionStore_memory_saved" -> R.string.RecordingSessionStore_memory_saved
            else -> R.string.RecordingSessionStore_retry
        }
        return context.getString(resource)
    }

    fun acknowledgeNotice(context: Context, expectedMessage: String? = notice(context)) {
        if (notice(context) != expectedMessage) return
        prefs(context).edit().remove("notice").commit()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun showNotification(context: Context) {
        val text = notice(context) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.RecordingSessionStore_title), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_screen_record)
                .setContentTitle(context.getString(R.string.RecordingSessionStore_title))
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(intent).setAutoCancel(true).build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification unavailable; recovery notice will appear in the app", e)
        }
    }
}
