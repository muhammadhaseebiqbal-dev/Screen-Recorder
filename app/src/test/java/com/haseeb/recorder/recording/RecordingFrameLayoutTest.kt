package com.haseeb.recorder.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingFrameLayoutTest {
    @Test fun portraitFillsOriginalCanvas() {
        assertEquals(RecordingFrameLayout(0, 0, 1080, 1920), RecordingFrameLayout.fit(1080, 1920, 1080, 1920))
    }
    @Test fun landscapeFitsPortraitWithoutCropping() {
        assertEquals(RecordingFrameLayout(0, 656, 1080, 608), RecordingFrameLayout.fit(1920, 1080, 1080, 1920))
    }
    @Test fun portraitFitsLandscapeWithoutStretching() {
        assertEquals(RecordingFrameLayout(656, 0, 608, 1080), RecordingFrameLayout.fit(1080, 1920, 1920, 1080))
    }
    @Test fun returningToPortraitRemovesBars() {
        val original = RecordingFrameLayout.fit(1080, 2400, 720, 1600)
        repeat(20) {
            val landscape = RecordingFrameLayout.fit(2400, 1080, 720, 1600)
            assertTrue(landscape.y > 0)
            assertEquals(original, RecordingFrameLayout.fit(1080, 2400, 720, 1600))
        }
    }
    @Test fun appWindowUsesItsOwnAspectRatio() {
        assertEquals(RecordingFrameLayout(0, 420, 1080, 1080), RecordingFrameLayout.fit(800, 800, 1080, 1920))
    }
    @Test fun oddAndExtremeSizesStayInsideCanvas() {
        for ((w, h) in listOf(1 to 9000, 9000 to 1, 333 to 777, 777 to 333)) {
            val frame = RecordingFrameLayout.fit(w, h, 721, 1601)
            assertTrue(frame.width > 0 && frame.height > 0)
            assertTrue(frame.x >= 0 && frame.y >= 0)
            assertTrue(frame.x + frame.width <= 721 && frame.y + frame.height <= 1601)
            assertTrue(kotlin.math.abs(721 - frame.width - 2 * frame.x) <= 1)
            assertTrue(kotlin.math.abs(1601 - frame.height - 2 * frame.y) <= 1)
        }
    }
    @Test fun qualityScaleSurvivesRotation() {
        assertEquals(720 to 1600, RecordingFrameLayout.captureSize(1080, 2400, 2.0 / 3))
        assertEquals(1600 to 720, RecordingFrameLayout.captureSize(2400, 1080, 2.0 / 3))
    }
    @Test fun tinyAppWindowStillHasValidBuffer() {
        assertEquals(1 to 1, RecordingFrameLayout.captureSize(1, 1, 0.1))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidCaptureIsRejected() {
        RecordingFrameLayout.captureSize(0, 1080, 1.0)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidCanvasIsRejected() {
        RecordingFrameLayout.fit(1080, 1920, 0, 1920)
    }
}
