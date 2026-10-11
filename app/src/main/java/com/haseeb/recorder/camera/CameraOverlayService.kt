package com.haseeb.recorder.camera

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.ResultReceiver
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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.appcompat.view.ContextThemeWrapper
import com.haseeb.recorder.R
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.databinding.OverlayCameraBinding
import com.haseeb.recorder.recording.RecordingStateManager
import com.haseeb.recorder.recording.ScreenRecordService
import kotlin.math.abs

/*
 * Floating camera preview with a camera foreground service and serialized Camera2 callbacks.
 * Features ultra-fast preview loading, bottom-anchored default position, and jitter-free multi-corner resizing.
 */
class CameraOverlayService : Service() {

    companion object {
        const val TAG = "CameraOverlayService"
        const val ACTION_START = "com.haseeb.recorder.ACTION_START_CAMERA"
        const val ACTION_STOP = "com.haseeb.recorder.ACTION_STOP_CAMERA"
        const val ACTION_SWITCH = "com.haseeb.recorder.ACTION_SWITCH_CAMERA"
        const val EXTRA_READY_RECEIVER = "camera_ready_receiver"
        private const val CHANNEL_ID = "camera_preview_channel"
        private const val NOTIFICATION_ID = 2

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

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var pendingSurfaceTexture: SurfaceTexture? = null
    private val previewState = CameraPreviewState()
    private var previewSurface: Surface? = null
    private var previewSize: PreviewSize? = null
    private var destroyed = false
    private var stopping = false
    private var failureReported = false
    private val previewTimeout = Runnable { failCamera("Camera produced no preview frames") }
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var overlayAspectRatio = 185.0 / 140.0

    private val hideControlsRunnable = Runnable {
        hideControlsWithAnimation()
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            pendingSurfaceTexture = texture
            if (cameraDevice != null) {
                startCameraPreview()
            } else {
                openCamera()
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            pendingSurfaceTexture = null
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            mainHandler.removeCallbacks(previewTimeout)
        }
    }

    /*
     * Returns null because this service operates independently without client binding.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /*
     * Initializes configuration. Camera access starts only after foreground promotion.
     */
    override fun onCreate() {
        super.onCreate()
        configManager = ConfigManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenBounds()
    }

    /*
     * Processes incoming intents to control camera service actions.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION")
        val readyReceiver = intent?.getParcelableExtra<ResultReceiver>(EXTRA_READY_RECEIVER)
        when (intent?.action) {
            ACTION_STOP -> { stopping = true; closeCamera(); stopSelf() }
            ACTION_SWITCH -> if (!stopping) switchCamera()
            else -> {
                if (!isCameraRunning && !stopping) {
                    try {
                        startCameraForeground()
                        if (!RecordingStateManager.isRecording) {
                            stopping = true
                            stopSelf()
                        } else if (createOverlay()) {
                            isCameraRunning = true
                            notifyCameraState()
                            openCamera()
                        }
                    } catch (e: RuntimeException) {
                        failCamera("Camera foreground startup failed", e)
                    }
                }
            }
        }
        readyReceiver?.send(0, null)
        return START_NOT_STICKY
    }

    private fun startCameraForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            getString(R.string.camera_preview_notification_title), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 20,
            Intent(this, CameraOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(getString(R.string.camera_preview_notification_title))
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_close, getString(R.string.overlay_camera_desc_close), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyCameraState() {
        RecordingStateManager.notifyListeners()
        sendBroadcast(Intent(ScreenRecordService.ACTION_STATE_CHANGED).setPackage(packageName))
        if (ScreenRecordService.isRecording) {
            startService(Intent(this, ScreenRecordService::class.java)
                .setAction(ScreenRecordService.ACTION_REFRESH_CAMERA))
        }
    }

    private fun failCamera(reason: String, error: Exception? = null) {
        if (destroyed || stopping) return
        Log.e(TAG, reason, error)
        if (!failureReported) {
            failureReported = true
            Toast.makeText(this, R.string.camera_preview_unavailable, Toast.LENGTH_LONG).show()
        }
        stopping = true
        closeCamera()
        stopSelf()
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
    private fun createOverlay(): Boolean {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_ScreenRecorder)
        binding = OverlayCameraBinding.inflate(LayoutInflater.from(themedContext))
        val view = binding!!.root

        val density = resources.displayMetrics.density
        val initialW = (140 * density).toInt()
        val initialH = (185 * density).toInt()
        overlayAspectRatio = initialH.toDouble() / initialW
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
            return true
        } catch (e: Exception) {
            failCamera("Failed to add camera window", e)
            return false
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
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var resizing = false

        binding?.btnResizeHandle?.setOnClickListener {
            updateScreenBounds()
            val current = CameraOverlayBounds(params.x, params.y, params.width, params.height, overlayAspectRatio)
            val expanded = current.resize(maxW, screenWidth, screenHeight, false, false)
            val target = if (params.width < expanded.width * 0.9f) expanded.width else (140 * density).toInt()
            val quadrant = currentQuadrant ?: ScreenQuadrant.BOTTOM_RIGHT
            applyCameraBounds(current.resize(target, screenWidth, screenHeight,
                quadrant == ScreenQuadrant.TOP_RIGHT || quadrant == ScreenQuadrant.BOTTOM_RIGHT,
                quadrant == ScreenQuadrant.BOTTOM_LEFT || quadrant == ScreenQuadrant.BOTTOM_RIGHT))
            updateDynamicPositions()
            showControlsTemporary()
        }

        var initialW = 0
        var initialH = 0
        var initialX = 0
        var initialY = 0
        var startRawX = 0f
        var startRawY = 0f
        var aspectRatio = 1.32f
        var activeQuadrant: ScreenQuadrant = ScreenQuadrant.BOTTOM_RIGHT

        binding?.btnResizeHandle?.setOnTouchListener { handle, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    showControlsTemporary()
                    mainHandler.removeCallbacks(hideControlsRunnable)
                    updateScreenBounds()
                    resizing = false
                    initialW = params.width
                    initialH = params.height
                    initialX = params.x
                    initialY = params.y
                    aspectRatio = overlayAspectRatio.toFloat()
                    startRawX = event.rawX
                    startRawY = event.rawY
                    activeQuadrant = currentQuadrant ?: ScreenQuadrant.BOTTOM_RIGHT
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rawDx = event.rawX - startRawX
                    val rawDy = event.rawY - startRawY
                    if (abs(rawDx) > touchSlop || abs(rawDy) > touchSlop) resizing = true
                    if (!resizing) return@setOnTouchListener true

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
                    applyCameraBounds(CameraOverlayBounds(initialX, initialY, initialW, initialH, overlayAspectRatio).resize(
                        targetWidth, screenWidth, screenHeight,
                        activeQuadrant == ScreenQuadrant.TOP_RIGHT || activeQuadrant == ScreenQuadrant.BOTTOM_RIGHT,
                        activeQuadrant == ScreenQuadrant.BOTTOM_LEFT || activeQuadrant == ScreenQuadrant.BOTTOM_RIGHT))
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.action == MotionEvent.ACTION_UP && !resizing) handle.performClick()
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

    private fun applyCameraBounds(bounds: CameraOverlayBounds) {
        params.x = bounds.x
        params.y = bounds.y
        params.width = bounds.width
        params.height = bounds.height
        try {
            windowManager?.updateViewLayout(binding?.root, params)
        } catch (e: Exception) {
            Log.e(TAG, "Resize failed", e)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenBounds()
        if (binding != null && ::params.isInitialized) {
            applyCameraBounds(CameraOverlayBounds(params.x, params.y, params.width, params.height,
                overlayAspectRatio).resize(params.width, screenWidth, screenHeight, false, false))
            updateDynamicPositions(force = true)
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

    /** All lifecycle state and callbacks run on the main looper; camera capture remains asynchronous. */
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (destroyed || stopping || cameraDevice != null) return
        val token = previewState.beginOpen() ?: return
        mainHandler.removeCallbacks(previewTimeout)
        mainHandler.postDelayed(previewTimeout, 10000)
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val facing = if (configManager.useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            } ?: manager.cameraIdList.firstOrNull()
            if (cameraId == null) { failCamera("No camera available"); return }
            val map = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            previewSize = choosePreviewSize(map?.getOutputSizes(SurfaceTexture::class.java)
                ?.map { PreviewSize(it.width, it.height) }.orEmpty())
            if (previewSize == null) { failCamera("No supported camera preview size"); return }
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (destroyed || stopping || !previewState.opened(token)) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    startCameraPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (previewState.isCurrent(token)) failCamera("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (previewState.isCurrent(token)) failCamera("Camera error: $error")
                }
            }, mainHandler)
        } catch (e: Exception) {
            failCamera("Open camera failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun startCameraPreview() {
        if (destroyed || stopping) return
        val device = cameraDevice ?: return
        val texture = pendingSurfaceTexture ?: return
        val size = previewSize ?: return
        val token = previewState.generation
        if (!previewState.beginPreview(token)) return
        try {
            texture.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(texture)
            previewSurface = surface
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }.build()
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (destroyed || stopping || !previewState.isCurrent(token) || cameraDevice !== device) {
                        session.close()
                        return
                    }
                    captureSession = session
                    try {
                        session.setRepeatingRequest(request, null, mainHandler)
                    } catch (e: Exception) {
                        failCamera("Repeating request failed", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (previewState.isCurrent(token)) failCamera("Camera preview configuration failed")
                }
            }, mainHandler)
        } catch (e: Exception) {
            failCamera("Start preview failed", e)
        }
    }

    private fun switchCamera() {
        if (destroyed || stopping) return
        configManager.useFrontCamera = !configManager.useFrontCamera
        closeCamera()
        openCamera()
    }

    private fun closeCamera() {
        previewState.close()
        mainHandler.removeCallbacks(previewTimeout)
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        previewSurface?.release()
        previewSurface = null
    }

    override fun onDestroy() {
        destroyed = true
        stopping = true
        isCameraRunning = false
        notifyCameraState()
        mainHandler.removeCallbacksAndMessages(null)
        closeCamera()
        binding?.textureView?.surfaceTextureListener = null
        binding?.root?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        binding = null
        pendingSurfaceTexture = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
