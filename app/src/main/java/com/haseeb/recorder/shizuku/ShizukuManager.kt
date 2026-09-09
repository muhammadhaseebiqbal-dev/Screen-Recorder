package com.haseeb.recorder.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/*
 * Manages Shizuku privileged service interactions, permission requests, and shell execution.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    const val REQUEST_CODE_PERMISSION = 7001

    private val permissionListeners = mutableListOf<(Boolean) -> Unit>()
    private val binderListeners = mutableListOf<() -> Unit>()

    private val internalPermissionListener = Shizuku.OnRequestPermissionResultListener { reqCode, grantResult ->
        if (reqCode == REQUEST_CODE_PERMISSION) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            notifyPermissionChanged(granted)
        }
    }

    private val internalBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        notifyBinderChanged()
        requestPermissionSilent()
    }

    private val internalBinderDeadListener = Shizuku.OnBinderDeadListener {
        notifyBinderChanged()
    }

    /*
     * Initializes Shizuku lifecycle listeners and silently requests permission if available.
     */
    fun init() {
        try {
            Shizuku.addBinderReceivedListener(internalBinderReceivedListener)
            Shizuku.addBinderDeadListener(internalBinderDeadListener)
            Shizuku.addRequestPermissionResultListener(internalPermissionListener)
            if (isShizukuRunning()) {
                requestPermissionSilent()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku init failed: ${e.message}")
        }
    }

    /*
     * Verifies if Shizuku server binder is active and accessible.
     */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.e(TAG, "pingBinder failed: ${e.message}")
            false
        }
    }

    /*
     * Checks if privileged Shizuku permission has been granted to the application.
     */
    fun isPermissionGranted(): Boolean {
        return try {
            if (!isShizukuRunning()) return false
            if (Shizuku.isPreV11()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkSelfPermission failed: ${e.message}")
            false
        }
    }

    /*
     * Silently triggers the Shizuku permission prompt if permission is not yet granted.
     */
    fun requestPermissionSilent() {
        try {
            if (isShizukuRunning() && !isPermissionGranted()) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    Log.d(TAG, "Shizuku permission rationale indicated")
                }
                Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed: ${e.message}")
        }
    }

    /*
     * Explicitly requests Shizuku permission when initiated by user action.
     */
    fun requestPermissionExplicit() {
        try {
            if (isShizukuRunning()) {
                Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Explicit requestPermission failed: ${e.message}")
        }
    }

    /*
     * Subscribes a listener to receive Shizuku permission state updates.
     */
    fun addPermissionListener(listener: (Boolean) -> Unit) {
        if (!permissionListeners.contains(listener)) {
            permissionListeners.add(listener)
        }
    }

    /*
     * Unsubscribes a previously registered Shizuku permission listener.
     */
    fun removePermissionListener(listener: (Boolean) -> Unit) {
        permissionListeners.remove(listener)
    }

    /*
     * Subscribes a listener to receive Shizuku binder connection changes.
     */
    fun addBinderListener(listener: () -> Unit) {
        if (!binderListeners.contains(listener)) {
            binderListeners.add(listener)
        }
    }

    /*
     * Unsubscribes a previously registered Shizuku binder listener.
     */
    fun removeBinderListener(listener: () -> Unit) {
        binderListeners.remove(listener)
    }

    /*
     * Notifies all registered subscribers of permission grant changes.
     */
    private fun notifyPermissionChanged(granted: Boolean) {
        permissionListeners.forEach { it.invoke(granted) }
    }

    /*
     * Notifies all registered subscribers of binder state transitions.
     */
    private fun notifyBinderChanged() {
        binderListeners.forEach { it.invoke() }
    }

    /*
     * Executes an arbitrary shell command using Shizuku privileged process.
     */
    fun executeShell(command: String): Boolean {
        if (!isPermissionGranted()) return false
        return try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply {
                isAccessible = true
            }
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            Log.e(TAG, "executeShell failed for command '$command': ${e.message}")
            false
        }
    }

    /*
     * Toggles system touch visual feedback via privileged shell command.
     */
    fun setShowTouches(enabled: Boolean): Boolean {
        val value = if (enabled) 1 else 0
        return executeShell("settings put system show_touches $value")
    }

    /*
     * Toggles system status and navigation bar visibility for clean recording.
     */
    fun setImmersiveMode(enabled: Boolean): Boolean {
        return if (enabled) {
            executeShell("settings put global policy_control immersive.full=*")
        } else {
            executeShell("settings put global policy_control null* || settings delete global policy_control")
        }
    }

    /*
     * Grants enhanced audio output capture privileges via Shizuku shell.
     */
    fun grantEnhancedAudioPermission(context: Context): Boolean {
        val p1 = executeShell("pm grant ${context.packageName} android.permission.CAPTURE_AUDIO_OUTPUT")
        val p2 = executeShell("cmd appops set ${context.packageName} CAPTURE_AUDIO_OUTPUT allow")
        val p3 = executeShell("cmd appops set ${context.packageName} RECORD_AUDIO allow")
        return p1 || p2 || p3
    }

    /*
     * Grants project media permissions to facilitate direct recording initiation.
     */
    fun grantProjectMediaPermission(context: Context): Boolean {
        val p1 = executeShell("pm grant ${context.packageName} android.permission.PROJECT_MEDIA")
        val p2 = executeShell("cmd appops set ${context.packageName} PROJECT_MEDIA allow")
        val p3 = executeShell("appops set ${context.packageName} 46 allow")
        return p1 || p2 || p3
    }
}
