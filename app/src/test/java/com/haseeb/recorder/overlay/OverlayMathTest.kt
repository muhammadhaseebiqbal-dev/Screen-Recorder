package com.haseeb.recorder.overlay

import org.junit.Assert.*
import org.junit.Test

class OverlayMathTest {
    @Test fun releaseSnapSnapsNearLeftOrRightEdgeAndKeepsCenterPlacement() {
        val screenW = 1080
        val viewW = 140
        val margin = 24
        val magnet = 160

        // Near left edge -> snaps smoothly to left margin
        assertEquals(24, OverlayMath.calculateReleaseSnapX(60, viewW, screenW, margin, magnet))
        assertEquals(24, OverlayMath.calculateReleaseSnapX(-10, viewW, screenW, margin, magnet))

        // Near right edge -> snaps smoothly to right margin
        val rightEdge = screenW - viewW - margin
        assertEquals(rightEdge, OverlayMath.calculateReleaseSnapX(rightEdge - 80, viewW, screenW, margin, magnet))
        assertEquals(rightEdge, OverlayMath.calculateReleaseSnapX(1020, viewW, screenW, margin, magnet))

        // Placed in the middle -> preserves exact user placement
        assertEquals(480, OverlayMath.calculateReleaseSnapX(480, viewW, screenW, margin, magnet))
    }

    @Test fun clampsVerticalAndHorizontalBoundsWithinSafeInsets() {
        assertEquals(120, OverlayMath.clampY(-50, 120, 2400, topInsetPx = 120, bottomInsetPx = 140))
        assertEquals(2140, OverlayMath.clampY(2350, 120, 2400, topInsetPx = 120, bottomInsetPx = 140))
        assertEquals(500, OverlayMath.clampY(500, 120, 2400, topInsetPx = 120, bottomInsetPx = 140))
    }
}
