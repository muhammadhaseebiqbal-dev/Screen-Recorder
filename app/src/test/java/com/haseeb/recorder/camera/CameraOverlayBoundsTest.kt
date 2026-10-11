package com.haseeb.recorder.camera

import org.junit.Assert.*
import org.junit.Test

class CameraOverlayBoundsTest {
    private val original = CameraOverlayBounds(300, 500, 140, 185)

    @Test fun topLeftAnchorStaysInPlace() {
        val bounds = original.resize(280, 1080, 1920, false, false)
        assertEquals(300, bounds.x); assertEquals(500, bounds.y)
        assertEquals(280, bounds.width); assertEquals(370, bounds.height)
    }
    @Test fun topRightAnchorStaysInPlace() {
        val bounds = original.resize(280, 1080, 1920, true, false)
        assertEquals(original.x + original.width, bounds.x + bounds.width)
        assertEquals(original.y, bounds.y)
    }
    @Test fun bottomLeftAnchorStaysInPlace() {
        val bounds = original.resize(280, 1080, 1920, false, true)
        assertEquals(original.y + original.height, bounds.y + bounds.height)
        assertEquals(original.x, bounds.x)
    }
    @Test fun bottomRightAnchorStaysInPlace() {
        val bounds = original.resize(280, 1080, 1920, true, true)
        assertEquals(original.x + original.width, bounds.x + bounds.width)
        assertEquals(original.y + original.height, bounds.y + bounds.height)
    }
    @Test fun growthNearEdgesStaysOnScreen() {
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            val bounds = CameraOverlayBounds(930, 1720, 140, 185).resize(5000, 1080, 1920, right, bottom)
            assertOnScreen(bounds, 1080, 1920)
        }
    }
    @Test fun landscapeConstrainsBothDimensions() {
        val bounds = original.resize(1000, 1920, 480, false, false)
        assertOnScreen(bounds, 1920, 480)
        assertTrue(kotlin.math.abs(bounds.height - bounds.width * original.aspectRatio) <= 1)
    }
    @Test fun repeatedResizeDoesNotAccumulateAspectRatioError() {
        var bounds = original
        repeat(100) {
            bounds = bounds.resize(213, 1080, 1920, false, false)
            bounds = bounds.resize(140, 1080, 1920, false, false)
        }
        assertEquals(original.width, bounds.width)
        assertEquals(original.height, bounds.height)
        assertEquals(original.aspectRatio, bounds.aspectRatio, 0.0)
    }
    @Test fun rotationMovesOffscreenPreviewBackInsideDisplay() {
        val bounds = CameraOverlayBounds(800, 1700, 280, 370).resize(280, 1920, 1080, false, false)
        assertOnScreen(bounds, 1920, 1080)
    }
    @Test fun tinyScreenStillGetsPositiveDimensions() {
        assertOnScreen(original.resize(280, 1, 1, true, true), 1, 1)
    }
    @Test(expected = IllegalArgumentException::class) fun zeroScreenIsRejected() {
        original.resize(200, 0, 1920, false, false)
    }
    private fun assertOnScreen(bounds: CameraOverlayBounds, width: Int, height: Int) {
        assertTrue(bounds.width > 0 && bounds.height > 0)
        assertTrue(bounds.x >= 0 && bounds.y >= 0)
        assertTrue(bounds.x + bounds.width <= width && bounds.y + bounds.height <= height)
    }
}
