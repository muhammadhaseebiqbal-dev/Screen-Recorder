package com.haseeb.recorder.overlay

/** Pure state and lifecycle rules for the optional always-on floating bar. */
object FloatingBarPolicy {
    enum class OverlayMode {
        IDLE_READY,
        STARTING,
        RECORDING,
        PAUSED
    }

    fun resolveMode(isRecording: Boolean, isStarting: Boolean, isPaused: Boolean): OverlayMode =
        when {
            isRecording && isPaused -> OverlayMode.PAUSED
            isRecording -> OverlayMode.RECORDING
            isStarting -> OverlayMode.STARTING
            else -> OverlayMode.IDLE_READY
        }

    fun shouldRunOverlayService(
        isRecording: Boolean,
        isStarting: Boolean,
        isOverlayEnabledDuringRecording: Boolean,
        isAlwaysShowEnabled: Boolean,
        hasOverlayPermission: Boolean
    ): Boolean {
        if (!hasOverlayPermission) return false
        return if (isRecording || isStarting) {
            isOverlayEnabledDuringRecording || isAlwaysShowEnabled
        } else {
            isAlwaysShowEnabled
        }
    }

    fun shouldStopOverlayOnRecordingEnd(
        isAlwaysShowEnabled: Boolean,
        hasOverlayPermission: Boolean
    ): Boolean = !(isAlwaysShowEnabled && hasOverlayPermission)

    fun isStartActionAllowed(isRecording: Boolean, isStarting: Boolean): Boolean =
        !isRecording && !isStarting
}
