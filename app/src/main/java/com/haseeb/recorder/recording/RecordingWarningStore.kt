package com.haseeb.recorder.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.haseeb.recorder.R
import com.haseeb.recorder.ui.activity.MainActivity

/** Persists and surfaces video-stall / dropped-frame warnings so silent partial files never pass unnoticed. */
internal object RecordingWarningStore {
    private const val TAG = "RecordingWarningStore"
    private const val PREFS = "recording_warnings"
    private const val KEY_WARNING = "warning_key"
    private const val CHANNEL_ID = "recording_warnings"
    private const val NOTIFICATION_ID = 4

    fun reportVideoDrop(context: Context, warning: RecordingTrackHealth.Warning) {
        if (warning == RecordingTrackHealth.Warning.NONE) return
        val app = context.applicationContext
        val key = when (warning) {
            RecordingTrackHealth.Warning.AUDIO_ONLY_TAIL -> "ScreenRecordService_warn_video_tail"
            RecordingTrackHealth.Warning.VIDEO_STALLED -> "ScreenRecordService_warn_video_gap"
            RecordingTrackHealth.Warning.NONE -> return
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WARNING, key).commit()
        showNotification(app, messageForKey(app, key))
    }

    fun pendingWarning(context: Context): String? {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WARNING, null) ?: return null
        return messageForKey(context, key)
    }

    fun acknowledgeWarning(context: Context, expectedMessage: String? = pendingWarning(context)) {
        if (pendingWarning(context) != expectedMessage) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_WARNING).commit()
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun messageForKey(context: Context, key: String): String {
        val resId = when (key) {
            "ScreenRecordService_warn_video_tail" -> R.string.ScreenRecordService_warn_video_tail
            else -> R.string.ScreenRecordService_warn_video_gap
        }
        return context.getString(resId)
    }

    private fun showNotification(context: Context, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ScreenRecordService_warn_video_title),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val intent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_screen_record)
                    .setContentTitle(context.getString(R.string.ScreenRecordService_warn_video_title))
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification unavailable; warning remains persisted for MainActivity", e)
        }
    }
}
