package com.haseeb.recorder.overlay

/*
 * Interface defining communication between overlay UI windows and the recording service controller.
 */
interface RecordingOverlayController {

    /*
     * Retrieves the base timestamp in milliseconds used for chronometer synchronization.
     */
    fun getChronometerBase(): Long

    /*
     * Returns whether the active recording session is currently paused.
     */
    fun isPaused(): Boolean

    /*
     * Returns whether microphone audio recording is currently muted.
     */
    fun isMuted(): Boolean

    /*
     * Returns whether floating camera preview overlay is currently active.
     */
    fun isCameraActive(): Boolean

    /*
     * Returns whether screen annotation drawing overlay is currently active.
     */
    fun isDrawActive(): Boolean

    /*
     * Returns whether the pause control button should be displayed in the overlay.
     */
    fun isShowPauseEnabled(): Boolean

    /*
     * Returns whether the stop control button should be displayed in the overlay.
     */
    fun isShowStopEnabled(): Boolean

    /*
     * Returns whether the microphone mute control button should be displayed in the overlay.
     */
    fun isShowMuteEnabled(): Boolean

    /*
     * Returns whether the camera preview toggle button should be displayed in the overlay.
     */
    fun isShowCameraEnabled(): Boolean

    /*
     * Returns whether the screen draw toggle button should be displayed in the overlay.
     */
    fun isShowDrawEnabled(): Boolean

    /*
     * Invoked when the pause or resume control action is triggered by the user.
     */
    fun onPauseClicked()

    /*
     * Invoked when the microphone mute or unmute control action is triggered by the user.
     */
    fun onMuteClicked()

    /*
     * Invoked when the camera preview toggle control action is triggered by the user.
     */
    fun onCameraClicked()

    /*
     * Invoked when the screen annotation draw tool action is triggered by the user.
     */
    fun onDrawClicked()

    /*
     * Invoked when the stop recording action is triggered by the user.
     */
    fun onStopClicked()
}
