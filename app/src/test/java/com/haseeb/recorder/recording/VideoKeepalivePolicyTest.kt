package com.haseeb.recorder.recording

import com.haseeb.recorder.recording.VideoKeepalivePolicy.FrameAction
import org.junit.Assert.*
import org.junit.Test

class VideoKeepalivePolicyTest {
    @Test fun emitsBlackFramesContinuouslyWhileDisplaySleeps() {
        val policy = VideoKeepalivePolicy(blackFrameIntervalNs = 100_000_000L, staticHeartbeatIntervalNs = 500_000_000L)
        assertEquals(100_000_000L, policy.onScreenFrameArrived(100_000_000L, 100_000_000L))
        policy.setDisplayOff(true)

        val firstBlack = policy.nextTickAction(120_000_000L)
        assertNotNull(firstBlack)
        assertEquals(FrameAction.DRAW_BLACK, firstBlack!!.action)
        assertTrue(firstBlack.presentationTimeNs > 100_000_000L)

        // Too soon for next black frame.
        assertNull(policy.nextTickAction(160_000_000L))

        val secondBlack = policy.nextTickAction(220_000_000L)
        assertNotNull(secondBlack)
        assertEquals(FrameAction.DRAW_BLACK, secondBlack!!.action)
        assertTrue(secondBlack.presentationTimeNs > firstBlack.presentationTimeNs)
    }

    @Test fun suppressesScreenFramesWhileDisplayIsOffAndResumesWhenOn() {
        val policy = VideoKeepalivePolicy()
        assertNotNull(policy.onScreenFrameArrived(1_000_000_000L, 1_000_000_000L))
        policy.setDisplayOff(true)

        // Stray surface updates during Doze/AoD must not reveal screen frames.
        assertNull(policy.onScreenFrameArrived(1_050_000_000L, 1_050_000_000L))

        policy.setDisplayOff(false)
        val wokePts = policy.onScreenFrameArrived(1_200_000_000L, 1_200_000_000L)
        assertNotNull(wokePts)
        assertTrue(wokePts!! >= 1_200_000_000L)
    }

    @Test fun pausedRecordingEmitsNoKeepaliveOrClosingFrames() {
        val policy = VideoKeepalivePolicy()
        policy.onScreenFrameArrived(1_000_000_000L, 1_000_000_000L)
        policy.setPaused(true)
        policy.setDisplayOff(true)

        assertNull(policy.nextTickAction(2_000_000_000L))
        assertNull(policy.closingFrameAction(2_000_000_000L))
        assertNull(policy.onScreenFrameArrived(2_100_000_000L, 2_100_000_000L))
    }

    @Test fun closingFrameDrawsBlackWhenStoppedWhileAsleep() {
        val policy = VideoKeepalivePolicy()
        policy.onScreenFrameArrived(1_000_000_000L, 1_000_000_000L)
        policy.setDisplayOff(true)
        val black = policy.nextTickAction(2_000_000_000L)!!
        val closing = policy.closingFrameAction(5_500_000_000L)
        assertNotNull(closing)
        assertEquals(FrameAction.DRAW_BLACK, closing!!.action)
        assertEquals(5_500_000_000L, closing.presentationTimeNs)
        assertTrue(closing.presentationTimeNs > black.presentationTimeNs)
    }

    @Test fun closingFrameRedrawsLastFrameWhenStoppedOnStaticActiveScreen() {
        val policy = VideoKeepalivePolicy()
        policy.onScreenFrameArrived(1_000_000_000L, 1_000_000_000L)
        val closing = policy.closingFrameAction(2_500_000_000L)
        assertNotNull(closing)
        assertEquals(FrameAction.REDRAW_LAST, closing!!.action)
        assertEquals(2_500_000_000L, closing.presentationTimeNs)
    }

    @Test fun staleTimestampAfterDisplayWakeRemainsStrictlyMonotonic() {
        val policy = VideoKeepalivePolicy()
        policy.setDisplayOff(true)
        val blackPts = policy.nextTickAction(5_000_000_000L)!!.presentationTimeNs
        policy.setDisplayOff(false)
        val staleScreenPts = policy.onScreenFrameArrived(4_500_000_000L, 5_100_000_000L)
        assertNotNull(staleScreenPts)
        assertTrue(staleScreenPts!! > blackPts)
    }

    @Test fun staticActiveScreenGetsHeartbeatAfterInterval() {
        val policy = VideoKeepalivePolicy(blackFrameIntervalNs = 100_000_000L, staticHeartbeatIntervalNs = 500_000_000L)
        policy.onScreenFrameArrived(1_000_000_000L, 1_000_000_000L)
        assertNull(policy.nextTickAction(1_400_000_000L))
        val heartbeat = policy.nextTickAction(1_500_000_000L)
        assertNotNull(heartbeat)
        assertEquals(FrameAction.REDRAW_LAST, heartbeat!!.action)
    }

    @Test fun initialBlackFrameEmittedEvenBeforeFirstVirtualDisplayFrame() {
        val policy = VideoKeepalivePolicy(blackFrameIntervalNs = 100_000_000L)
        val cmd = policy.nextTickAction(1_000_000_000L)
        assertNotNull(cmd)
        assertEquals(FrameAction.DRAW_BLACK, cmd!!.action)
    }
}
