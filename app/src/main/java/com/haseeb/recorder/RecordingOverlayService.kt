package com.haseeb.recorder

import android.animation.ValueAnimator
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.view.ContextThemeWrapper
import com.haseeb.recorder.databinding.LayoutRecordingOverlayBinding
import kotlin.math.abs

/*
 * Foreground overlay service that displays a floating recording control panel.
 * Supports drag, expand/collapse, pause/resume, and animated entry.
 */
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
     * Returns null since this service does not support binding.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /*
     * Initializes the window manager, reads screen dimensions, and builds the overlay.
     */
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenBounds()
        createOverlay()
        startCollapseTimer()
    }

    /*
     * Reads the current screen width and height from the window manager.
     */
    private fun updateScreenBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager?.currentWindowMetrics
            screenWidth = metrics?.bounds?.width() ?: 1080
            screenHeight = metrics?.bounds?.height() ?: 1920
        }
    }

    /*
     * Inflates the overlay layout, configures all components, and adds the view
     * to the window manager with a smooth entry animation.
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

        /*
         * Entry animation: fade in with subtle scale and upward slide.
         * Kept short and snappy to avoid any perceived lag on launch.
         */
        view.alpha = 0f
        view.scaleX = 0.88f
        view.scaleY = 0.88f
        view.translationY = -18f
        view.animate()
            .alpha(0.95f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
    }

    /*
     * Configures the WindowManager layout parameters for the floating overlay window.
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
     * Attaches a touch listener that separates drag gestures from tap gestures.
     * Drag moves the window; tap expands the overlay if it is collapsed.
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
     * Collapses the overlay in two sequential phases to prevent animation jitter.
     * Phase 1: fades out the control buttons.
     * Phase 2: slides the window to the right screen edge after layout has settled.
     */
    private fun collapseOverlay() {
        if (!isExpanded || binding == null) return
        isExpanded = false
        updateScreenBounds()

        binding!!.controlsContainer.animate()
            .alpha(0f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding?.controlsContainer?.visibility = View.GONE
                binding?.controlsContainer?.alpha = 1f

                /*
                 * Start the X slide after the layout has fully collapsed so that
                 * both animations do not run at the same time, which causes jitter.
                 */
                binding?.root?.post {
                    val cardWidth = binding?.rootCard?.width ?: 120
                    val targetX = (screenWidth - cardWidth - 12).coerceAtLeast(0)
                    animateWindowPositionX(params.x, targetX)
                }
            }
            .start()
    }

    /*
     * Expands the overlay in two sequential phases.
     * Phase 1: slides the window away from the screen edge.
     * Phase 2: fades in the control buttons after the slide begins.
     */
    private fun expandOverlay() {
        if (isExpanded || binding == null) return
        isExpanded = true
        updateScreenBounds()

        val targetX = (params.x - 220).coerceAtLeast(12)
        animateWindowPositionX(params.x, targetX)

        /*
         * Delay the fade-in slightly so it overlaps the tail end of the slide,
         * giving a smooth staggered feel without sequential lag.
         */
        binding!!.root.postDelayed({
            binding?.controlsContainer?.alpha = 0f
            binding?.controlsContainer?.visibility = View.VISIBLE
            binding?.controlsContainer?.animate()
                ?.alpha(1f)
                ?.setDuration(200)
                ?.setInterpolator(DecelerateInterpolator(2f))
                ?.start()
        }, 80)

        startCollapseTimer()
    }

    /*
     * Animates the window X position smoothly using a ValueAnimator.
     * Cancels any in-progress X animation before starting a new one.
     */
    private fun animateWindowPositionX(startX: Int, endX: Int) {
        windowXAnimator?.cancel()
        windowXAnimator = ValueAnimator.ofInt(startX, endX).apply {
            duration = 220
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                updateViewLayoutSafe()
            }
            start()
        }
    }

    /*
     * Safely updates the window layout and swallows any exception that may
     * occur in edge cases such as the view being detached or the token being invalid.
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
     * Posts the collapse runnable after a 3-second delay.
     * Removes any previously scheduled callbacks to avoid duplicate timers.
     */
    private fun startCollapseTimer() {
        collapseHandler.removeCallbacks(collapseRunnable)
        if (isExpanded) collapseHandler.postDelayed(collapseRunnable, 3000)
    }

    /*
     * Resets the collapse timer whenever the user interacts with the overlay.
     */
    private fun resetCollapseTimer() = startCollapseTimer()

    /*
     * Initializes and starts the chronometer to track recording duration.
     */
    private fun setupTimer() {
        binding?.timer?.apply {
            base = SystemClock.elapsedRealtime()
            start()
        }
    }

    /*
     * Creates and starts the infinite alpha blink animation for the recording dot.
     */
    private fun setupBlinkingDot() {
        redDotAnimator = ValueAnimator.ofFloat(1f, 0.25f).apply {
            duration = 850
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener { binding?.redDot?.alpha = it.animatedValue as Float }
            start()
        }
    }

    /*
     * Attaches click listeners to the pause and stop control buttons.
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
     * Toggles between pause and resume states.
     * Adjusts the chronometer base and swaps the button icon accordingly.
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
     * Sends a control action intent to the ScreenRecordService.
     */
    private fun sendAction(action: String) {
        startService(Intent(this, ScreenRecordService::class.java).apply { this.action = action })
    }

    /*
     * Releases all resources, cancels animations, and removes the overlay view from the window.
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