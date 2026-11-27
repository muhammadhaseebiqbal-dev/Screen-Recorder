package com.haseeb.recorder.overlay

import android.graphics.PointF
import kotlin.math.cos
import kotlin.math.sin

/*
 * Utility calculations for floating recording overlay positioning and radial orbital distribution.
 */
object OverlayMath {

    /*
     * Calculates the target X and Y coordinate offsets for an orbital button evenly distributed in a circle.
     */
    fun calculateSatelliteOffset(
        index: Int,
        totalCount: Int,
        radiusPx: Float
    ): PointF {
        if (totalCount <= 0) return PointF(0f, 0f)
        val angleDeg = -90.0 + (index.toDouble() / totalCount) * 360.0
        val angleRad = Math.toRadians(angleDeg)
        val x = (radiusPx * cos(angleRad)).toFloat()
        val y = (radiusPx * sin(angleRad)).toFloat()
        return PointF(x, y)
    }

    fun clampX(x: Int, viewWidth: Int, screenWidth: Int, edgeMarginPx: Int = 0): Int {
        val maxX = (screenWidth - viewWidth - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        return x.coerceIn(edgeMarginPx.coerceAtMost(maxX), maxX)
    }

    fun clampY(y: Int, viewHeight: Int, screenHeight: Int, topInsetPx: Int, bottomInsetPx: Int): Int {
        val maxY = (screenHeight - viewHeight - bottomInsetPx).coerceAtLeast(topInsetPx)
        return y.coerceIn(topInsetPx.coerceAtMost(maxY), maxY)
    }

    /**
     * Snaps smoothly to the nearest horizontal screen margin when released inside the magnetic
     * edge zone; preserves arbitrary free placement anywhere in the middle region.
     */
    fun calculateReleaseSnapX(
        currentX: Int,
        viewWidth: Int,
        screenWidth: Int,
        edgeMarginPx: Int,
        edgeMagnetPx: Int
    ): Int {
        val clamped = clampX(currentX, viewWidth, screenWidth, edgeMarginPx)
        val rightEdgeX = (screenWidth - viewWidth - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        return when {
            clamped <= edgeMarginPx + edgeMagnetPx -> edgeMarginPx
            clamped >= rightEdgeX - edgeMagnetPx -> rightEdgeX
            else -> clamped
        }
    }

    /*
     * Calculates horizontal docking coordinate placing the view against the closest screen edge.
     */
    fun calculateDockedX(
        currentX: Int,
        viewWidth: Int,
        screenWidth: Int,
        peekInsetPx: Int
    ): Int {
        val centerX = currentX + viewWidth / 2
        return if (centerX < screenWidth / 2) {
            -peekInsetPx
        } else {
            screenWidth - viewWidth + peekInsetPx
        }
    }
}
