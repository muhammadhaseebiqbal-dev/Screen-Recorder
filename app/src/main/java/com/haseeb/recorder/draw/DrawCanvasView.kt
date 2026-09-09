package com.haseeb.recorder.draw

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.haseeb.recorder.R
import java.util.Stack
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * Custom hardware-accelerated interactive drawing canvas view supporting shapes, pen, and dynamic eraser.
 */
class DrawCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val actionsList = mutableListOf<DrawAction>()
    private val redoStack = Stack<DrawAction>()

    var currentTool: DrawTool = DrawTool.PEN
    var currentColor: Int = ContextCompat.getColor(context, R.color.draw_palette_red)
    var currentStrokeWidth: Float = 10f

    private var currentPath: Path? = null
    private var startX = 0f
    private var startY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDrawingActive = false

    private var bufferBitmap: Bitmap? = null
    private var bufferCanvas: Canvas? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    var onHistoryChangeListener: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null

    /*
     * Recreates the off-screen buffer bitmap when view dimensions change.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            bufferBitmap?.recycle()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bufferBitmap = bitmap
            bufferCanvas = Canvas(bitmap)
            redrawBuffer()
        }
    }

    /*
     * Renders buffered historical actions and live in-progress drawing preview.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bufferBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }
        if (isDrawingActive) {
            drawCurrentPreview(canvas)
        }
    }

    /*
     * Draws the active preview path or geometric shape during user interaction.
     */
    private fun drawCurrentPreview(canvas: Canvas) {
        when (currentTool) {
            DrawTool.PEN -> {
                currentPath?.let {
                    strokePaint.color = currentColor
                    strokePaint.strokeWidth = currentStrokeWidth
                    canvas.drawPath(it, strokePaint)
                }
            }
            DrawTool.ARROW -> {
                drawArrowShape(canvas, startX, startY, lastTouchX, lastTouchY, currentColor, currentStrokeWidth)
            }
            DrawTool.RECTANGLE -> {
                val left = minOf(startX, lastTouchX)
                val top = minOf(startY, lastTouchY)
                val right = maxOf(startX, lastTouchX)
                val bottom = maxOf(startY, lastTouchY)
                strokePaint.color = currentColor
                strokePaint.strokeWidth = currentStrokeWidth
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, strokePaint)
            }
            DrawTool.CIRCLE -> {
                val cx = (startX + lastTouchX) / 2f
                val cy = (startY + lastTouchY) / 2f
                val radius = kotlin.math.hypot((lastTouchX - startX).toDouble(), (lastTouchY - startY).toDouble()).toFloat() / 2f
                strokePaint.color = currentColor
                strokePaint.strokeWidth = currentStrokeWidth
                canvas.drawCircle(cx, cy, radius, strokePaint)
            }
            DrawTool.ERASER -> {
                /*
                 * Eraser draws directly into the buffer canvas during move events.
                 */
            }
        }
    }

    /*
     * Replays all recorded historical drawing actions onto the buffer canvas.
     */
    private fun redrawBuffer() {
        val bCanvas = bufferCanvas ?: return
        bCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        for (action in actionsList) {
            when (action) {
                is DrawAction.Freehand -> {
                    if (action.isEraser) {
                        eraserPaint.strokeWidth = action.strokeWidth
                        bCanvas.drawPath(action.path, eraserPaint)
                    } else {
                        strokePaint.color = action.color
                        strokePaint.strokeWidth = action.strokeWidth
                        bCanvas.drawPath(action.path, strokePaint)
                    }
                }
                is DrawAction.Arrow -> {
                    drawArrowShape(bCanvas, action.startX, action.startY, action.endX, action.endY, action.color, action.strokeWidth)
                }
                is DrawAction.Rectangle -> {
                    strokePaint.color = action.color
                    strokePaint.strokeWidth = action.strokeWidth
                    bCanvas.drawRoundRect(action.left, action.top, action.right, action.bottom, 12f, 12f, strokePaint)
                }
                is DrawAction.Circle -> {
                    strokePaint.color = action.color
                    strokePaint.strokeWidth = action.strokeWidth
                    bCanvas.drawCircle(action.centerX, action.centerY, action.radius, strokePaint)
                }
            }
        }
        invalidate()
        notifyHistoryChanged()
    }

    /*
     * Renders a directional arrow indicator with shaft and arrowhead.
     */
    private fun drawArrowShape(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        color: Int,
        strokeWidth: Float
    ) {
        val deltaX = (x2 - x1).toDouble()
        val deltaY = (y2 - y1).toDouble()
        val distance = kotlin.math.hypot(deltaX, deltaY).toFloat()
        if (distance < 6f) return

        val angle = atan2(deltaY, deltaX)
        val density = resources.displayMetrics.density

        val headLength = (strokeWidth * 2.8f + 14f * density)
            .coerceAtMost(distance * 0.7f)
        val arrowHalfWidth = maxOf(headLength * 0.65f, strokeWidth * 1.45f)

        val baseCenterX = (x2 - headLength * cos(angle)).toFloat()
        val baseCenterY = (y2 - headLength * sin(angle)).toFloat()

        val perpAngle = angle + Math.PI / 2.0
        val p1X = (baseCenterX + arrowHalfWidth * cos(perpAngle)).toFloat()
        val p1Y = (baseCenterY + arrowHalfWidth * sin(perpAngle)).toFloat()
        val p2X = (baseCenterX - arrowHalfWidth * cos(perpAngle)).toFloat()
        val p2Y = (baseCenterY - arrowHalfWidth * sin(perpAngle)).toFloat()

        val notchX = (baseCenterX + headLength * 0.28f * cos(angle)).toFloat()
        val notchY = (baseCenterY + headLength * 0.28f * sin(angle)).toFloat()

        strokePaint.color = color
        strokePaint.strokeWidth = strokeWidth
        strokePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x1, y1, notchX, notchY, strokePaint)

        val headPath = Path().apply {
            moveTo(x2, y2)
            lineTo(p1X, p1Y)
            lineTo(notchX, notchY)
            lineTo(p2X, p2Y)
            close()
        }
        fillPaint.color = color
        canvas.drawPath(headPath, fillPaint)
    }

    /*
     * Dispatches user touch movements into drawing paths and shapes.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawingActive = true
                startX = x
                startY = y
                lastTouchX = x
                lastTouchY = y

                if (currentTool == DrawTool.PEN || currentTool == DrawTool.ERASER) {
                    val p = Path().apply {
                        moveTo(x, y)
                        lineTo(x + 0.1f, y + 0.1f)
                    }
                    currentPath = p
                    if (currentTool == DrawTool.ERASER) {
                        eraserPaint.strokeWidth = currentStrokeWidth * 2.5f
                        bufferCanvas?.drawPath(p, eraserPaint)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentTool == DrawTool.PEN || currentTool == DrawTool.ERASER) {
                    currentPath?.let {
                        val midX = (lastTouchX + x) / 2f
                        val midY = (lastTouchY + y) / 2f
                        it.quadTo(lastTouchX, lastTouchY, midX, midY)
                        if (currentTool == DrawTool.ERASER) {
                            eraserPaint.strokeWidth = currentStrokeWidth * 2.5f
                            bufferCanvas?.drawPath(it, eraserPaint)
                        }
                    }
                }
                lastTouchX = x
                lastTouchY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDrawingActive = false
                commitCurrentAction(x, y)
                currentPath = null
                redrawBuffer()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDrawingActive = false
                currentPath = null
                redrawBuffer()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /*
     * Commits the completed shape or stroke action into the undo/redo stack.
     */
    private fun commitCurrentAction(endX: Float, endY: Float) {
        val action: DrawAction = when (currentTool) {
            DrawTool.PEN -> {
                val path = currentPath ?: return
                DrawAction.Freehand(path, currentColor, currentStrokeWidth, isEraser = false)
            }
            DrawTool.ERASER -> {
                val path = currentPath ?: return
                DrawAction.Freehand(path, Color.TRANSPARENT, currentStrokeWidth * 2.5f, isEraser = true)
            }
            DrawTool.ARROW -> {
                DrawAction.Arrow(startX, startY, endX, endY, currentColor, currentStrokeWidth)
            }
            DrawTool.RECTANGLE -> {
                val left = minOf(startX, endX)
                val top = minOf(startY, endY)
                val right = maxOf(startX, endX)
                val bottom = maxOf(startY, endY)
                DrawAction.Rectangle(left, top, right, bottom, currentColor, currentStrokeWidth)
            }
            DrawTool.CIRCLE -> {
                val cx = (startX + endX) / 2f
                val cy = (startY + endY) / 2f
                val radius = kotlin.math.hypot((endX - startX).toDouble(), (endY - startY).toDouble()).toFloat() / 2f
                DrawAction.Circle(cx, cy, radius, currentColor, currentStrokeWidth)
            }
        }

        actionsList.add(action)
        redoStack.clear()
    }

    /*
     * Undoes the most recent drawing action.
     */
    fun undo() {
        if (actionsList.isNotEmpty()) {
            val removed = actionsList.removeAt(actionsList.size - 1)
            redoStack.push(removed)
            redrawBuffer()
        }
    }

    /*
     * Redoes the last undone drawing action.
     */
    fun redo() {
        if (redoStack.isNotEmpty()) {
            val restored = redoStack.pop()
            actionsList.add(restored)
            redrawBuffer()
        }
    }

    /*
     * Clears all drawn paths and resets undo and redo history stacks.
     */
    fun clearAll() {
        actionsList.clear()
        redoStack.clear()
        redrawBuffer()
    }

    /*
     * Returns true if there are actions that can be undone.
     */
    fun canUndo(): Boolean = actionsList.isNotEmpty()

    /*
     * Returns true if there are actions that can be redone.
     */
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /*
     * Invokes the history change listener callback.
     */
    private fun notifyHistoryChanged() {
        onHistoryChangeListener?.invoke(canUndo(), canRedo())
    }
}
