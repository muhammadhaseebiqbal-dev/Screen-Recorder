package com.haseeb.recorder.camera

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.RelativeLayout
import androidx.appcompat.view.ContextThemeWrapper
import com.haseeb.recorder.R
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.databinding.OverlayCameraBinding
import com.haseeb.recorder.recording.RecordingStateManager
import com.haseeb.recorder.recording.ScreenRecordService
import kotlin.math.abs

/*
 * Floating camera preview overlay service using optimized Camera2 background pipeline.
 * Features ultra-fast preview loading, bottom-anchored default position, and jitter-free multi-corner resizing.
 */
class CameraOverlayService : Service() {

    companion object {
        const val TAG = "CameraOverlayService"
        const val ACTION_START = "com.haseeb.recorder.ACTION_START_CAMERA"
        const val ACTION_STOP = "com.haseeb.recorder.ACTION_STOP_CAMERA"
        const val ACTION_SWITCH = "com.haseeb.recorder.ACTION_SWITCH_CAMERA"

        @Volatile var isCameraRunning = false
            private set
    }

    private enum class ScreenQuadrant {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        CENTER
    }

    private var currentQuadrant: ScreenQuadrant? = null
    private var windowManager: WindowManager? = null
    private var binding: OverlayCameraBinding? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var configManager: ConfigManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var pendingSurfaceTexture: SurfaceTexture? = null
    private var isCameraOpening = false
    private var screenWidth = 1080
    private var screenHeight = 1920

    private val hideControlsRunnable = Runnable {
        hideControlsWithAnimation()
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            pendingSurfaceTexture = texture
            if (cameraDevice != null) {
                startCameraPreview()
            } else if (!isCameraOpening) {
                openCamera()
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            pendingSurfaceTexture = null
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    /*
     * Returns null because this service operates independently without client binding.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /*
     * Initializes background threads, configuration, overlay layout, and starts immediate camera open.
     */
    override fun onCreate() {
        super.onCreate()
        configManager = ConfigManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startCameraThread()
        updateScreenBounds()
        createOverlay()
        openCamera()
        isCameraRunning = true
        RecordingStateManager.notifyListeners()
        sendBroadcast(Intent(ScreenRecordService.ACTION_STATE_CHANGED))
    }

    /*
     * Spawns dedicated background thread for high-priority asynchronous camera operations.
     */
    private fun startCameraThread() {
        val thread = HandlerThread("CameraBackgroundThread", android.os.Process.THREAD_PRIORITY_DISPLAY).apply {
            start()
        }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    /*
     * Safely terminates background thread and clears pending camera runnables.
     */
    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(500)
        } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null
    }

    /*
     * Processes incoming intents to control camera service actions.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_SWITCH -> switchCamera()
        }
        return START_NOT_STICKY
    }

    /*
     * Retrieves screen display dimensions to calculate window boundaries and quadrants.
     */
    private fun updateScreenBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager?.currentWindowMetrics
            screenWidth = metrics?.bounds?.width() ?: 1080
            screenHeight = metrics?.bounds?.height() ?: 1920
        } else {
            val display = windowManager?.defaultDisplay
            val size = android.graphics.Point()
            display?.getSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }
    }

    /*
     * Inflates layout, positions window at comfortable bottom default location, and registers touch listeners.
     */
    private fun createOverlay() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_ScreenRecorder)
        binding = OverlayCameraBinding.inflate(LayoutInflater.from(themedContext))
        val view = binding!!.root

        val density = resources.displayMetrics.density
        val initialW = (140 * density).toInt()
        val initialH = (185 * density).toInt()
        val marginEnd = (16 * density).toInt()
        val marginBottom = (90 * density).toInt()

        val initialX = (screenWidth - initialW - marginEnd).coerceIn(0, (screenWidth - initialW).coerceAtLeast(0))
        val initialY = (screenHeight - initialH - marginBottom).coerceIn(0, (screenHeight - initialH).coerceAtLeast(0))

        params = WindowManager.LayoutParams(
            initialW,
            initialH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        binding?.textureView?.surfaceTextureListener = surfaceTextureListener
        updateDynamicPositions(force = true)
        setupButtons()
        setupResizeLogic()
        applyDragLogic(view)

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add camera window: ${e.message}")
        }
    }

    /*
     * Calculates the quadrant of the screen the window occupies and repositions corner controls.
     */
    private fun updateDynamicPositions(force: Boolean = false) {
        val b = binding ?: return
        val currentW = params.width
        val currentH = params.height
        val centerX = params.x + (currentW / 2)
        val centerY = params.y + (currentH / 2)

        val isLeft = centerX < (screenWidth / 2)
        val isTop = centerY < (screenHeight / 2)

        val isHorizontalCenter = abs(centerX - (screenWidth / 2)) < (screenWidth * 0.12f)
        val isVerticalCenter = abs(centerY - (screenHeight / 2)) < (screenHeight * 0.12f)

        val newQuadrant = when {
            isHorizontalCenter && isVerticalCenter -> ScreenQuadrant.CENTER
            isTop && isLeft -> ScreenQuadrant.TOP_LEFT
            isTop && !isLeft -> ScreenQuadrant.TOP_RIGHT
            !isTop && isLeft -> ScreenQuadrant.BOTTOM_LEFT
            else -> ScreenQuadrant.BOTTOM_RIGHT
        }

        if (!force && newQuadrant == currentQuadrant) return
        currentQuadrant = newQuadrant

        when (newQuadrant) {
            ScreenQuadrant.TOP_LEFT -> {
                setRelativeAlignment(b.btnResizeHandle, RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.ALIGN_PARENT_END)
                b.btnResizeHandle.setIconResource(R.drawable.ic_resize_diagonal_nw_se)

                setRelativeAlignment(b.btnCloseCamera, RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_START)

                setRelativeAlignment(b.btnSwitchCamera, RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_END)
            }
            ScreenQuadrant.TOP_RIGHT -> {
                setRelativeAlignment(b.btnResizeHandle, RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.ALIGN_PARENT_START)
                b.btnResizeHandle.setIconResource(R.drawable.ic_resize_diagonal_ne_sw)

                setRelativeAlignment(b.btnCloseCamera, RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_END)

                setRelativeAlignment(b.btnSwitchCamera, RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_START)
            }
            ScreenQuadrant.BOTTOM_LEFT -> {
                setRelativeAlignment(b.btnResizeHandle, RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_END)
                b.btnResizeHandle.setIconResource(R.drawable.ic_resize_diagonal_ne_sw)

                setRelativeAlignment(b.btnCloseCamera, RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.ALIGN_PARENT_START)

                setRelativeAlignment(b.btnSwitchCamera, RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.ALIGN_PARENT_END)
            }
            ScreenQuadrant.BOTTOM_RIGHT -> {
                setRelativeAlignment(b.btnResizeHandle, RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_START)
                b.btnResizeHandle.setIconResource(R.drawable.ic_resize_diagonal_nw_se)

                setRelativeAlignment(b.btnCloseCamera, RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.ALIGN_PARENT_END)

                setRelativeAlignment(b.btnSwitchCamera, RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.ALIGN_PARENT_START)
            }
            ScreenQuadrant.CENTER -> {
                setRelativeAlignment(b.btnResizeHandle, RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.ALIGN_PARENT_END)
                b.btnResizeHandle.setIconResource(R.drawable.ic_resize_diagonal_nw_se)

                setRelativeAlignment(b.btnCloseCamera, RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_START)

                setRelativeAlignment(b.btnSwitchCamera, RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_END)
            }
        }
    }

    /*
     * Sets layout alignment rules for a RelativeLayout child view cleanly.
     */
    private fun setRelativeAlignment(view: View, verticalRule: Int, horizontalRule: Int) {
        val lp = view.layoutParams as RelativeLayout.LayoutParams
        lp.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
        lp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        lp.removeRule(RelativeLayout.ALIGN_PARENT_START)
        lp.removeRule(RelativeLayout.ALIGN_PARENT_END)
        lp.removeRule(RelativeLayout.ALIGN_PARENT_LEFT)
        lp.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT)

        lp.addRule(verticalRule)
        lp.addRule(horizontalRule)
        view.layoutParams = lp
    }

    /*
     * Displays all control buttons with a smooth scale and fade animation, resetting auto-hide timer.
     */
    private fun showControlsTemporary() {
        mainHandler.removeCallbacks(hideControlsRunnable)

        val buttons = listOfNotNull(
            binding?.btnSwitchCamera,
            binding?.btnCloseCamera,
            binding?.btnResizeHandle
        )

        for (btn in buttons) {
            if (btn.visibility != View.VISIBLE) {
                btn.alpha = 0f
                btn.scaleX = 0.75f
                btn.scaleY = 0.75f
                btn.visibility = View.VISIBLE

                val scaleXAnim = ObjectAnimator.ofFloat(btn, "scaleX", 0.75f, 1f)
                val scaleYAnim = ObjectAnimator.ofFloat(btn, "scaleY", 0.75f, 1f)
                val alphaAnim = ObjectAnimator.ofFloat(btn, "alpha", 0f, 1f)

                AnimatorSet().apply {
                    playTogether(scaleXAnim, scaleYAnim, alphaAnim)
                    duration = 200
                    interpolator = DecelerateInterpolator()
                    start()
                }
            }
        }

        mainHandler.postDelayed(hideControlsRunnable, 3000)
    }

    /*
     * Smoothly fades out and hides all interactive control buttons.
     */
    private fun hideControlsWithAnimation() {
        val buttons = listOfNotNull(
            binding?.btnSwitchCamera,
            binding?.btnCloseCamera,
            binding?.btnResizeHandle
        )

        for (btn in buttons) {
            if (btn.visibility == View.VISIBLE) {
                val scaleXAnim = ObjectAnimator.ofFloat(btn, "scaleX", 1f, 0.75f)
                val scaleYAnim = ObjectAnimator.ofFloat(btn, "scaleY", 1f, 0.75f)
                val alphaAnim = ObjectAnimator.ofFloat(btn, "alpha", 1f, 0f)

                AnimatorSet().apply {
                    playTogether(scaleXAnim, scaleYAnim, alphaAnim)
                    duration = 180
                    interpolator = DecelerateInterpolator()
                    start()
                }
            }
        }

        mainHandler.postDelayed({
            for (btn in buttons) {
                btn.visibility = View.GONE
            }
        }, 190)
    }

    /*
     * Sets click listeners for camera toggle and close buttons.
     */
    private fun setupButtons() {
        binding?.btnSwitchCamera?.setOnClickListener {
            showControlsTemporary()
            switchCamera()
        }

        binding?.btnCloseCamera?.setOnClickListener {
            stopSelf()
        }
    }

    /*
     * Configures jitter-free, continuous vector-based resizing logic for all screen quadrants.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeLogic() {
        val density = resources.displayMetrics.density
        val minW = (100 * density).toInt()
        val maxW = (300 * density).toInt()

        var initialW = 0
        var initialH = 0
        var initialX = 0
        var initialY = 0
        var initialRight = 0
        var initialBottom = 0
        var startRawX = 0f
        var startRawY = 0f
        var aspectRatio = 1.32f
        var activeQuadrant: ScreenQuadrant = ScreenQuadrant.BOTTOM_RIGHT

        binding?.btnResizeHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    showControlsTemporary()
                    initialW = params.width
                    initialH = params.height
                    initialX = params.x
                    initialY = params.y
                    initialRight = initialX + initialW
                    initialBottom = initialY + initialH
                    aspectRatio = initialH.toFloat() / initialW.toFloat().coerceAtLeast(1f)
                    startRawX = event.rawX
                    startRawY = event.rawY
                    activeQuadrant = currentQuadrant ?: ScreenQuadrant.BOTTOM_RIGHT
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rawDx = event.rawX - startRawX
                    val rawDy = event.rawY - startRawY

                    val delta = when (activeQuadrant) {
                        ScreenQuadrant.TOP_LEFT, ScreenQuadrant.CENTER -> {
                            (rawDx + (rawDy / aspectRatio)) / 2f
                        }
                        ScreenQuadrant.TOP_RIGHT -> {
                            (-rawDx + (rawDy / aspectRatio)) / 2f
                        }
                        ScreenQuadrant.BOTTOM_LEFT -> {
                            (rawDx - (rawDy / aspectRatio)) / 2f
                        }
                        ScreenQuadrant.BOTTOM_RIGHT -> {
                            (-rawDx - (rawDy / aspectRatio)) / 2f
                        }
                    }

                    val targetWidth = (initialW + delta).toInt().coerceIn(minW, maxW)
                    val targetHeight = (targetWidth * aspectRatio).toInt()

                    when (activeQuadrant) {
                        ScreenQuadrant.TOP_LEFT, ScreenQuadrant.CENTER -> {
                            params.x = initialX
                            params.y = initialY
                        }
                        ScreenQuadrant.TOP_RIGHT -> {
                            params.x = initialRight - targetWidth
                            params.y = initialY
                        }
                        ScreenQuadrant.BOTTOM_LEFT -> {
                            params.x = initialX
                            params.y = initialBottom - targetHeight
                        }
                        ScreenQuadrant.BOTTOM_RIGHT -> {
                            params.x = initialRight - targetWidth
                            params.y = initialBottom - targetHeight
                        }
                    }

                    params.width = targetWidth
                    params.height = targetHeight

                    try {
                        windowManager?.updateViewLayout(binding?.root, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "Resize failed: ${e.message}")
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    params.x = params.x.coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
                    params.y = params.y.coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
                    try {
                        windowManager?.updateViewLayout(binding?.root, params)
                    } catch (_: Exception) {}
                    updateDynamicPositions()
                    showControlsTemporary()
                    true
                }
                else -> false
            }
        }
    }

    /*
     * Handles dragging of the floating preview and dynamically updates corner positions upon release.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun applyDragLogic(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
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
                        params.x = (initialX + dx).coerceIn(0, (screenWidth - view.width).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(0, (screenHeight - view.height).coerceAtLeast(0))
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Update layout failed: ${e.message}")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        showControlsTemporary()
                    } else {
                        updateDynamicPositions()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /*
     * Asynchronously opens the target camera device via dedicated background camera thread.
     */
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val handler = cameraHandler ?: return
        isCameraOpening = true

        handler.post {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                val facing = if (configManager.useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                var cameraIdToOpen: String? = null

                for (id in manager.cameraIdList) {
                    val characteristics = manager.getCameraCharacteristics(id)
                    if (characteristics.get(CameraCharacteristics.LENS_FACING) == facing) {
                        cameraIdToOpen = id
                        break
                    }
                }

                if (cameraIdToOpen == null && manager.cameraIdList.isNotEmpty()) {
                    cameraIdToOpen = manager.cameraIdList[0]
                }

                if (cameraIdToOpen != null) {
                    manager.openCamera(cameraIdToOpen, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            isCameraOpening = false
                            cameraDevice = camera
                            if (pendingSurfaceTexture != null || binding?.textureView?.isAvailable == true) {
                                if (pendingSurfaceTexture == null) {
                                    pendingSurfaceTexture = binding?.textureView?.surfaceTexture
                                }
                                startCameraPreview()
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            isCameraOpening = false
                            camera.close()
                            cameraDevice = null
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            isCameraOpening = false
                            camera.close()
                            cameraDevice = null
                        }
                    }, handler)
                } else {
                    isCameraOpening = false
                }
            } catch (e: Exception) {
                isCameraOpening = false
                Log.e(TAG, "Open camera failed: ${e.message}")
            }
        }
    }

    /*
     * Configures the camera preview session immediately using the available TextureView SurfaceTexture.
     */
    private fun startCameraPreview() {
        val device = cameraDevice ?: return
        val texture = pendingSurfaceTexture ?: binding?.textureView?.surfaceTexture ?: return
        val handler = cameraHandler ?: return

        handler.post {
            try {
                texture.setDefaultBufferSize(640, 480)
                val surface = Surface(texture)
                val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                }

                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewRequestBuilder.build(), null, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "Repeating request failed: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera preview config failed")
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(TAG, "Start preview failed: ${e.message}")
            }
        }
    }

    /*
     * Switches between front and back camera lenses asynchronously.
     */
    private fun switchCamera() {
        configManager.useFrontCamera = !configManager.useFrontCamera
        closeCamera()
        openCamera()
    }

    /*
     * Closes the active camera capture session and releases camera device resources.
     */
    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
    }

    /*
     * Performs final cleanup by quitting background threads, closing camera, and removing overlay view.
     */
    override fun onDestroy() {
        super.onDestroy()
        isCameraRunning = false
        RecordingStateManager.notifyListeners()
        sendBroadcast(Intent(ScreenRecordService.ACTION_STATE_CHANGED))
        mainHandler.removeCallbacks(hideControlsRunnable)
        closeCamera()
        stopCameraThread()
        binding?.root?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        binding = null
    }
}
