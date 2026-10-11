package com.haseeb.recorder.ui.activity

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.haseeb.recorder.R
import com.haseeb.recorder.camera.CameraOverlayService
import com.haseeb.recorder.recording.RecordingStateManager
import com.haseeb.recorder.util.LocaleHelper

/** Establish a resumed activity before starting a service with while-in-use camera access. */
class CameraPermissionActivity : Activity() {
    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, CameraPermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var permissionPending = false
    private val timeout = Runnable { finish() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreation must not interpret an already-started preview as a new toggle-off.
        if (savedInstanceState?.getBoolean("camera_start_dispatched") == true) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("camera_start_dispatched", started)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || started || permissionPending) return
        if (!RecordingStateManager.isRecording) { finish(); return }
        if (CameraOverlayService.isCameraRunning) {
            stopService(Intent(this, CameraOverlayService::class.java))
            finish()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.camera_preview_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        started = true
        val receiver = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (!isDestroyed) finish()
            }
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, CameraOverlayService::class.java).apply {
                action = CameraOverlayService.ACTION_START
                putExtra(CameraOverlayService.EXTRA_READY_RECEIVER, receiver)
            })
            // Keep the activity resumed until the service has promoted itself to foreground.
            handler.postDelayed(timeout, 5000)
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.camera_preview_unavailable, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionPending = false
        if (requestCode == 1 && (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.camera_preview_permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
        // A granted permission proceeds in onResume, after the permission dialog closes.
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
