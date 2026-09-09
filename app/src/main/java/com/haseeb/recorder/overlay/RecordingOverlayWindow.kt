package com.haseeb.recorder.overlay

/*
 * Interface contract for floating overlay user interface implementations.
 */
interface RecordingOverlayWindow {

    /*
     * Inflates the overlay view hierarchy and attaches it to the WindowManager.
     */
    fun show()

    /*
     * Dismisses and removes the overlay view hierarchy from the WindowManager.
     */
    fun dismiss()

    /*
     * Updates UI components to reflect updated recording properties and button states.
     */
    fun onRecordingStateChanged()

    /*
     * Reconfigures visible control options and layout metrics after user settings updates.
     */
    fun onConfigurationChanged()
}
