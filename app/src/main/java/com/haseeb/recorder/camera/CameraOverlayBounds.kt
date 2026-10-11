package com.haseeb.recorder.camera

import kotlin.math.roundToInt

internal data class CameraOverlayBounds(val x: Int, val y: Int, val width: Int, val height: Int,
    val aspectRatio: Double = height.toDouble() / width) {
    fun resize(targetWidth: Int, screenWidth: Int, screenHeight: Int, anchorRight: Boolean, anchorBottom: Boolean): CameraOverlayBounds {
        require(width > 0 && height > 0 && screenWidth > 0 && screenHeight > 0 && aspectRatio > 0 && aspectRatio.isFinite())
        val ratio = aspectRatio
        val maxWidth = minOf(screenWidth, (screenHeight / ratio).toInt()).coerceAtLeast(1)
        val newWidth = targetWidth.coerceIn(1, maxWidth)
        val newHeight = (newWidth * ratio).roundToInt().coerceIn(1, screenHeight)
        val newX = if (anchorRight) x + width - newWidth else x
        val newY = if (anchorBottom) y + height - newHeight else y
        return CameraOverlayBounds(newX.coerceIn(0, screenWidth - newWidth),
            newY.coerceIn(0, screenHeight - newHeight), newWidth, newHeight, aspectRatio)
    }
}
