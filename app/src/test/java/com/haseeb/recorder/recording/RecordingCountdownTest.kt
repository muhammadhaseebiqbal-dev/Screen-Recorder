package com.haseeb.recorder.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingCountdownTest {
    @Test fun permissionDialogTimeDoesNotConsumeCountdown() {
        val timer = RecordingCountdown(3000)
        assertEquals(3, timer.seconds(60_000))
        timer.resume(60_000)
        assertEquals(3, timer.seconds(60_000))
        assertEquals(1, timer.seconds(62_999))
        assertEquals(0, timer.seconds(63_000))
    }

    @Test fun eachConfiguredDurationRunsInFull() {
        for (seconds in listOf(3, 5, 10)) {
            val timer = RecordingCountdown(seconds * 1000L)
            timer.resume(100)
            assertEquals(seconds, timer.seconds(100))
            assertEquals(1, timer.seconds(seconds * 1000L + 99))
            assertEquals(0, timer.seconds(seconds * 1000L + 100))
        }
    }

    @Test fun pausedGatewayCannotFinishCountdownInBackground() {
        val timer = RecordingCountdown(5000)
        timer.resume(1000)
        timer.pause(2500)
        assertEquals(3500L, timer.remainingMillis(90_000))
        timer.resume(90_000)
        assertEquals(1, timer.seconds(93_499))
        assertEquals(0, timer.seconds(93_500))
    }

    @Test fun repeatedResumeDoesNotRestartTimer() {
        val timer = RecordingCountdown(3000)
        timer.resume(100)
        timer.resume(2100)
        assertEquals(1, timer.seconds(2100))
    }

    @Test fun recreationPreservesRemainingTime() {
        val timer = RecordingCountdown(10_000)
        timer.resume(100)
        val restored = RecordingCountdown(timer.remainingMillis(2600))
        assertEquals(7500L, restored.remainingMillis(90_000))
        restored.resume(90_000)
        assertEquals(1, restored.seconds(97_499))
        assertEquals(0, restored.seconds(97_500))
    }

    @Test fun disabledOrInvalidDurationsAreReadyImmediately() {
        assertEquals(0, RecordingCountdown(0).seconds(100))
        assertEquals(0, RecordingCountdown(-100).seconds(100))
    }

    @Test fun lateTickStaysAtZero() {
        val timer = RecordingCountdown(3000)
        timer.resume(100)
        assertEquals(0L, timer.remainingMillis(9000))
    }
}
