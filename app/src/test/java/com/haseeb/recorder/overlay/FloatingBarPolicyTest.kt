package com.haseeb.recorder.overlay

import com.haseeb.recorder.overlay.FloatingBarPolicy.OverlayMode
import org.junit.Assert.*
import org.junit.Test

class FloatingBarPolicyTest {
    @Test fun resolvesIdleStartingRecordingAndPausedModes() {
        assertEquals(OverlayMode.IDLE_READY, FloatingBarPolicy.resolveMode(false, false, false))
        assertEquals(OverlayMode.STARTING, FloatingBarPolicy.resolveMode(false, true, false))
        assertEquals(OverlayMode.RECORDING, FloatingBarPolicy.resolveMode(true, false, false))
        assertEquals(OverlayMode.PAUSED, FloatingBarPolicy.resolveMode(true, false, true))
        assertEquals(OverlayMode.RECORDING, FloatingBarPolicy.resolveMode(true, true, false))
    }

    @Test fun requiresOverlayPermissionInAllStates() {
        assertFalse(FloatingBarPolicy.shouldRunOverlayService(true, false, true, true, false))
        assertFalse(FloatingBarPolicy.shouldRunOverlayService(false, false, false, true, false))
    }

    @Test fun honorsAlwaysOnVsRecordingOnlyVisibility() {
        assertFalse(FloatingBarPolicy.shouldRunOverlayService(false, false, true, false, true))
        assertTrue(FloatingBarPolicy.shouldRunOverlayService(false, false, false, true, true))
        assertTrue(FloatingBarPolicy.shouldRunOverlayService(true, false, true, false, true))
        assertTrue(FloatingBarPolicy.shouldRunOverlayService(true, false, false, true, true))
        assertTrue(FloatingBarPolicy.shouldRunOverlayService(false, true, true, false, true))
        assertFalse(FloatingBarPolicy.shouldRunOverlayService(true, false, false, false, true))
    }

    @Test fun keepsOverlayAliveAfterStopOnlyWhenAlwaysOnAndPermitted() {
        assertTrue(FloatingBarPolicy.shouldStopOverlayOnRecordingEnd(false, true))
        assertTrue(FloatingBarPolicy.shouldStopOverlayOnRecordingEnd(true, false))
        assertFalse(FloatingBarPolicy.shouldStopOverlayOnRecordingEnd(true, true))
    }

    @Test fun preventsDoubleStartWhileRecordingOrStarting() {
        assertTrue(FloatingBarPolicy.isStartActionAllowed(false, false))
        assertFalse(FloatingBarPolicy.isStartActionAllowed(false, true))
        assertFalse(FloatingBarPolicy.isStartActionAllowed(true, false))
        assertFalse(FloatingBarPolicy.isStartActionAllowed(true, true))
    }
}
