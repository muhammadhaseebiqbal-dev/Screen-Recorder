package com.haseeb.recorder.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.haseeb.recorder.R
import com.haseeb.recorder.recording.VoipAudioDiagnostics.CaptureIssue
import com.haseeb.recorder.ui.activity.MainActivity

/** Persists and displays user-facing notices when Android isolates VoIP or protected app audio. */
internal object VoipAudioWarningStore {
    private const val TAG = "VoipAudioWarningStore"
    private const val PREFS = "voip_audio_warnings"
    private const val KEY_ISSUE = "issue_name"
    private const val CHANNEL_ID = "voip_audio_warnings"
    private const val NOTIFICATION_ID = 7

    fun report(context: Context, issue: CaptureIssue) {
        if (issue == CaptureIssue.NONE) return
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ISSUE, issue.name)
            .commit()
        showNotification(app, messageForIssue(app, issue))
    }

    fun pendingWarning(context: Context): String? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ISSUE, null) ?: return null
        val issue = runCatching { CaptureIssue.valueOf(raw) }.getOrNull() ?: return null
        if (issue == CaptureIssue.NONE) return null
        return messageForIssue(context, issue)
    }

    fun acknowledgeWarning(context: Context, expectedMessage: String? = pendingWarning(context)) {
        if (pendingWarning(context) != expectedMessage) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ISSUE).commit()
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun messageForIssue(context: Context, issue: CaptureIssue): String {
        val resId = when (issue) {
            CaptureIssue.VOICE_CALL_RESTRICTED -> R.string.VoipAudio_warn_voice_call
            CaptureIssue.APP_CAPTURE_BLOCKED -> R.string.VoipAudio_warn_app_blocked
            CaptureIssue.NONE -> return ""
        }
        return context.getString(resId)
    }

    private fun showNotification(context: Context, message: String) {
        if (message.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.VoipAudio_warn_title),
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
                    .setContentTitle(context.getString(R.string.VoipAudio_warn_title))
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification blocked; warning saved for MainActivity", e)
        }
    }
}
