package com.haseeb.recorder.recording

/**
 * Pure state machine governing display-sleep black-frame keepalive, static-frame heartbeats,
 * and strictly monotonic presentation timestamps for the EGL recording surface.
 */
internal class VideoKeepalivePolicy(
    private val blackFrameIntervalNs: Long = DEFAULT_BLACK_INTERVAL_NS,
    private val staticHeartbeatIntervalNs: Long = DEFAULT_STATIC_HEARTBEAT_NS
) {
    enum class FrameAction { NONE, DRAW_BLACK, REDRAW_LAST }

    data class FrameCommand(val action: FrameAction, val presentationTimeNs: Long)

    companion object {
        const val DEFAULT_BLACK_INTERVAL_NS = 100_000_000L // 10 fps black frames during display sleep
        const val DEFAULT_STATIC_HEARTBEAT_NS = 500_000_000L // 2 fps static-screen refresh
        private const val MIN_PTS_STEP_NS = 10_000L
    }

    var isPaused: Boolean = false
        private set
    var isDisplayOff: Boolean = false
        private set
    var hasCapturedFrame: Boolean = false
        private set

    private var lastRenderWallNs: Long = Long.MIN_VALUE
    private var lastPresentationNs: Long = 0L

    fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    fun setDisplayOff(displayOff: Boolean) {
        isDisplayOff = displayOff
        if (displayOff) {
            // Trigger immediate black-frame emission on next tick.
            lastRenderWallNs = Long.MIN_VALUE
        }
    }

    fun onScreenFrameArrived(rawTimestampNs: Long, nowWallNs: Long): Long? {
        hasCapturedFrame = true
        if (isPaused || isDisplayOff) return null
        lastRenderWallNs = nowWallNs
        return allocatePresentationNs(rawTimestampNs)
    }

    fun nextTickAction(nowWallNs: Long): FrameCommand? {
        if (isPaused) return null
        val elapsed = if (lastRenderWallNs == Long.MIN_VALUE) Long.MAX_VALUE else nowWallNs - lastRenderWallNs
        return when {
            isDisplayOff -> {
                if (elapsed >= blackFrameIntervalNs) {
                    lastRenderWallNs = nowWallNs
                    FrameCommand(FrameAction.DRAW_BLACK, allocatePresentationNs(nowWallNs))
                } else null
            }
            !hasCapturedFrame -> {
                if (elapsed >= blackFrameIntervalNs) {
                    lastRenderWallNs = nowWallNs
                    FrameCommand(FrameAction.DRAW_BLACK, allocatePresentationNs(nowWallNs))
                } else null
            }
            elapsed >= staticHeartbeatIntervalNs -> {
                lastRenderWallNs = nowWallNs
                FrameCommand(FrameAction.REDRAW_LAST, allocatePresentationNs(nowWallNs))
            }
            else -> null
        }
    }

    fun closingFrameAction(nowWallNs: Long): FrameCommand? {
        if (isPaused) return null
        lastRenderWallNs = nowWallNs
        val action = if (isDisplayOff || !hasCapturedFrame) FrameAction.DRAW_BLACK else FrameAction.REDRAW_LAST
        return FrameCommand(action, allocatePresentationNs(nowWallNs))
    }

    fun nextTickDelayMs(nowWallNs: Long): Long {
        val intervalNs = if (isDisplayOff || !hasCapturedFrame) blackFrameIntervalNs else staticHeartbeatIntervalNs
        if (lastRenderWallNs == Long.MIN_VALUE) return 0L
        val remainingNs = intervalNs - (nowWallNs - lastRenderWallNs)
        return (remainingNs / 1_000_000L).coerceIn(10L, intervalNs / 1_000_000L)
    }

    private fun allocatePresentationNs(candidateNs: Long): Long {
        val monotonic = if (lastPresentationNs == 0L) candidateNs else maxOf(candidateNs, lastPresentationNs + MIN_PTS_STEP_NS)
        lastPresentationNs = monotonic
        return monotonic
    }
}
