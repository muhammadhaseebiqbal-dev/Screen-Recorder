package com.haseeb.recorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Acting as a transparent gateway for recording.
 * This class ensures all permissions (Runtime, Overlay, Write Settings) are granted
 * before initiating the Media Projection prompt and starting the service.
 */
class MediaProjectionPermissionActivity : Activity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_RUNTIME_PERMISSIONS = 1002
    }

    private var pendingResultCode: Int = -1
    private var pendingData: Intent? = null
    private var isProjectionRequestPending = false
    private lateinit var configManager: ConfigManager

    /**
     * Initializes activity and handles intent extras from TileService or MainActivity.
     * Starts the sequential permission validation flow.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)

        handleIntentExtras(intent)
        validateAndProceed()
    }

    /**
     * Updates ConfigManager with values passed from the calling component.
     */
    private fun handleIntentExtras(intent: Intent) {
        if (intent.hasExtra("RECORD_MIC")) {
            configManager.isMicEnabled = intent.getBooleanExtra("RECORD_MIC", true)
        }
        if (intent.hasExtra("RECORD_SYSTEM_AUDIO")) {
            configManager.isSystemAudioEnabled = intent.getBooleanExtra("RECORD_SYSTEM_AUDIO", true)
        }
    }

    /**
     * Entry point for permission validation. 
     * It checks each layer of permission and only proceeds if all are granted.
     * Prevents re-triggering if a projection request is already active.
     */
    private fun validateAndProceed() {
        if (isProjectionRequestPending) return

        if (!checkRuntimePermissions()) return
        if (!checkOverlayPermission()) return
        if (!checkWriteSettingsPermission()) return

        requestMediaProjection()
    }

    /**
     * Layer 1: Standard Runtime Permissions (Audio, Notifications, Storage).
     * Adapts to Android 13 (Tiramisu) and above.
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

    /**
     * Layer 2: Overlay Permission (Draw over other apps).
     * Required for floating controls and capturing UI properly.
     */
    private fun checkOverlayPermission(): Boolean {
        return if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            false
        } else {
            true
        }
    }

    /**
     * Layer 3: Write System Settings Permission.
     * Essential for future-proofing features that modify system behavior during recording.
     */
    private fun checkWriteSettingsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                startActivity(intent)
                false
            } else {
                true
            }
        } else {
            true
        }
    }

    /**
     * Final Step before service: Request Screen Capture authorization from the user.
     * Sets a flag to prevent duplicate dialogs during activity lifecycle changes.
     */
    private fun requestMediaProjection() {
        if (isProjectionRequestPending) return

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        projectionManager?.let {
            isProjectionRequestPending = true
            startActivityForResult(it.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } ?: finish()
    }

    /**
     * Re-validates the permission chain when the user returns from system settings pages.
     * Skips validation if a media projection request is already in progress.
     */
    override fun onResume() {
        super.onResume()
        if (pendingData == null && !isProjectionRequestPending) {
            validateAndProceed()
        }
    }

    /**
     * Handles the result of runtime permission requests.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RUNTIME_PERMISSIONS) {
            validateAndProceed()
        }
    }

    /**
     * Handles the result of the Media Projection dialog.
     * Resets the pending flag and initiates the visual countdown on success.
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

    /**
     * Executes a 3-second countdown UI.
     * Hides the window before starting the service to avoid capturing the countdown itself.
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
                        if (!isFinishing && pendingData != null) {
                            startRecordingService(pendingResultCode, pendingData!!)
                        }
                    }, 250)
                }
            }.start()
        } catch (e: Exception) {
            pendingData?.let { startRecordingService(pendingResultCode, it) }
        }
    }

    /**
     * Fires up the ScreenRecordService as a Foreground Service.
     * Future-proofed for Android O+ foreground service requirements.
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
