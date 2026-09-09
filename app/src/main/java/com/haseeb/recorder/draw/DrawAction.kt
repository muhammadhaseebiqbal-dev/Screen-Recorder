package com.haseeb.recorder.draw

import android.graphics.Path

/*
 * Sealed class representing individual completed graphical drawing actions.
 */
sealed class DrawAction {

    data class Freehand(
        val path: Path,
        val color: Int,
        val strokeWidth: Float,
        val isEraser: Boolean
    ) : DrawAction()

    data class Arrow(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val color: Int,
        val strokeWidth: Float
    ) : DrawAction()

    data class Rectangle(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val color: Int,
        val strokeWidth: Float
    ) : DrawAction()

    data class Circle(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val color: Int,
        val strokeWidth: Float
    ) : DrawAction()
}
