package com.haseeb.recorder.draw

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.haseeb.recorder.R
import com.haseeb.recorder.databinding.OverlayDrawToolbarBinding
import com.haseeb.recorder.recording.RecordingStateManager
import kotlin.math.abs

/*
 * Floating service providing on-screen drawing overlay canvas and Material 3 bottom tool sheet.
 */
class DrawOverlayService : Service() {

    companion object {
        const val TAG = "DrawOverlayService"
        const val ACTION_START = "com.haseeb.recorder.draw.ACTION_START_DRAW"
        const val ACTION_STOP = "com.haseeb.recorder.draw.ACTION_STOP_DRAW"
        const val ACTION_TOGGLE = "com.haseeb.recorder.draw.ACTION_TOGGLE_DRAW"
        const val ACTION_DRAW_STATE_CHANGED = "com.haseeb.recorder.draw.ACTION_DRAW_STATE_CHANGED"

        @Volatile var isDrawRunning = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var canvasView: DrawCanvasView? = null
    private var toolbarBinding: OverlayDrawToolbarBinding? = null
    private lateinit var themedContext: Context

    private lateinit var canvasParams: WindowManager.LayoutParams
    private lateinit var toolbarParams: WindowManager.LayoutParams

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var isTouchThrough = false

    private val colorPaletteRes = listOf(
        R.color.draw_palette_red,
        R.color.draw_palette_pink,
        R.color.draw_palette_orange,
        R.color.draw_palette_yellow,
        R.color.draw_palette_green,
        R.color.draw_palette_cyan,
        R.color.draw_palette_blue,
        R.color.draw_palette_purple,
        R.color.draw_palette_white,
        R.color.draw_palette_black
    )

    private val colorPalette by lazy {
        colorPaletteRes.map { ContextCompat.getColor(this, it) }
    }

    private val strokeSizes = listOf(4f, 8f, 16f, 26f, 40f)
    private val strokeSizeIcons = listOf(
        R.drawable.ic_stroke_size_1,
        R.drawable.ic_stroke_size_2,
        R.drawable.ic_stroke_size_3,
        R.drawable.ic_stroke_size_4,
        R.drawable.ic_stroke_size_5
    )
    private var strokeSizeIndex = 1

    /*
     * Returns null because this service operates independently without binding.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /*
     * Initializes the service, screen boundaries, drawing canvas overlay, and floating toolbar.
     */
    override fun onCreate() {
        super.onCreate()
        themedContext = ContextThemeWrapper(this, R.style.Theme_ScreenRecorder)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenBounds()
        createCanvasOverlay()
        createToolbarOverlay()
        isDrawRunning = true
        RecordingStateManager.notifyListeners()
        sendBroadcast(Intent(ACTION_DRAW_STATE_CHANGED))
    }

    /*
     * Handles control action intents to stop or toggle the drawing overlay.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /*
     * Retrieves screen display dimensions to calculate window boundaries.
     */
    private fun updateScreenBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager?.currentWindowMetrics
            screenWidth = metrics?.bounds?.width() ?: 1080
            screenHeight = metrics?.bounds?.height() ?: 1920
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager?.defaultDisplay
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display?.getSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }
    }

    /*
     * Creates and attaches the full-screen transparent drawing canvas view.
     */
    private fun createCanvasOverlay() {
        val density = resources.displayMetrics.density
        canvasView = DrawCanvasView(themedContext).apply {
            currentColor = colorPalette[0]
            currentStrokeWidth = strokeSizes[strokeSizeIndex] * density
            onHistoryChangeListener = { canUndo, canRedo ->
                updateUndoRedoButtons(canUndo, canRedo)
            }
        }

        canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(canvasView, canvasParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /*
     * Creates and adds the floating Material 3 bottom toolbar to the window manager.
     */
    private fun createToolbarOverlay() {
        toolbarBinding = OverlayDrawToolbarBinding.inflate(LayoutInflater.from(themedContext))
        val view = toolbarBinding!!.root

        val density = resources.displayMetrics.density
        val defaultWidth = (330 * density).toInt().coerceAtMost(screenWidth - (24 * density).toInt())

        toolbarParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidth - defaultWidth) / 2).coerceAtLeast(12)
            y = screenHeight - (200 * density).toInt()
        }

        setupToolbarActions()
        setupColorPaletteView()
        toolbarBinding?.btnStrokeSize?.setIconResource(strokeSizeIcons[strokeSizeIndex])

        applyToolbarDrag(toolbarBinding!!.layoutDragHandle, view)
        applyToolbarDrag(toolbarBinding!!.cardMainToolbar, view)

        try {
            windowManager?.addView(view, toolbarParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        updateUndoRedoButtons(canUndo = false, canRedo = false)
        selectTool(DrawTool.PEN)
    }

    /*
     * Attaches click listeners to all tool, size, undo, redo, and touch toggle buttons.
     */
    private fun setupToolbarActions() {
        val b = toolbarBinding ?: return

        b.btnToolPen.setOnClickListener { selectTool(DrawTool.PEN) }
        b.btnToolArrow.setOnClickListener { selectTool(DrawTool.ARROW) }
        b.btnToolRect.setOnClickListener { selectTool(DrawTool.RECTANGLE) }
        b.btnToolCircle.setOnClickListener { selectTool(DrawTool.CIRCLE) }
        b.btnToolEraser.setOnClickListener { selectTool(DrawTool.ERASER) }

        b.btnStrokeSize.setOnClickListener {
            cycleStrokeSize()
        }

        b.btnUndo.setOnClickListener {
            canvasView?.undo()
        }

        b.btnRedo.setOnClickListener {
            canvasView?.redo()
        }

        b.btnClearCanvas.setOnClickListener {
            canvasView?.clearAll()
        }

        b.btnTouchMode.setOnClickListener {
            toggleTouchThroughMode()
        }

        b.btnCloseDraw.setOnClickListener {
            stopSelf()
        }
    }

    /*
     * Selects the active drawing tool and updates button checkable states cleanly.
     */
    private fun selectTool(tool: DrawTool) {
        if (isTouchThrough) {
            toggleTouchThroughMode()
        }
        canvasView?.currentTool = tool
        val b = toolbarBinding ?: return

        b.btnToolPen.isChecked = (tool == DrawTool.PEN)
        b.btnToolArrow.isChecked = (tool == DrawTool.ARROW)
        b.btnToolRect.isChecked = (tool == DrawTool.RECTANGLE)
        b.btnToolCircle.isChecked = (tool == DrawTool.CIRCLE)
        b.btnToolEraser.isChecked = (tool == DrawTool.ERASER)
    }

    /*
     * Updates check indicators and attaches click handlers directly to color cards in toolbar layout.
     */
    private fun setupColorPaletteView() {
        val b = toolbarBinding ?: return
        val selectedColor = canvasView?.currentColor ?: colorPalette[0]

        val colorViews = listOf(
            Triple(b.colorRed, b.checkRed, colorPalette[0]),
            Triple(b.colorPink, b.checkPink, colorPalette[1]),
            Triple(b.colorOrange, b.checkOrange, colorPalette[2]),
            Triple(b.colorYellow, b.checkYellow, colorPalette[3]),
            Triple(b.colorGreen, b.checkGreen, colorPalette[4]),
            Triple(b.colorCyan, b.checkCyan, colorPalette[5]),
            Triple(b.colorBlue, b.checkBlue, colorPalette[6]),
            Triple(b.colorPurple, b.checkPurple, colorPalette[7]),
            Triple(b.colorWhite, b.checkWhite, colorPalette[8]),
            Triple(b.colorBlack, b.checkBlack, colorPalette[9])
        )

        for ((card, check, color) in colorViews) {
            val isSelected = (color == selectedColor)
            if (isSelected) {
                val luminance = ColorUtils.calculateLuminance(color)
                val checkColor = if (luminance > 0.45) Color.BLACK else Color.WHITE
                check.setColorFilter(checkColor)
                check.visibility = View.VISIBLE
            } else {
                check.visibility = View.GONE
            }

            card.setOnClickListener {
                canvasView?.currentColor = color
                setupColorPaletteView()
                if (canvasView?.currentTool == DrawTool.ERASER) {
                    selectTool(DrawTool.PEN)
                }
            }
        }
    }

    /*
     * Cycles through predefined stroke width dimensions and updates stroke size indicator icon.
     */
    private fun cycleStrokeSize() {
        strokeSizeIndex = (strokeSizeIndex + 1) % strokeSizes.size
        val density = resources.displayMetrics.density
        val newWidth = strokeSizes[strokeSizeIndex] * density
        canvasView?.currentStrokeWidth = newWidth

        val b = toolbarBinding ?: return
        b.btnStrokeSize.setIconResource(strokeSizeIcons[strokeSizeIndex])
    }

    /*
     * Updates the enabled state and visual transparency of undo and redo buttons.
     */
    private fun updateUndoRedoButtons(canUndo: Boolean, canRedo: Boolean) {
        val b = toolbarBinding ?: return
        b.btnUndo.isEnabled = canUndo
        b.btnUndo.alpha = if (canUndo) 1.0f else 0.35f
        b.btnRedo.isEnabled = canRedo
        b.btnRedo.alpha = if (canRedo) 1.0f else 0.35f
    }

    /*
     * Switches between drawing interaction mode and screen touch-through mode.
     */
    private fun toggleTouchThroughMode() {
        isTouchThrough = !isTouchThrough
        val b = toolbarBinding ?: return

        b.btnTouchMode.isChecked = isTouchThrough
        if (isTouchThrough) {
            canvasParams.flags = canvasParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            canvasParams.flags = canvasParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }

        try {
            windowManager?.updateViewLayout(canvasView, canvasParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /*
     * Attaches smooth dragging behavior to allow moving the toolbar freely across the screen.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun applyToolbarDrag(handle: View, root: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        handle.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    initialX = toolbarParams.x
                    initialY = toolbarParams.y
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
                        val density = resources.displayMetrics.density
                        val curW = if (root.width > 0) root.width else (320 * density).toInt()
                        val curH = if (root.height > 0) root.height else (180 * density).toInt()
                        toolbarParams.x = (initialX + dx).coerceIn(0, (screenWidth - curW).coerceAtLeast(0))
                        toolbarParams.y = (initialY + dy).coerceIn(20, (screenHeight - curH - 20).coerceAtLeast(20))
                        try {
                            windowManager?.updateViewLayout(root, toolbarParams)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    /*
     * Cleans up drawing canvas and toolbar windows on service termination.
     */
    override fun onDestroy() {
        super.onDestroy()
        isDrawRunning = false
        RecordingStateManager.notifyListeners()
        sendBroadcast(Intent(ACTION_DRAW_STATE_CHANGED))
        canvasView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        toolbarBinding?.root?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        canvasView = null
        toolbarBinding = null
    }
}
