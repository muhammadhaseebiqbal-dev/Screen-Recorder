package com.haseeb.recorder

import android.net.Uri

data class VideoFile(
    val id: Long,
    val uri: Uri,
    val name: String,
    val duration: Long,
    val size: Long,
    val dateAdded: Long
)
