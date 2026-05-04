package com.haseeb.recorder

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest

/*
 * Transparent gateway activity that validates all required permissions
 * before launching the screen recording service.
 * Handles background-to-foreground transition to prevent Android 14+ crash
 * when starting MediaProjection from a background activity.
 */
class MediaProjectionPermissionActivity : Activity() {

    companion object {
        private const val TAG = "MediaProjectionActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_RUNTIME_PERMISSIONS = 1002
    }

    private var pendingResultCode: Int = -1
    private var pendingData: Intent? = null
    private var isProjectionRequestPending = false
    private var isActivityResumed = false
    private var pendingServiceStart = false

    private lateinit var configManager: ConfigManager

    /*
     * Initializes the activity, reads intent extras, and starts the permission validation flow.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)
        handleIntentExtras(intent)
        validateAndProceed()
    }

    /*
     * Updates ConfigManager with audio settings passed from the calling component.
     */
    private fun handleIntentExtras(intent: Intent) {
        if (intent.hasExtra("RECORD_MIC")) {
            configManager.isMicEnabled = intent.getBooleanExtra("RECORD_MIC", true)
        }
        if (intent.hasExtra("RECORD_SYSTEM_AUDIO")) {
            configManager.isSystemAudioEnabled = intent.getBooleanExtra("RECORD_SYSTEM_AUDIO", true)
        }
    }

    /*
     * Validates all permission layers in order.
     * Stops and waits if any permission is missing.
     */
    private fun validateAndProceed() {
        if (isProjectionRequestPending) return
        if (!checkRuntimePermissions()) return
        if (!checkOverlayPermission()) return
        if (!checkWriteSettingsPermission()) return
        requestMediaProjection()
    }

    /*
     * Checks and requests standard runtime permissions (audio, notifications).
     */
    private fun checkRuntimePermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_RUNTIME_PERMISSIONS)
            false
        } else {
            true
        }
    }

    /*
     * Checks overlay permission required for floating controls.
     */
    private fun checkOverlayPermission(): Boolean {
        return if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            false
        } else {
            true
        }
    }

    /*
     * Checks write system settings permission needed for show-touches feature.
     */
    private fun checkWriteSettingsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            false
        } else {
            true
        }
    }

    /*
     * Requests screen capture authorization from the user.
     * Sets a flag to prevent duplicate dialogs.
     */
    private fun requestMediaProjection() {
        if (isProjectionRequestPending) return
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        projectionManager?.let {
            isProjectionRequestPending = true
            startActivityForResult(it.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } ?: finish()
    }

    /*
     * Tracks that the activity is now in the foreground.
     * If a service start was deferred because the activity was in the background,
     * the window is hidden immediately to prevent showing a stale countdown number,
     * and the service is started safely from the foreground state.
     */
    override fun onResume() {
        super.onResume()
        isActivityResumed = true

        if (pendingServiceStart && pendingData != null) {
            pendingServiceStart = false
            val data = pendingData!!
            pendingData = null
            window?.decorView?.alpha = 0f
            startRecordingService(pendingResultCode, data)
            return
        }

        if (pendingData == null && !isProjectionRequestPending) {
            validateAndProceed()
        }
    }

    /*
     * Tracks that the activity has moved to the background.
     */
    override fun onPause() {
        super.onPause()
        isActivityResumed = false
    }

    /*
     * Handles new intents when activity is brought back using REORDER_TO_FRONT.
     * The pending start is handled in onResume so no action is needed here.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    /*
     * Handles the result of runtime permission requests and re-validates.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RUNTIME_PERMISSIONS) {
            validateAndProceed()
        }
    }

    /*
     * Handles the result of the media projection permission dialog.
     * Resets the pending flag and starts the countdown on success.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            isProjectionRequestPending = false
            if (resultCode == RESULT_OK && data != null) {
                pendingResultCode = resultCode
                pendingData = data
                startCountdown()
            } else {
                finish()
            }
        }
    }

    /*
     * Runs a 3-second countdown UI before starting the recording service.
     * If the activity moves to the background before the countdown ends,
     * the service start is deferred and executed safely when the activity resumes.
     */
    private fun startCountdown() {
        try {
            setContentView(R.layout.activity_countdown)
            val tvCountdown = findViewById<TextView>(R.id.tvCountdown)

            object : CountDownTimer(4000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    tvCountdown?.text = (millisUntilFinished / 1000).toString()
                }

                override fun onFinish() {
                    if (isFinishing) return
                    tvCountdown?.visibility = View.GONE
                    window?.decorView?.alpha = 0f

                    window?.decorView?.postDelayed({
                        if (isFinishing || pendingData == null) return@postDelayed

                        if (isActivityResumed) {
                            startRecordingService(pendingResultCode, pendingData!!)
                        } else {
                            /*
                             * Activity is in background. Mark the start as pending and
                             * bring the activity to foreground. onResume will handle
                             * the service start safely to avoid Android 14+ crash.
                             */
                            pendingServiceStart = true
                            bringActivityToForeground()
                        }
                    }, 250)
                }
            }.start()
        } catch (e: Exception) {
            pendingData?.let { startRecordingService(pendingResultCode, it) }
        }
    }

    /*
     * Brings this activity back to the foreground using moveTaskToFront.
     * Falls back to FLAG_ACTIVITY_REORDER_TO_FRONT if the primary method fails.
     */
    private fun bringActivityToForeground() {
        try {
            val am = getSystemService(ActivityManager::class.java)
            am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
        } catch (e: Exception) {
            Log.w(TAG, "moveTaskToFront failed, using fallback: ${e.message}")
            try {
                val intent = Intent(this, MediaProjectionPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to bring activity to foreground: ${ex.message}")
            }
        }
    }

    /*
     * Starts the ScreenRecordService as a foreground service with the projection data.
     */
    private fun startRecordingService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        finish()
    }
}