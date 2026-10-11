package com.haseeb.recorder.recording

import kotlin.math.min
import kotlin.math.roundToInt

internal data class RecordingFrameLayout(val x: Int, val y: Int, val width: Int, val height: Int) {
    companion object {
        fun fit(sourceWidth: Int, sourceHeight: Int, canvasWidth: Int, canvasHeight: Int): RecordingFrameLayout {
            require(sourceWidth > 0 && sourceHeight > 0 && canvasWidth > 0 && canvasHeight > 0)
            val scale = min(canvasWidth.toDouble() / sourceWidth, canvasHeight.toDouble() / sourceHeight)
            val width = (sourceWidth * scale).roundToInt().coerceIn(1, canvasWidth)
            val height = (sourceHeight * scale).roundToInt().coerceIn(1, canvasHeight)
            return RecordingFrameLayout((canvasWidth - width) / 2, (canvasHeight - height) / 2, width, height)
        }

        // Retain the selected quality while the source display or app window changes size.
        fun captureSize(width: Int, height: Int, scale: Double): Pair<Int, Int> {
            require(width > 0 && height > 0 && scale > 0 && scale.isFinite())
            return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
        }
    }
}
