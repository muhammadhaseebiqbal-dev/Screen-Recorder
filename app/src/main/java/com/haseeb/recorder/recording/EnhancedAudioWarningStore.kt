package com.haseeb.recorder.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.haseeb.recorder.R
import com.haseeb.recorder.recording.EnhancedAudioDiagnostics.BlockReason
import com.haseeb.recorder.ui.activity.MainActivity

/** Persists and surfaces actionable diagnostics when Android restricts internal audio capture. */
internal object EnhancedAudioWarningStore {
    private const val TAG = "EnhancedAudioWarningStore"
    private const val PREFS = "enhanced_audio_warnings"
    private const val KEY_REASON = "reason_name"
    private const val CHANNEL_ID = "enhanced_audio_warnings"
    private const val NOTIFICATION_ID = 6

    fun report(context: Context, reason: BlockReason) {
        if (reason == BlockReason.NONE) return
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REASON, reason.name)
            .commit()
        showNotification(app, messageForReason(app, reason))
    }

    fun pendingWarning(context: Context): String? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REASON, null) ?: return null
        val reason = runCatching { BlockReason.valueOf(raw) }.getOrNull() ?: return null
        if (reason == BlockReason.NONE) return null
        return messageForReason(context, reason)
    }

    fun acknowledgeWarning(context: Context, expectedMessage: String? = pendingWarning(context)) {
        if (pendingWarning(context) != expectedMessage) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_REASON).commit()
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun messageForReason(context: Context, reason: BlockReason): String {
        val resId = when (reason) {
            BlockReason.ALLOW_CAPTURE_BY_NONE -> R.string.EnhancedAudio_warn_by_none
            BlockReason.ALLOW_CAPTURE_BY_SYSTEM_NO_PRIVILEGE -> R.string.EnhancedAudio_warn_by_system
            BlockReason.NON_CAPTURABLE_USAGE -> R.string.EnhancedAudio_warn_non_media
            BlockReason.PRIVILEGED_BACKEND_UNAVAILABLE -> R.string.EnhancedAudio_warn_backend_unavailable
            BlockReason.NONE -> return ""
        }
        return context.getString(resId)
    }

    private fun showNotification(context: Context, message: String) {
        if (message.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.EnhancedAudio_warn_title),
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
                    .setContentTitle(context.getString(R.string.EnhancedAudio_warn_title))
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification suppressed; warning stored for UI display", e)
        }
    }
}
