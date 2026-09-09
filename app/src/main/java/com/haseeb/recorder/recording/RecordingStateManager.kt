package com.haseeb.recorder.recording

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.haseeb.recorder.camera.CameraOverlayService
import com.haseeb.recorder.draw.DrawOverlayService
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Central singleton orchestrating recording states, live chronometer time, and control actions.
 */
object RecordingStateManager {

    /*
     * Callback interface for observing changes in recording states and options.
     */
    interface StateListener {
        fun onStateChanged()
    }

    private val listeners = CopyOnWriteArrayList<StateListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var isRecording: Boolean = false
        private set

    @Volatile var isPaused: Boolean = false
        private set

    @Volatile var isMuted: Boolean = false
        private set

    @Volatile var recordingStartRealtime: Long = 0L
        private set

    @Volatile var pauseStartRealtime: Long = 0L
        private set

    @Volatile var totalPausedDurationMs: Long = 0L
        private set

    val isCameraActive: Boolean
        get() = CameraOverlayService.isCameraRunning

    val isDrawActive: Boolean
        get() = DrawOverlayService.isDrawRunning

    /*
     * Registers a state change observer listener.
     */
    fun addListener(listener: StateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /*
     * Unregisters a previously registered state change observer listener.
     */
    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

    /*
     * Dispatches state change notification on the main looper thread.
     */
    fun notifyListeners() {
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onStateChanged()
                } catch (_: Exception) {}
            }
        }
    }

    /*
     * Returns total active elapsed recording duration in milliseconds excluding pauses.
     */
    fun getElapsedMillis(): Long {
        if (!isRecording || recordingStartRealtime == 0L) return 0L
        return if (isPaused) {
            (pauseStartRealtime - recordingStartRealtime - totalPausedDurationMs).coerceAtLeast(0L)
        } else {
            (SystemClock.elapsedRealtime() - recordingStartRealtime - totalPausedDurationMs).coerceAtLeast(0L)
        }
    }

    /*
     * Calculates the exact base time for Android Chronometer widgets.
     */
    fun getChronometerBase(): Long {
        if (!isRecording) return SystemClock.elapsedRealtime()
        return if (isPaused) {
            SystemClock.elapsedRealtime() - getElapsedMillis()
        } else {
            recordingStartRealtime + totalPausedDurationMs
        }
    }

    /*
     * Records session initialization parameters and informs all active observers.
     */
    fun onRecordingStarted(startTimeRealtime: Long, initialMuted: Boolean) {
        isRecording = true
        isPaused = false
        isMuted = initialMuted
        recordingStartRealtime = startTimeRealtime
        pauseStartRealtime = 0L
        totalPausedDurationMs = 0L
        notifyListeners()
    }

    /*
     * Updates paused state timestamps when recording is paused.
     */
    fun onRecordingPaused() {
        if (!isRecording || isPaused) return
        isPaused = true
        pauseStartRealtime = SystemClock.elapsedRealtime()
        notifyListeners()
    }

    /*
     * Accumulates pause duration and updates state timestamps upon resume.
     */
    fun onRecordingResumed() {
        if (!isRecording || !isPaused) return
        val now = SystemClock.elapsedRealtime()
        totalPausedDurationMs += (now - pauseStartRealtime).coerceAtLeast(0L)
        isPaused = false
        pauseStartRealtime = 0L
        notifyListeners()
    }

    /*
     * Updates microphone mute state dynamically.
     */
    fun onMuteToggled(muted: Boolean) {
        isMuted = muted
        notifyListeners()
    }

    /*
     * Resets all internal tracking states when recording finishes.
     */
    fun onRecordingStopped() {
        isRecording = false
        isPaused = false
        isMuted = false
        recordingStartRealtime = 0L
        pauseStartRealtime = 0L
        totalPausedDurationMs = 0L
        notifyListeners()
    }

    /*
     * Dispatches pause or resume command to the recording service.
     */
    fun togglePause(context: Context) {
        val action = if (isPaused) ScreenRecordService.ACTION_RESUME else ScreenRecordService.ACTION_PAUSE
        val intent = Intent(context, ScreenRecordService::class.java).apply { this.action = action }
        context.startService(intent)
    }

    /*
     * Dispatches microphone mute toggle command to the recording service.
     */
    fun toggleMute(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_TOGGLE_MUTE }
        context.startService(intent)
    }

    /*
     * Dispatches floating camera toggle command to the recording service.
     */
    fun toggleCamera(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_TOGGLE_CAMERA }
        context.startService(intent)
    }

    /*
     * Dispatches drawing tool toggle command to the recording service.
     */
    fun toggleDraw(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_TOGGLE_DRAW }
        context.startService(intent)
    }

    /*
     * Dispatches stop and save recording command to the recording service.
     */
    fun stopRecording(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_STOP }
        context.startService(intent)
    }
}
