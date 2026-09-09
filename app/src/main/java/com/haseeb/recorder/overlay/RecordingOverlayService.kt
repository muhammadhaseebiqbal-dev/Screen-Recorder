package com.haseeb.recorder.overlay

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.recording.RecordingStateManager

/*
 * Main overlay controller service orchestrating recording session state and decoupled floating window UIs.
 */
class RecordingOverlayService : Service(), RecordingOverlayController, RecordingStateManager.StateListener {

    companion object {
        const val ACTION_OVERLAY_CONFIG_CHANGED = "com.haseeb.recorder.ACTION_OVERLAY_CONFIG_CHANGED"
    }

    private var windowManager: WindowManager? = null
    private lateinit var configManager: ConfigManager
    private var currentWindow: RecordingOverlayWindow? = null
    private var currentStyle: String = ConfigManager.OVERLAY_STYLE_HORIZONTAL

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_OVERLAY_CONFIG_CHANGED) {
                handleOverlayConfigChange()
            }
        }
    }

    /*
     * Returns null since the overlay operates as an unbound foreground overlay service.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /*
     * Initializes configuration, registers state and broadcast listeners, and creates the overlay window.
     */
    override fun onCreate() {
        super.onCreate()
        configManager = ConfigManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val activeCount = getActiveSubOptionsCount()
        val isRadial = (configManager.overlayStyle == ConfigManager.OVERLAY_STYLE_RADIAL) && (activeCount >= 3)
        currentStyle = if (isRadial) ConfigManager.OVERLAY_STYLE_RADIAL else ConfigManager.OVERLAY_STYLE_HORIZONTAL

        attachOverlayWindow(currentStyle)

        RecordingStateManager.addListener(this)

        val filter = IntentFilter(ACTION_OVERLAY_CONFIG_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(configReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(configReceiver, filter)
        }
    }

    /*
     * Callback from RecordingStateManager when any recording property changes.
     */
    override fun onStateChanged() {
        currentWindow?.onRecordingStateChanged()
    }

    /*
     * Instantiates and presents the requested floating overlay UI window implementation.
     */
    private fun attachOverlayWindow(style: String) {
        val wm = windowManager ?: return
        currentWindow?.dismiss()
        val window: RecordingOverlayWindow = if (style == ConfigManager.OVERLAY_STYLE_RADIAL) {
            RadialOverlayWindow(this, wm, this)
        } else {
            HorizontalOverlayWindow(this, wm, this)
        }
        currentWindow = window
        window.show()
    }

    /*
     * Updates overlay style or control visibility dynamically without restarting recording.
     */
    private fun handleOverlayConfigChange() {
        if (!configManager.isRecordingOverlayEnabled) {
            stopSelf()
            return
        }

        val activeCount = getActiveSubOptionsCount()
        val targetRadial = (configManager.overlayStyle == ConfigManager.OVERLAY_STYLE_RADIAL) && (activeCount >= 3)
        val targetStyle = if (targetRadial) ConfigManager.OVERLAY_STYLE_RADIAL else ConfigManager.OVERLAY_STYLE_HORIZONTAL

        if (targetStyle != currentStyle) {
            currentStyle = targetStyle
            attachOverlayWindow(currentStyle)
        } else {
            currentWindow?.onConfigurationChanged()
        }
    }

    /*
     * Calculates the count of active action button options configured by the user.
     */
    private fun getActiveSubOptionsCount(): Int {
        var count = 0
        if (configManager.isOverlayShowPauseEnabled) count++
        if (configManager.isOverlayShowStopEnabled) count++
        if (configManager.isOverlayShowMuteEnabled) count++
        if (configManager.isOverlayShowCameraEnabled) count++
        if (configManager.isOverlayShowDrawEnabled) count++
        return count
    }

    /*
     * Retrieves the base timestamp in milliseconds used for chronometer synchronization.
     */
    override fun getChronometerBase(): Long = RecordingStateManager.getChronometerBase()

    /*
     * Returns whether the active recording session is currently paused.
     */
    override fun isPaused(): Boolean = RecordingStateManager.isPaused

    /*
     * Returns whether microphone audio recording is currently muted.
     */
    override fun isMuted(): Boolean = RecordingStateManager.isMuted

    /*
     * Returns whether floating camera preview overlay is currently active.
     */
    override fun isCameraActive(): Boolean = RecordingStateManager.isCameraActive

    /*
     * Returns whether screen annotation drawing overlay is currently active.
     */
    override fun isDrawActive(): Boolean = RecordingStateManager.isDrawActive

    /*
     * Returns whether the pause control button should be displayed in the overlay.
     */
    override fun isShowPauseEnabled(): Boolean = configManager.isOverlayShowPauseEnabled

    /*
     * Returns whether the stop control button should be displayed in the overlay.
     */
    override fun isShowStopEnabled(): Boolean = configManager.isOverlayShowStopEnabled

    /*
     * Returns whether the microphone mute control button should be displayed in the overlay.
     */
    override fun isShowMuteEnabled(): Boolean = configManager.isOverlayShowMuteEnabled

    /*
     * Returns whether the camera preview toggle button should be displayed in the overlay.
     */
    override fun isShowCameraEnabled(): Boolean = configManager.isOverlayShowCameraEnabled

    /*
     * Returns whether the screen draw toggle button should be displayed in the overlay.
     */
    override fun isShowDrawEnabled(): Boolean = configManager.isOverlayShowDrawEnabled

    /*
     * Invoked when the pause or resume control action is triggered by the user.
     */
    override fun onPauseClicked() {
        RecordingStateManager.togglePause(this)
    }

    /*
     * Invoked when the microphone mute or unmute control action is triggered by the user.
     */
    override fun onMuteClicked() {
        RecordingStateManager.toggleMute(this)
    }

    /*
     * Invoked when the camera preview toggle control action is triggered by the user.
     */
    override fun onCameraClicked() {
        RecordingStateManager.toggleCamera(this)
    }

    /*
     * Invoked when the screen annotation draw tool action is triggered by the user.
     */
    override fun onDrawClicked() {
        RecordingStateManager.toggleDraw(this)
    }

    /*
     * Invoked when the stop recording action is triggered by the user.
     */
    override fun onStopClicked() {
        RecordingStateManager.stopRecording(this)
    }

    /*
     * Cleans up receivers, listeners, and dismisses active floating window upon destruction.
     */
    override fun onDestroy() {
        super.onDestroy()
        RecordingStateManager.removeListener(this)

        try {
            unregisterReceiver(configReceiver)
        } catch (_: Exception) {}

        currentWindow?.dismiss()
        currentWindow = null
        windowManager = null
    }
}
