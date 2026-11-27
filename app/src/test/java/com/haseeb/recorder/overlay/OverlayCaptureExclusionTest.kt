package com.haseeb.recorder.overlay

import com.haseeb.recorder.overlay.OverlayCaptureExclusion.ExclusionMode
import org.junit.Assert.*
import org.junit.Test

class OverlayCaptureExclusionTest {
    @Test fun disabledSettingNeverExcludesOverlay() {
        assertEquals(
            ExclusionMode.NONE,
            OverlayCaptureExclusion.resolveMode(hideEnabled = false, isRecording = true, isPaused = false, canSkipScreenshot = true)
        )
        assertEquals(
            ExclusionMode.NONE,
            OverlayCaptureExclusion.resolveMode(hideEnabled = false, isRecording = true, isPaused = false, canSkipScreenshot = false)
        )
    }

    @Test fun overlayReappearsWhenStoppedOrPaused() {
        assertEquals(
            ExclusionMode.NONE,
            OverlayCaptureExclusion.resolveMode(hideEnabled = true, isRecording = false, isPaused = false, canSkipScreenshot = false)
        )
        assertEquals(
            ExclusionMode.NONE,
            OverlayCaptureExclusion.resolveMode(hideEnabled = true, isRecording = true, isPaused = true, canSkipScreenshot = false)
        )
        assertEquals(
            ExclusionMode.NONE,
            OverlayCaptureExclusion.resolveMode(hideEnabled = true, isRecording = true, isPaused = true, canSkipScreenshot = true)
        )
    }

    @Test fun usesSkipScreenshotWhenPermittedDuringActiveRecording() {
        assertEquals(
            ExclusionMode.SKIP_SCREENSHOT,
            OverlayCaptureExclusion.resolveMode(hideEnabled = true, isRecording = true, isPaused = false, canSkipScreenshot = true)
        )
    }

    @Test fun fallsBackToHidingOverlayWindowWhenSkipScreenshotUnavailable() {
        assertEquals(
            ExclusionMode.HIDE_OVERLAY,
            OverlayCaptureExclusion.resolveMode(hideEnabled = true, isRecording = true, isPaused = false, canSkipScreenshot = false)
        )
    }
}
