package com.haseeb.recorder.data

import android.net.Uri

/*
 * Data class representing a recorded video file with duration, size, date, and resolution technical metadata.
 */
data class VideoFile(
    val id: Long,
    val uri: Uri,
    val name: String,
    val duration: Long,
    val size: Long,
    val dateAdded: Long,
    val qualityFps: String? = null
)
