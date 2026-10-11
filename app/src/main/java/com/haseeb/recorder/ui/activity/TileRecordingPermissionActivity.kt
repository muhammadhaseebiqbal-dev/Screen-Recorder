package com.haseeb.recorder.ui.activity

/** Tile permission/countdown flow runs in a temporary task, separate from the main app. */
class TileRecordingPermissionActivity : MediaProjectionPermissionActivity() {
    override fun finish() {
        if (isTaskRoot) {
            finishAndRemoveTask()
        } else {
            super.finish()
        }
    }
}
