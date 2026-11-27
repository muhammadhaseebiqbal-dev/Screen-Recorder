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

/** Persists and surfaces system-audio capture warnings when playback capture is unavailable or blocked. */
internal object SystemAudioWarningStore {
    private const val TAG = "SystemAudioWarningStore"
    private const val PREFS = "system_audio_warnings"
    private const val KEY_WARNING = "warning_key"
    private const val CHANNEL_ID = "system_audio_warnings"
    private const val NOTIFICATION_ID = 5

    fun report(context: Context, diagnostic: SystemAudioPolicy.Diagnostic) {
        if (diagnostic == SystemAudioPolicy.Diagnostic.NONE) return
        val app = context.applicationContext
        val key = when (diagnostic) {
            SystemAudioPolicy.Diagnostic.UNAVAILABLE -> "ScreenRecordService_warn_sys_audio_unavailable"
            SystemAudioPolicy.Diagnostic.RESTRICTED_BY_APP -> "ScreenRecordService_warn_sys_audio_restricted"
            SystemAudioPolicy.Diagnostic.NON_MEDIA_USAGE -> "ScreenRecordService_warn_sys_audio_usage"
            SystemAudioPolicy.Diagnostic.NONE -> return
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
            "ScreenRecordService_warn_sys_audio_restricted" -> R.string.ScreenRecordService_warn_sys_audio_restricted
            "ScreenRecordService_warn_sys_audio_usage" -> R.string.ScreenRecordService_warn_sys_audio_usage
            else -> R.string.ScreenRecordService_warn_sys_audio_unavailable
        }
        return context.getString(resId)
    }

    private fun showNotification(context: Context, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ScreenRecordService_warn_sys_audio_title),
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
                    .setContentTitle(context.getString(R.string.ScreenRecordService_warn_sys_audio_title))
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification unavailable; system audio warning remains persisted for MainActivity", e)
        }
    }
}
