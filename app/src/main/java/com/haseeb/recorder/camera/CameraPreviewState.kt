package com.haseeb.recorder.camera

/** Main-thread lifecycle tokens reject callbacks from a closed or switched camera. */
internal class CameraPreviewState {
    var generation = 0
        private set
    private var opening = false
    private var configuring = false

    fun beginOpen(): Int? {
        if (opening) return null
        opening = true
        configuring = false
        return ++generation
    }

    fun opened(token: Int): Boolean {
        if (!isCurrent(token) || !opening) return false
        opening = false
        return true
    }

    fun beginPreview(token: Int): Boolean {
        if (!isCurrent(token) || opening || configuring) return false
        configuring = true
        return true
    }

    fun isCurrent(token: Int): Boolean = token == generation

    fun close() {
        generation++
        opening = false
        configuring = false
    }
}

internal data class PreviewSize(val width: Int, val height: Int)

/** Prefer a modest 4:3 stream, but only return sizes actually advertised by this camera. */
internal fun choosePreviewSize(sizes: List<PreviewSize>): PreviewSize? = sizes
    .filter { it.width > 0 && it.height > 0 }
    .minWithOrNull(compareBy<PreviewSize> {
        kotlin.math.abs(it.width.toDouble() / it.height - 4.0 / 3.0)
    }.thenBy { kotlin.math.abs(it.width.toLong() * it.height - 640L * 480) })
