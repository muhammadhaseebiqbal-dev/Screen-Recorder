package com.haseeb.recorder.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import com.haseeb.recorder.R
import com.haseeb.recorder.databinding.OverlayRecordingBinding
import kotlin.math.abs

/*
 * Horizontal floating bar overlay implementation displaying expandable recording control buttons.
 */
class HorizontalOverlayWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val controller: RecordingOverlayController
) : RecordingOverlayWindow {

    private var binding: OverlayRecordingBinding? = null
    private var rootView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var isExpanded = false

    private var redDotAnimator: ValueAnimator? = null
    private var windowXAnimator: ValueAnimator? = null
    private var alphaAnimator: ValueAnimator? = null
    private var moveAnimator: ValueAnimator? = null

    private val autoCollapseHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable { collapseOverlay() }

    private val dockInactivityHandler = Handler(Looper.getMainLooper())
    private val dockInactivityRunnable = Runnable { performInactivityDocking() }

    /*
     * Inflates horizontal bar views, sets up layout parameters, and attaches to WindowManager.
     */
    override fun show() {
        updateScreenBounds()
        val baseThemed = ContextThemeWrapper(context, R.style.Theme_ScreenRecorder)
        val themedContext = if (com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
            com.google.android.material.color.DynamicColors.wrapContextIfAvailable(baseThemed)
        } else {
            baseThemed
        }
        val inflater = LayoutInflater.from(themedContext)
        val b = OverlayRecordingBinding.inflate(inflater)
        binding = b
        rootView = b.root

        setupLayoutParams()
        setupButtons(b)
        applyTouchHandling(b.timerContainer)

        try {
            windowManager.addView(rootView, params)
        } catch (_: Exception) {}

        synchronizeTimer()
        synchronizeDotAnimation()
        updateControlsState()
        startDockInactivityTimer()
    }

    /*
     * Removes view hierarchy from WindowManager and cancels active animators and handlers.
     */
    override fun dismiss() {
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
        dockInactivityHandler.removeCallbacks(dockInactivityRunnable)
        redDotAnimator?.cancel()
        windowXAnimator?.cancel()
        alphaAnimator?.cancel()
        moveAnimator?.cancel()

        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        binding = null
        rootView = null
    }

    /*
     * Updates recording status timer, red dot pulse, and action button states.
     */
    override fun onRecordingStateChanged() {
        updateControlsState()
        synchronizeTimer()
        synchronizeDotAnimation()
    }

    /*
     * Refreshes button visibility options and divider appearance based on latest configuration.
     */
    override fun onConfigurationChanged() {
        updateControlsState()
    }

    /*
     * Initializes window layout parameters positioning the bar near top-left screen margin.
     */
    private fun setupLayoutParams() {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = context.resources.displayMetrics.density
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (100 * density).toInt()
        }
    }

    /*
     * Queries display metrics to determine physical screen width and height.
     */
    private fun updateScreenBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
        } else {
            val displayMetrics = context.resources.displayMetrics
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }
    }

    /*
     * Attaches click handlers to action buttons that forward events to controller.
     */
    private fun setupButtons(b: OverlayRecordingBinding) {
        b.btnPause.setOnClickListener {
            startAutoCollapseTimer()
            controller.onPauseClicked()
        }
        b.btnMute.setOnClickListener {
            startAutoCollapseTimer()
            controller.onMuteClicked()
        }
        b.btnCamera.setOnClickListener {
            startAutoCollapseTimer()
            controller.onCameraClicked()
        }
        b.btnDraw.setOnClickListener {
            startAutoCollapseTimer()
            controller.onDrawClicked()
        }
        b.btnStop.setOnClickListener {
            controller.onStopClicked()
        }
    }

    /*
     * Synchronizes chronometer widget directly from controller base timestamp.
     */
    private fun synchronizeTimer() {
        binding?.timer?.apply {
            base = controller.getChronometerBase()
            if (controller.isPaused()) {
                stop()
            } else {
                start()
            }
        }
    }

    /*
     * Synchronizes pulsing animation on the recording indicator dot with recording state.
     */
    private fun synchronizeDotAnimation() {
        val dot = binding?.redDot ?: return
        if (controller.isPaused()) {
            redDotAnimator?.cancel()
            dot.alpha = 1f
        } else {
            if (redDotAnimator?.isRunning != true) {
                redDotAnimator?.cancel()
                redDotAnimator = ValueAnimator.ofFloat(1f, 0.25f).apply {
                    duration = 850
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        dot.alpha = it.animatedValue as Float
                    }
                    start()
                }
            }
        }
    }

    /*
     * Updates visibility, icons, colors, and accessibility descriptions across all controls.
     */
    private fun updateControlsState() {
        val b = binding ?: return
        val showPause = controller.isShowPauseEnabled()
        val showStop = controller.isShowStopEnabled()
        val showMute = controller.isShowMuteEnabled()
        val showCamera = controller.isShowCameraEnabled()
        val showDraw = controller.isShowDrawEnabled()

        val hasAny = showPause || showStop || showMute || showCamera || showDraw
        b.dividerControls.visibility = if (hasAny) View.VISIBLE else View.GONE
        if (!hasAny) {
            b.scrollControls.visibility = View.GONE
            if (isExpanded) collapseOverlay()
        }

        b.btnPause.visibility = if (showPause) View.VISIBLE else View.GONE
        b.btnStop.visibility = if (showStop) View.VISIBLE else View.GONE
        b.btnMute.visibility = if (showMute) View.VISIBLE else View.GONE
        b.btnCamera.visibility = if (showCamera) View.VISIBLE else View.GONE
        b.btnDraw.visibility = if (showDraw) View.VISIBLE else View.GONE

        val isPaused = controller.isPaused()
        val isMicActive = !controller.isMuted()
        val isCameraOn = controller.isCameraActive()
        val isDrawOn = controller.isDrawActive()

        b.btnPause.setIconResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
        b.btnPause.contentDescription = context.getString(
            if (isPaused) R.string.HorizontalOverlayWindow_desc_resume else R.string.HorizontalOverlayWindow_desc_pause
        )
        b.btnPause.isChecked = isPaused

        b.btnMute.setIconResource(R.drawable.ic_mic)
        b.btnMute.contentDescription = context.getString(
            if (isMicActive) R.string.HorizontalOverlayWindow_desc_mute else R.string.HorizontalOverlayWindow_desc_unmute
        )
        b.btnMute.isChecked = isMicActive

        b.btnCamera.setIconResource(R.drawable.ic_camera)
        b.btnCamera.contentDescription = context.getString(
            if (isCameraOn) R.string.HorizontalOverlayWindow_desc_camera_on else R.string.HorizontalOverlayWindow_desc_camera_off
        )
        b.btnCamera.isChecked = isCameraOn

        b.btnDraw.setIconResource(R.drawable.ic_draw)
        b.btnDraw.contentDescription = context.getString(
            if (isDrawOn) R.string.HorizontalOverlayWindow_desc_draw_on else R.string.HorizontalOverlayWindow_desc_draw_off
        )
        b.btnDraw.isChecked = isDrawOn
    }

    /*
     * Attaches touch listeners for drag movement, tap expansion, and activity reset.
     */
    private fun applyTouchHandling(targetView: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        targetView.setOnTouchListener { v, event ->
            wakeUpOverlay()
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
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop || isDragging) {
                        isDragging = true
                        val currentWidth = if (params.width > 0) params.width else (rootView?.width ?: 100)
                        val currentHeight = if (params.height > 0) params.height else (rootView?.height ?: 50)
                        params.x = (initialX + dx).coerceIn(0, (screenWidth - currentWidth).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(24, (screenHeight - currentHeight - 24).coerceAtLeast(24))
                        updateViewLayoutSafe()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (!isExpanded) expandOverlay() else collapseOverlay()
                        v.performClick()
                    } else {
                        if (isExpanded) {
                            startAutoCollapseTimer()
                        } else {
                            startDockInactivityTimer()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /*
     * Expands the overlay to display all active horizontal interactive action buttons.
     */
    private fun expandOverlay() {
        val b = binding ?: return
        if (isExpanded) return
        val hasAny = controller.isShowPauseEnabled() ||
                controller.isShowStopEnabled() ||
                controller.isShowMuteEnabled() ||
                controller.isShowCameraEnabled() ||
                controller.isShowDrawEnabled()
        if (!hasAny) return

        isExpanded = true
        wakeUpOverlay()

        val density = context.resources.displayMetrics.density
        val marginPx = (14 * density).toInt()

        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT

        b.dividerControls.visibility = View.VISIBLE
        b.scrollControls.visibility = View.VISIBLE
        b.rootCard.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST)
        )
        val expandedWidth = b.rootCard.measuredWidth.takeIf { it > 0 } ?: (280 * density).toInt()

        if (params.x + expandedWidth > screenWidth - marginPx) {
            val targetX = (screenWidth - expandedWidth - marginPx).coerceAtLeast(marginPx)
            val startX = params.x
            moveAnimator?.cancel()
            moveAnimator = ValueAnimator.ofInt(startX, targetX).apply {
                duration = 200
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener { anim ->
                    params.x = anim.animatedValue as Int
                    updateViewLayoutSafe()
                }
                start()
            }
        }

        b.scrollControls.alpha = 0f
        b.scrollControls.scaleX = 0.8f
        b.scrollControls.pivotX = 0f
        b.scrollControls.animate()
            .alpha(1f)
            .scaleX(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        startAutoCollapseTimer()
    }

    /*
     * Collapses interactive controls and smoothly animates window to the closest screen edge.
     */
    private fun collapseOverlay() {
        val b = binding ?: return
        if (!isExpanded) return
        isExpanded = false
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
        moveAnimator?.cancel()

        b.scrollControls.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator(2f))
            .withEndAction {
                b.scrollControls.visibility = View.GONE
                val compactWidth = b.rootCard.width.takeIf { it > 0 } ?: (90 * context.resources.displayMetrics.density).toInt()
                params.x = params.x.coerceIn(0, (screenWidth - compactWidth).coerceAtLeast(0))
                updateViewLayoutSafe()

                val targetDockX = if (params.x + compactWidth / 2 < screenWidth / 2) {
                    0
                } else {
                    (screenWidth - compactWidth).coerceAtLeast(0)
                }
                animateWindowPositionX(params.x, targetDockX) {
                    startDockInactivityTimer()
                }
            }
            .start()
    }

    /*
     * Starts the auto-collapse timer while overlay controls are expanded.
     */
    private fun startAutoCollapseTimer() {
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
        autoCollapseHandler.postDelayed(autoCollapseRunnable, 3500L)
    }

    /*
     * Starts the inactivity timer before docking against the screen edge.
     */
    private fun startDockInactivityTimer() {
        dockInactivityHandler.removeCallbacks(dockInactivityRunnable)
        dockInactivityHandler.postDelayed(dockInactivityRunnable, 3000L)
    }

    /*
     * Docks the collapsed bar against the closest screen edge and lowers transparency.
     */
    private fun performInactivityDocking() {
        if (isExpanded) return
        val view = rootView ?: return
        val density = context.resources.displayMetrics.density
        val peekInsetPx = (10 * density).toInt()

        val currentWidth = if (params.width > 0) params.width else view.width
        val targetX = OverlayMath.calculateDockedX(params.x, currentWidth, screenWidth, peekInsetPx)

        animateWindowPositionX(params.x, targetX)
        animateOverlayAlpha(0.45f)
    }

    /*
     * Restores full opacity and cancels pending docking timers upon user interaction.
     */
    private fun wakeUpOverlay() {
        dockInactivityHandler.removeCallbacks(dockInactivityRunnable)
        alphaAnimator?.cancel()
        rootView?.alpha = 1.0f
    }

    /*
     * Animates overlay window opacity smoothly to target value.
     */
    private fun animateOverlayAlpha(targetAlpha: Float) {
        alphaAnimator?.cancel()
        val currentAlpha = rootView?.alpha ?: 1f
        alphaAnimator = ValueAnimator.ofFloat(currentAlpha, targetAlpha).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                rootView?.alpha = it.animatedValue as Float
            }
            start()
        }
    }

    /*
     * Animates window horizontal position during edge docking.
     */
    private fun animateWindowPositionX(startX: Int, endX: Int, onEnd: (() -> Unit)? = null) {
        if (startX == endX) {
            onEnd?.invoke()
            return
        }
        windowXAnimator?.cancel()
        windowXAnimator = ValueAnimator.ofInt(startX, endX).apply {
            duration = 240
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                updateViewLayoutSafe()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    /*
     * Updates window layout parameters on the WindowManager safely.
     */
    private fun updateViewLayoutSafe() {
        try {
            if (rootView?.windowToken != null) {
                windowManager.updateViewLayout(rootView, params)
            }
        } catch (_: Exception) {}
    }
}
