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
import android.view.animation.OvershootInterpolator
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import com.haseeb.recorder.R
import com.haseeb.recorder.databinding.OverlayRecordingRadialBinding
import kotlin.math.abs

/*
 * Radial circular floating overlay implementation displaying orbital satellite action buttons.
 */
class RadialOverlayWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val controller: RecordingOverlayController
) : RecordingOverlayWindow {

    private var binding: OverlayRecordingRadialBinding? = null
    private var rootView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var isExpanded = false
    private var isAnimating = false

    private var bubbleSizePx = 0
    private var expandedWindowSizePx = 0

    private var redDotAnimator: ValueAnimator? = null
    private var windowXAnimator: ValueAnimator? = null
    private var alphaAnimator: ValueAnimator? = null

    private val autoCollapseHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable { collapseOverlay() }

    private val dockInactivityHandler = Handler(Looper.getMainLooper())
    private val dockInactivityRunnable = Runnable { performInactivityDocking() }

    /*
     * Inflates radial bubble views, sets up layout parameters, and attaches to WindowManager.
     */
    override fun show() {
        updateScreenBounds()
        val density = context.resources.displayMetrics.density
        bubbleSizePx = (48 * density).toInt()
        expandedWindowSizePx = (200 * density).toInt()

        val baseThemed = ContextThemeWrapper(context, R.style.Theme_ScreenRecorder)
        val themedContext = if (com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
            com.google.android.material.color.DynamicColors.wrapContextIfAvailable(baseThemed)
        } else {
            baseThemed
        }
        val inflater = LayoutInflater.from(themedContext)
        val b = OverlayRecordingRadialBinding.inflate(inflater)
        binding = b
        rootView = b.root

        setupLayoutParams()
        setupButtons(b)
        applyTouchHandling(b.centerBubble)

        b.radialRoot.setOnClickListener {
            if (isExpanded && !isAnimating) {
                collapseOverlay()
            }
        }

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
     * Refreshes satellite button state and active options based on updated user settings.
     */
    override fun onConfigurationChanged() {
        updateControlsState()
    }

    /*
     * Initializes window layout parameters for compact circular bubble overlay.
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
            bubbleSizePx,
            bubbleSizePx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (120 * density).toInt()
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
     * Attaches click handlers to radial satellite buttons forwarding actions to controller.
     */
    private fun setupButtons(b: OverlayRecordingRadialBinding) {
        b.btnRadialPause.setOnClickListener {
            startAutoCollapseTimer()
            controller.onPauseClicked()
        }
        b.btnRadialMute.setOnClickListener {
            startAutoCollapseTimer()
            controller.onMuteClicked()
        }
        b.btnRadialCamera.setOnClickListener {
            startAutoCollapseTimer()
            controller.onCameraClicked()
        }
        b.btnRadialDraw.setOnClickListener {
            startAutoCollapseTimer()
            controller.onDrawClicked()
        }
        b.btnRadialStop.setOnClickListener {
            controller.onStopClicked()
        }
    }

    /*
     * Synchronizes chronometer widget directly from controller base timestamp.
     */
    private fun synchronizeTimer() {
        binding?.radialTimer?.apply {
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
        val dot = binding?.radialRedDot ?: return
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
     * Updates icons, state colors, and accessibility descriptions for radial satellite buttons.
     */
    private fun updateControlsState() {
        val b = binding ?: return
        val activeButtons = getActiveButtons(b)
        if (activeButtons.isEmpty() && isExpanded) {
            collapseOverlay()
        }

        val isPaused = controller.isPaused()
        val isMicActive = !controller.isMuted()
        val isCameraOn = controller.isCameraActive()
        val isDrawOn = controller.isDrawActive()

        b.btnRadialPause.setIconResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
        b.btnRadialPause.contentDescription = context.getString(
            if (isPaused) R.string.RadialOverlayWindow_desc_resume else R.string.RadialOverlayWindow_desc_pause
        )
        b.btnRadialPause.isChecked = isPaused

        b.btnRadialMute.setIconResource(R.drawable.ic_mic)
        b.btnRadialMute.contentDescription = context.getString(
            if (isMicActive) R.string.RadialOverlayWindow_desc_mute else R.string.RadialOverlayWindow_desc_unmute
        )
        b.btnRadialMute.isChecked = isMicActive

        b.btnRadialCamera.setIconResource(R.drawable.ic_camera)
        b.btnRadialCamera.contentDescription = context.getString(
            if (isCameraOn) R.string.RadialOverlayWindow_desc_camera_on else R.string.RadialOverlayWindow_desc_camera_off
        )
        b.btnRadialCamera.isChecked = isCameraOn

        b.btnRadialDraw.setIconResource(R.drawable.ic_draw)
        b.btnRadialDraw.contentDescription = context.getString(
            if (isDrawOn) R.string.RadialOverlayWindow_desc_draw_on else R.string.RadialOverlayWindow_desc_draw_off
        )
        b.btnRadialDraw.isChecked = isDrawOn
    }

    /*
     * Returns the ordered list of enabled radial satellite buttons based on controller.
     */
    private fun getActiveButtons(b: OverlayRecordingRadialBinding): List<MaterialButton> {
        val list = mutableListOf<MaterialButton>()
        if (controller.isShowPauseEnabled()) list.add(b.btnRadialPause)
        if (controller.isShowMuteEnabled()) list.add(b.btnRadialMute)
        if (controller.isShowCameraEnabled()) list.add(b.btnRadialCamera)
        if (controller.isShowDrawEnabled()) list.add(b.btnRadialDraw)
        if (controller.isShowStopEnabled()) list.add(b.btnRadialStop)
        return list
    }

    /*
     * Attaches touch listeners for drag movement, tap expansion, and activity reset on the center bubble.
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
                        val currentWidth = if (params.width > 0) params.width else bubbleSizePx
                        val currentHeight = if (params.height > 0) params.height else bubbleSizePx
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
     * Expands the overlay to bloom radial satellite buttons with hardware-accelerated smoothness.
     */
    private fun expandOverlay() {
        val b = binding ?: return
        if (isExpanded || isAnimating) return
        val activeButtons = getActiveButtons(b)
        if (activeButtons.isEmpty()) return

        isExpanded = true
        isAnimating = true
        wakeUpOverlay()

        val density = context.resources.displayMetrics.density
        val radiusPx = 64f * density
        val halfExpanded = expandedWindowSizePx / 2
        val minMargin = (12 * density).toInt()

        val currentCenterX = params.x + bubbleSizePx / 2
        val currentCenterY = params.y + bubbleSizePx / 2

        val targetCenterX = currentCenterX.coerceIn(halfExpanded + minMargin, screenWidth - halfExpanded - minMargin)
        val targetCenterY = currentCenterY.coerceIn(halfExpanded + minMargin, screenHeight - halfExpanded - minMargin)

        params.width = expandedWindowSizePx
        params.height = expandedWindowSizePx
        params.x = targetCenterX - halfExpanded
        params.y = targetCenterY - halfExpanded
        updateViewLayoutSafe()

        val shiftX = (currentCenterX - targetCenterX).toFloat()
        val shiftY = (currentCenterY - targetCenterY).toFloat()

        b.centerBubble.translationX = shiftX
        b.centerBubble.translationY = shiftY
        b.satelliteContainer.translationX = shiftX
        b.satelliteContainer.translationY = shiftY

        b.centerBubble.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(2f))
            .withEndAction {
                b.centerBubble.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
            .start()

        b.satelliteContainer.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        val total = activeButtons.size
        for ((index, button) in activeButtons.withIndex()) {
            val offset = OverlayMath.calculateSatelliteOffset(index, total, radiusPx)
            button.visibility = View.VISIBLE
            button.translationX = 0f
            button.translationY = 0f
            button.scaleX = 0f
            button.scaleY = 0f
            button.alpha = 0f

            button.animate()
                .translationX(offset.x)
                .translationY(offset.y)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(240)
                .setStartDelay(index * 16L)
                .setInterpolator(OvershootInterpolator(1.2f))
                .withEndAction {
                    if (index == total - 1) {
                        isAnimating = false
                    }
                }
                .start()
        }

        startAutoCollapseTimer()
    }

    /*
     * Collapses radial satellite buttons back into the central bubble and returns to compact bounds.
     */
    private fun collapseOverlay() {
        val b = binding ?: return
        if (!isExpanded || isAnimating) return
        isExpanded = false
        isAnimating = true
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)

        val activeButtons = getActiveButtons(b)
        var finishedCount = 0

        for (button in activeButtons) {
            button.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(0f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator(2f))
                .withEndAction {
                    finishedCount++
                    button.visibility = View.GONE
                    if (finishedCount >= activeButtons.size) {
                        finishRadialCollapse()
                    }
                }
                .start()
        }

        if (activeButtons.isEmpty()) {
            finishRadialCollapse()
        }
    }

    /*
     * Restores compact window parameters in place and smoothly docks to the closest screen edge.
     */
    private fun finishRadialCollapse() {
        val b = binding
        b?.centerBubble?.translationX = 0f
        b?.centerBubble?.translationY = 0f
        b?.satelliteContainer?.translationX = 0f
        b?.satelliteContainer?.translationY = 0f

        val halfExpanded = expandedWindowSizePx / 2
        val currentCenterX = params.x + halfExpanded
        val currentCenterY = params.y + halfExpanded

        params.x = (currentCenterX - bubbleSizePx / 2).coerceIn(0, screenWidth - bubbleSizePx)
        params.y = (currentCenterY - bubbleSizePx / 2).coerceIn(24, screenHeight - bubbleSizePx - 24)
        params.width = bubbleSizePx
        params.height = bubbleSizePx
        updateViewLayoutSafe()

        isAnimating = false

        val targetDockX = if (currentCenterX < screenWidth / 2) 0 else screenWidth - bubbleSizePx
        animateWindowPositionX(params.x, targetDockX) {
            startDockInactivityTimer()
        }
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
     * Docks the collapsed bubble against the closest screen edge and lowers transparency.
     */
    private fun performInactivityDocking() {
        if (isExpanded || isAnimating) return
        val density = context.resources.displayMetrics.density
        val peekInsetPx = (10 * density).toInt()

        val targetX = OverlayMath.calculateDockedX(params.x, bubbleSizePx, screenWidth, peekInsetPx)
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
