package com.haseeb.recorder

import android.animation.ValueAnimator
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.view.ContextThemeWrapper
import com.haseeb.recorder.databinding.LayoutRecordingOverlayBinding
import kotlin.math.abs

class RecordingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var binding: LayoutRecordingOverlayBinding? = null
    private var redDotAnimator: ValueAnimator? = null
    private var windowXAnimator: ValueAnimator? = null
    private lateinit var params: WindowManager.LayoutParams

    private var screenWidth = 0
    private var screenHeight = 0
    private var pausedElapsedMs = 0L
    private var isExpanded = true

    private val collapseHandler = Handler(Looper.getMainLooper())
    private val collapseRunnable = Runnable { collapseOverlay() }

    /*
     * Binds the service to an intent.
     * Returns null as this service operates independently without binding to an activity.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /*
     * Initializes the service, retrieves the window manager system service.
     * Calculates initial screen boundaries and triggers the creation of the overlay interface.
     */
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenBounds()
        createOverlay()
        startCollapseTimer()
    }

    /*
     * Retrieves the current screen width and height using the modern WindowMetrics API.
     * Provides fallback values to prevent crashes if metrics are unavailable.
     */
    private fun updateScreenBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager?.currentWindowMetrics
            screenWidth = metrics?.bounds?.width() ?: 1080
            screenHeight = metrics?.bounds?.height() ?: 1920
        }
    }

    /*
     * Inflates the overlay layout with the defined application theme.
     * Initializes all UI components, sets layout parameters, applies drag logic, and adds the view to the window manager.
     */
    private fun createOverlay() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_ScreenRecorder)
        binding = LayoutRecordingOverlayBinding.inflate(LayoutInflater.from(themedContext))
        val view = binding!!.root

        setupLayoutParams()
        setupTimer()
        setupBlinkingDot()
        setupButtons()
        applyDragLogic(view)

        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        params.x = (screenWidth - view.measuredWidth) / 2

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        view.alpha = 0f
        view.scaleX = 0.7f
        view.scaleY = 0.7f
        view.animate().alpha(0.9f).scaleX(1f).scaleY(1f)
            .setDuration(400)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    /*
     * Configures the WindowManager layout parameters for the floating overlay.
     * Makes the overlay float above other apps and sets its initial placement.
     */
    private fun setupLayoutParams() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = 100
        }
    }

    /*
     * Implements the touch listener for the main floating view to enable dragging.
     * Distinguishes between dragging and clicking to prevent accidental expansions after a drag.
     */
    private fun applyDragLogic(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            resetCollapseTimer()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                        params.x = (initialX + dx).coerceIn(0, screenWidth - view.width)
                        params.y = (initialY + dy).coerceIn(0, screenHeight - view.height)
                        updateViewLayoutSafe()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (!isExpanded) expandOverlay()
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /*
     * Collapses the overlay to hide the control buttons and show only the timer.
     * Syncs layout bounds transition with a custom X-axis animation to prevent visual jitter.
     */
    private fun collapseOverlay() {
        if (!isExpanded || binding == null) return
        
        updateScreenBounds()
        
        val transition = AutoTransition().apply { 
            duration = 300 
            interpolator = DecelerateInterpolator()
        }
        TransitionManager.beginDelayedTransition(binding!!.rootCard, transition)
        
        binding!!.controlsContainer.visibility = View.GONE
        isExpanded = false

        val targetX = screenWidth - 140 
        animateWindowPositionX(params.x, targetX)
    }

    /*
     * Expands the overlay to show all recording controls.
     * Animates the expansion smoothly while moving the window away from the screen edge.
     */
    private fun expandOverlay() {
        if (isExpanded || binding == null) return
        
        updateScreenBounds()
        
        val transition = AutoTransition().apply { 
            duration = 300 
            interpolator = DecelerateInterpolator()
        }
        TransitionManager.beginDelayedTransition(binding!!.rootCard, transition)
        
        binding!!.controlsContainer.visibility = View.VISIBLE
        isExpanded = true
        
        val targetX = (params.x - 200).coerceAtLeast(0)
        animateWindowPositionX(params.x, targetX)
        startCollapseTimer()
    }

    /*
     * Animates the X coordinate of the floating window across the screen over time.
     * Ensures the window moves at the exact same pace as the width transition to eliminate UI glitches.
     */
    private fun animateWindowPositionX(startX: Int, endX: Int) {
        windowXAnimator?.cancel()
        windowXAnimator = ValueAnimator.ofInt(startX, endX).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                updateViewLayoutSafe()
            }
            start()
        }
    }

    /*
     * Safely updates the view layout inside the WindowManager.
     * Uses a try-catch block to prevent application crashes in edge cases or custom Android OS environments.
     */
    private fun updateViewLayoutSafe() {
        try {
            if (binding?.root?.windowToken != null) {
                windowManager?.updateViewLayout(binding?.root, params)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /*
     * Triggers the collapse runnable to execute after a delay of three seconds.
     * Removes any existing callbacks to prevent multiple timers from running simultaneously.
     */
    private fun startCollapseTimer() {
        collapseHandler.removeCallbacks(collapseRunnable)
        if (isExpanded) collapseHandler.postDelayed(collapseRunnable, 3000)
    }

    /*
     * Resets the active collapse timer.
     * Called whenever the user interacts with the floating window to keep it expanded.
     */
    private fun resetCollapseTimer() = startCollapseTimer()

    /*
     * Initializes and starts the chronometer to display recording duration.
     */
    private fun setupTimer() {
        binding?.timer?.apply {
            base = SystemClock.elapsedRealtime()
            start()
        }
    }

    /*
     * Configures the infinite blinking animation for the recording indicator dot.
     */
    private fun setupBlinkingDot() {
        redDotAnimator = ValueAnimator.ofFloat(1f, 0.3f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { binding?.redDot?.alpha = it.animatedValue as Float }
            start()
        }
    }

    /*
     * Binds standard click listeners to the pause and stop buttons.
     */
    private fun setupButtons() {
        binding?.btnPause?.setOnClickListener { 
            resetCollapseTimer()
            handlePauseResume() 
        }
        binding?.btnStop?.setOnClickListener { 
            sendAction(ScreenRecordService.ACTION_STOP) 
        }
    }

    /*
     * Handles the logic for pausing and resuming the screen recording.
     * Updates the chronometer base time and swaps the play and pause icons accordingly.
     */
    private fun handlePauseResume() {
        val timer = binding?.timer ?: return
        if (ScreenRecordService.isPaused) {
            sendAction(ScreenRecordService.ACTION_RESUME)
            binding?.btnPause?.setIconResource(R.drawable.ic_pause)
            timer.base = SystemClock.elapsedRealtime() - pausedElapsedMs
            timer.start()
            redDotAnimator?.start()
        } else {
            pausedElapsedMs = SystemClock.elapsedRealtime() - timer.base
            sendAction(ScreenRecordService.ACTION_PAUSE)
            binding?.btnPause?.setIconResource(R.drawable.ic_play)
            timer.stop()
            redDotAnimator?.cancel()
            binding?.redDot?.alpha = 1f
        }
    }

    /*
     * Sends an action intent to the main screen recording service to perform tasks.
     */
    private fun sendAction(action: String) {
        startService(Intent(this, ScreenRecordService::class.java).apply { this.action = action })
    }

    /*
     * Cleans up all ongoing operations, handlers, animations, and removes the view upon service destruction.
     */
    override fun onDestroy() {
        super.onDestroy()
        collapseHandler.removeCallbacks(collapseRunnable)
        redDotAnimator?.cancel()
        windowXAnimator?.cancel()
        binding?.root?.let {
            try { 
                windowManager?.removeView(it) 
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        binding = null
    }
}
