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
