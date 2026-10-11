package com.haseeb.recorder.camera

import org.junit.Assert.*
import org.junit.Test

class CameraPreviewStateTest {
    @Test fun duplicateOpenWhileWaitingIsIgnored() {
        val state = CameraPreviewState()
        assertNotNull(state.beginOpen())
        assertNull(state.beginOpen())
    }

    @Test fun previewWaitsForCameraOpen() {
        val state = CameraPreviewState()
        val token = state.beginOpen()!!
        assertFalse(state.beginPreview(token))
        assertTrue(state.opened(token))
        assertTrue(state.beginPreview(token))
    }

    @Test fun textureAndDeviceCallbacksCannotCreateTwoSessions() {
        val state = CameraPreviewState()
        val token = state.beginOpen()!!
        state.opened(token)
        assertTrue(state.beginPreview(token))
        assertFalse(state.beginPreview(token))
    }

    @Test fun stopBeforeOpenRejectsLateDevice() {
        val state = CameraPreviewState()
        val token = state.beginOpen()!!
        state.close()
        assertFalse(state.opened(token))
        assertFalse(state.isCurrent(token))
    }

    @Test fun switchBeforeOpenRejectsOldDeviceAndAcceptsNewOne() {
        val state = CameraPreviewState()
        val old = state.beginOpen()!!
        state.close()
        val current = state.beginOpen()!!
        assertFalse(state.opened(old))
        assertTrue(state.opened(current))
        assertTrue(state.beginPreview(current))
    }

    @Test fun switchDuringConfigurationRejectsOldSession() {
        val state = CameraPreviewState()
        val old = state.beginOpen()!!
        state.opened(old)
        state.beginPreview(old)
        state.close()
        val current = state.beginOpen()!!
        state.opened(current)
        assertFalse(state.isCurrent(old))
        assertTrue(state.beginPreview(current))
    }

    @Test fun destroyedTextureInvalidatesSession() {
        val state = CameraPreviewState()
        val token = state.beginOpen()!!
        state.opened(token)
        state.beginPreview(token)
        state.close()
        assertFalse(state.isCurrent(token))
        assertFalse(state.beginPreview(token))
    }

    @Test fun recreatedTextureCanOpenNewSession() {
        val state = CameraPreviewState()
        val old = state.beginOpen()!!
        state.opened(old)
        state.beginPreview(old)
        state.close()
        val current = state.beginOpen()!!
        assertTrue(state.opened(current))
        assertTrue(state.beginPreview(current))
    }

    @Test fun repeatedStopsDoNotMakeOldCallbacksCurrent() {
        val state = CameraPreviewState()
        val token = state.beginOpen()!!
        repeat(10) { state.close() }
        assertFalse(state.opened(token))
        assertFalse(state.isCurrent(token))
    }

    @Test fun duplicateOpenedCallbackIsRejected() {
        val state = CameraPreviewState()
        val token = state.beginOpen()!!
        assertTrue(state.opened(token))
        assertFalse(state.opened(token))
    }

    @Test fun usesSupportedAlternativeWhenVgaIsMissing() {
        val supported = listOf(PreviewSize(1920, 1080), PreviewSize(1280, 960))
        assertEquals(PreviewSize(1280, 960), choosePreviewSize(supported))
    }

    @Test fun prefersModestStreamOverLargeSameAspectRatio() {
        assertEquals(PreviewSize(640, 480), choosePreviewSize(listOf(
            PreviewSize(1920, 1440), PreviewSize(640, 480), PreviewSize(1280, 960))))
    }

    @Test fun supportsCameraWithOnlyWidescreenOutput() {
        assertEquals(PreviewSize(1280, 720), choosePreviewSize(listOf(PreviewSize(1280, 720))))
    }

    @Test fun rejectsMissingOrInvalidOutputs() {
        assertNull(choosePreviewSize(emptyList()))
        assertNull(choosePreviewSize(listOf(PreviewSize(0, 480), PreviewSize(640, -1))))
    }
}
