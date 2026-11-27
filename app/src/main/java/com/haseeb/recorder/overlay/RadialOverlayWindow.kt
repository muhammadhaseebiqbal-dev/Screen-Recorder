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
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.view.ContextThemeWrapper
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
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
    private var topInsetPx = 0
    private var bottomInsetPx = 0
    private var leftInsetPx = 0
    private var rightInsetPx = 0
    private var isExpanded = false
    private var isAnimating = false
    private var lastOverlayMode: FloatingBarPolicy.OverlayMode? = null

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
        val margin = maxOf((8 * density).toInt(), leftInsetPx)
        params = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = margin
            y = maxOf((120 * density).toInt(), topInsetPx)
        }
    }

    /*
     * Queries display metrics and safe system/cutout insets.
     */
    private fun updateScreenBounds() {
        val density = context.resources.displayMetrics.density
        val minPad = (8 * density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            topInsetPx = insets.top.coerceAtLeast(minPad)
            bottomInsetPx = insets.bottom.coerceAtLeast(minPad)
            leftInsetPx = insets.left.coerceAtLeast(minPad)
            rightInsetPx = insets.right.coerceAtLeast(minPad)
        } else {
            val displayMetrics = context.resources.displayMetrics
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            val fallbackVert = (24 * density).toInt()
            topInsetPx = fallbackVert
            bottomInsetPx = fallbackVert
            leftInsetPx = minPad
            rightInsetPx = minPad
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
        b.btnRadialStart.setOnClickListener {
            collapseOverlay()
            controller.onStartRecordingClicked()
        }
        b.btnRadialDismiss.setOnClickListener {
            collapseOverlay()
            controller.onDismissIdleOverlayClicked()
        }
    }

    /*
     * Synchronizes chronometer widget directly from controller base timestamp.
     */
    private fun synchronizeTimer() {
        val timer = binding?.radialTimer ?: return
        when (controller.getOverlayMode()) {
            FloatingBarPolicy.OverlayMode.RECORDING -> {
                timer.base = controller.getChronometerBase()
                timer.start()
            }
            FloatingBarPolicy.OverlayMode.PAUSED -> {
                timer.base = controller.getChronometerBase()
                timer.stop()
            }
            else -> timer.stop()
        }
    }

    /*
     * Synchronizes pulsing animation on the recording indicator dot with recording state.
     */
    private fun synchronizeDotAnimation() {
        val dot = binding?.radialRedDot ?: return
        if (controller.getOverlayMode() != FloatingBarPolicy.OverlayMode.RECORDING) {
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
        val mode = controller.getOverlayMode()
        if (lastOverlayMode != null && lastOverlayMode != mode && isExpanded) {
            collapseOverlay()
        }
        lastOverlayMode = mode
        val isIdleOrStarting = mode == FloatingBarPolicy.OverlayMode.IDLE_READY ||
            mode == FloatingBarPolicy.OverlayMode.STARTING

        b.radialIdleIcon.visibility = if (isIdleOrStarting) View.VISIBLE else View.GONE
        b.radialIdleIcon.alpha = if (mode == FloatingBarPolicy.OverlayMode.STARTING) 0.5f else 1.0f
        b.radialTimerContainer.visibility = if (isIdleOrStarting) View.GONE else View.VISIBLE

        val activeButtons = getActiveButtons(b)
        if (!isExpanded || activeButtons.isEmpty()) {
            for (button in allSatelliteButtons(b)) {
                if (button !in activeButtons || !isExpanded) {
                    button.visibility = View.GONE
                }
            }
        }
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
    private fun allSatelliteButtons(b: OverlayRecordingRadialBinding): List<MaterialButton> = listOf(
        b.btnRadialPause, b.btnRadialMute, b.btnRadialCamera, b.btnRadialDraw, b.btnRadialStop,
        b.btnRadialStart, b.btnRadialDismiss
    )

    private fun getActiveButtons(b: OverlayRecordingRadialBinding): List<MaterialButton> {
        return when (controller.getOverlayMode()) {
            FloatingBarPolicy.OverlayMode.IDLE_READY -> listOf(b.btnRadialStart, b.btnRadialDismiss)
            FloatingBarPolicy.OverlayMode.STARTING -> emptyList()
            FloatingBarPolicy.OverlayMode.RECORDING,
            FloatingBarPolicy.OverlayMode.PAUSED -> {
                val list = mutableListOf<MaterialButton>()
                if (controller.isShowPauseEnabled()) list.add(b.btnRadialPause)
                if (controller.isShowMuteEnabled()) list.add(b.btnRadialMute)
                if (controller.isShowCameraEnabled()) list.add(b.btnRadialCamera)
                if (controller.isShowDrawEnabled()) list.add(b.btnRadialDraw)
                if (controller.isShowStopEnabled()) list.add(b.btnRadialStop)
                list
            }
        }
    }

    private fun edgeMarginPx(): Int {
        val density = context.resources.displayMetrics.density
        return maxOf((8 * density).toInt(), leftInsetPx, rightInsetPx)
    }

    private fun edgeMagnetPx(): Int {
        val density = context.resources.displayMetrics.density
        return minOf((72 * density).toInt(), screenWidth / 4)
    }

    /*
     * Attaches touch listeners for smooth drag movement, tap expansion, and release snap on the center bubble.
     */
    private fun applyTouchHandling(targetView: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var touchDownRawX = 0f
        var touchDownRawY = 0f
        var dragOriginRawX = 0f
        var dragOriginRawY = 0f
        var isDragging = false

        targetView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wakeUpOverlay()
                    updateScreenBounds()
                    initialX = params.x
                    initialY = params.y
                    touchDownRawX = event.rawX
                    touchDownRawY = event.rawY
                    dragOriginRawX = event.rawX
                    dragOriginRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val slopDx = event.rawX - touchDownRawX
                    val slopDy = event.rawY - touchDownRawY
                    if (!isDragging && (abs(slopDx) > touchSlop || abs(slopDy) > touchSlop)) {
                        isDragging = true
                        dragOriginRawX = event.rawX
                        dragOriginRawY = event.rawY
                    }
                    if (isDragging) {
                        val currentWidth = if (params.width > 0) params.width else bubbleSizePx
                        val currentHeight = if (params.height > 0) params.height else bubbleSizePx
                        val dx = kotlin.math.round(event.rawX - dragOriginRawX).toInt()
                        val dy = kotlin.math.round(event.rawY - dragOriginRawY).toInt()
                        params.x = OverlayMath.clampX(initialX + dx, currentWidth, screenWidth, 0)
                        params.y = OverlayMath.clampY(initialY + dy, currentHeight, screenHeight, topInsetPx, bottomInsetPx)
                        updateViewLayoutSafe()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (!isExpanded) expandOverlay() else collapseOverlay()
                        v.performClick()
                    } else if (isDragging) {
                        if (isExpanded) {
                            val currentWidth = if (params.width > 0) params.width else expandedWindowSizePx
                            val clampedX = OverlayMath.clampX(params.x, currentWidth, screenWidth, edgeMarginPx())
                            animateWindowPositionX(params.x, clampedX)
                            startAutoCollapseTimer()
                        } else {
                            val targetX = OverlayMath.calculateReleaseSnapX(
                                currentX = params.x,
                                viewWidth = bubbleSizePx,
                                screenWidth = screenWidth,
                                edgeMarginPx = edgeMarginPx(),
                                edgeMagnetPx = edgeMagnetPx()
                            )
                            animateWindowPositionX(params.x, targetX) {
                                startDockInactivityTimer()
                            }
                        }
                    } else {
                        startDockInactivityTimer()
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

        val activeButtons = allSatelliteButtons(b).filter { it.visibility == View.VISIBLE }
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

        val margin = edgeMarginPx()
        params.x = OverlayMath.clampX(currentCenterX - bubbleSizePx / 2, bubbleSizePx, screenWidth, margin)
        params.y = OverlayMath.clampY(currentCenterY - bubbleSizePx / 2, bubbleSizePx, screenHeight, topInsetPx, bottomInsetPx)
        params.width = bubbleSizePx
        params.height = bubbleSizePx
        updateViewLayoutSafe()

        isAnimating = false

        val targetDockX = OverlayMath.calculateReleaseSnapX(
            currentX = params.x,
            viewWidth = bubbleSizePx,
            screenWidth = screenWidth,
            edgeMarginPx = margin,
            edgeMagnetPx = edgeMagnetPx()
        )
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
     * Starts the inactivity timer before dimming the idle bubble.
     */
    private fun startDockInactivityTimer() {
        dockInactivityHandler.removeCallbacks(dockInactivityRunnable)
        dockInactivityHandler.postDelayed(dockInactivityRunnable, 3000L)
    }

    /*
     * Gently lowers opacity after inactivity while preserving the user's chosen screen position.
     */
    private fun performInactivityDocking() {
        if (isExpanded || isAnimating) return
        animateOverlayAlpha(0.55f)
    }

    /*
     * Restores full opacity and cancels pending animations and timers upon user interaction.
     */
    private fun wakeUpOverlay() {
        dockInactivityHandler.removeCallbacks(dockInactivityRunnable)
        windowXAnimator?.cancel()
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
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                rootView?.alpha = it.animatedValue as Float
            }
            start()
        }
    }

    /*
     * Animates window horizontal position smoothly during edge snapping.
     */
    private fun animateWindowPositionX(startX: Int, endX: Int, onEnd: (() -> Unit)? = null) {
        if (startX == endX) {
            onEnd?.invoke()
            return
        }
        windowXAnimator?.cancel()
        windowXAnimator = ValueAnimator.ofInt(startX, endX).apply {
            duration = 260
            interpolator = FastOutSlowInInterpolator()
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
