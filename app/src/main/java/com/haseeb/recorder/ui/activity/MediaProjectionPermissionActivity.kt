package com.haseeb.recorder.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import com.haseeb.recorder.R
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.recording.ScreenRecordService
import com.haseeb.recorder.recording.RecordingCountdown
import com.haseeb.recorder.privileged.PrivilegedAccessManager
import com.haseeb.recorder.util.LocaleHelper

/*
 * Transparent gateway activity that validates all required permissions
 * before launching the screen recording service.
 * Handles background-to-foreground transition to prevent Android 14+ crash
 * when starting MediaProjection from a background activity.
 */
open class MediaProjectionPermissionActivity : Activity() {

    companion object {
        private const val TAG = "MediaProjectionActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_RUNTIME_PERMISSIONS = 1002
    }

    private var pendingResultCode: Int = -1
    private var pendingData: Intent? = null
    private var isProjectionRequestPending = false
    private var isPreparingProjection = false
    private var isActivityResumed = false
    private var isRuntimePermissionRequestPending = false
    private var countdown: RecordingCountdown? = null
    private var countdownView: TextView? = null
    private var serviceStarted = false
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownTick = object : Runnable {
        override fun run() {
            if (!isActivityResumed || isFinishing || isDestroyed || pendingData == null || serviceStarted) return
            val seconds = countdown?.seconds(SystemClock.elapsedRealtime()) ?: return
            if (seconds > 0) {
                countdownView?.text = seconds.toString()
                countdownHandler.postDelayed(this, 100)
            } else {
                window.decorView.alpha = 0f
                countdownHandler.postDelayed(startServiceAfterCountdown, 100)
            }
        }
    }
    private val startServiceAfterCountdown = Runnable {
        if (isActivityResumed && !isFinishing && !isDestroyed && !serviceStarted) {
            pendingData?.let { startRecordingService(pendingResultCode, it) }
        }
    }

    private lateinit var configManager: ConfigManager

    /*
     * Wraps the base context with the user-selected locale configuration.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    /*
     * Initializes the activity, reads intent extras, and starts the permission validation flow.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)
        handleIntentExtras(intent)
        if (savedInstanceState != null) {
            pendingResultCode = savedInstanceState.getInt("projection_result", RESULT_CANCELED)
            @Suppress("DEPRECATION")
            pendingData = savedInstanceState.getParcelable("projection_data")
            isProjectionRequestPending = savedInstanceState.getBoolean("projection_pending")
            isRuntimePermissionRequestPending = savedInstanceState.getBoolean("runtime_pending")
            if (pendingData != null) {
                countdown = RecordingCountdown(savedInstanceState.getLong("countdown_remaining"))
            }
        }
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
        if (isFinishing || isDestroyed) return
        if (isProjectionRequestPending || isPreparingProjection || isRuntimePermissionRequestPending || pendingData != null || serviceStarted) return
        if (!checkRuntimePermissions()) return
        if (!checkOverlayPermission()) return
        if (PrivilegedAccessManager.isPermissionGranted() && configManager.isShizukuDirectRecordingEnabled) {
            isPreparingProjection = true
            PrivilegedAccessManager.runAsync {
                PrivilegedAccessManager.grantProjectMediaPermission(applicationContext)
                runOnUiThread {
                    isPreparingProjection = false
                    if (!isFinishing && !isDestroyed) requestMediaProjection()
                }
            }
            return
        }
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
            isRuntimePermissionRequestPending = true
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

    /* Begin the countdown only after the system permission screen has returned. */
    override fun onResume() {
        super.onResume()
        isActivityResumed = true

        if (pendingData != null) {
            startCountdown()
        } else {
            validateAndProceed()
        }
    }

    /*
     * Tracks that the activity has moved to the background.
     */
    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        countdown?.pause(SystemClock.elapsedRealtime())
        countdownHandler.removeCallbacksAndMessages(null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("projection_result", pendingResultCode)
        outState.putParcelable("projection_data", pendingData)
        outState.putBoolean("projection_pending", isProjectionRequestPending)
        outState.putBoolean("runtime_pending", isRuntimePermissionRequestPending)
        outState.putLong("countdown_remaining", countdown?.remainingMillis(SystemClock.elapsedRealtime()) ?: 0)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        countdownHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
            isRuntimePermissionRequestPending = false
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (isActivityResumed) validateAndProceed()
            } else {
                finish()
            }
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
                countdown = RecordingCountdown(configManager.countdownSeconds.coerceAtLeast(0) * 1000L)
                if (isActivityResumed) startCountdown()
            } else {
                finish()
            }
        }
    }

    /*
     * Runs configured countdown before starting recording or starts immediately if disabled.
     * Maintains transparent activity lifecycle to guarantee safe foreground service start.
     */
    private fun startCountdown() {
        if (!isActivityResumed || serviceStarted || isFinishing || isDestroyed) return
        val timer = countdown ?: return
        countdownHandler.removeCallbacksAndMessages(null)
        if (timer.remainingMillis(SystemClock.elapsedRealtime()) > 0) {
            try {
                if (countdownView == null) {
                    setContentView(R.layout.activity_countdown)
                    countdownView = checkNotNull(findViewById<TextView>(R.id.tvCountdown))
                }
                window.decorView.alpha = 1f
            } catch (e: Exception) {
                // Do not silently bypass the user's countdown if its UI cannot be shown.
                Log.e(TAG, "Unable to show recording countdown", e)
                Toast.makeText(this, R.string.recording_countdown_failed, Toast.LENGTH_LONG).show()
                pendingData = null
                finish()
                return
            }
        }
        timer.resume(SystemClock.elapsedRealtime())
        countdownTick.run()
    }

    /*
     * Starts the ScreenRecordService as a foreground service with the projection data.
     */
    private fun startRecordingService(resultCode: Int, data: Intent) {
        if (serviceStarted) return
        serviceStarted = true
        pendingData = null
        countdownHandler.removeCallbacksAndMessages(null)
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
