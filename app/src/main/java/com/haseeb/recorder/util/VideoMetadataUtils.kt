package com.haseeb.recorder.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Utility functions for inspecting and caching video file technical metadata.
 */
object VideoMetadataUtils {

    private val metadataCache = ConcurrentHashMap<String, Pair<Long, String?>>()

    /*
     * Normalizes raw frame rates against standard recording profile steps.
     */
    private fun normalizeFps(rawFps: Int): Int {
        return when (rawFps) {
            in 13..17 -> 15
            in 22..26 -> 24
            in 28..32 -> 30
            in 57..63 -> 60
            in 87..93 -> 90
            in 116..124 -> 120
            else -> rawFps
        }
    }

    /*
     * Extracts duration in milliseconds and formatted quality@FPS string with caching.
     */
    fun extractMetadata(context: Context, uri: Uri): Pair<Long, String?> {
        val cacheKey = uri.toString()
        metadataCache[cacheKey]?.let { return it }

        var durationMs = 0L
        var width = 0
        var height = 0
        var fps = 0

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (!durStr.isNullOrEmpty()) {
                durationMs = durStr.toLongOrNull() ?: 0L
            }
            val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (!wStr.isNullOrEmpty() && !hStr.isNullOrEmpty()) {
                width = wStr.toIntOrNull() ?: 0
                height = hStr.toIntOrNull() ?: 0
            }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val temp = width
                width = height
                height = temp
            }

            val captureFpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val captureFps = captureFpsStr?.toFloatOrNull()?.roundToInt() ?: 0
            if (captureFps > 0) {
                fps = normalizeFps(captureFps)
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }

        if (fps <= 0 || width <= 0 || height <= 0 || durationMs <= 0L) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        if (width <= 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                            width = format.getInteger(MediaFormat.KEY_WIDTH)
                        }
                        if (height <= 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                            height = format.getInteger(MediaFormat.KEY_HEIGHT)
                        }
                        if (fps <= 0 && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            val trackFps = try {
                                format.getInteger(MediaFormat.KEY_FRAME_RATE)
                            } catch (_: Exception) {
                                try {
                                    format.getFloat(MediaFormat.KEY_FRAME_RATE).roundToInt()
                                } catch (_: Exception) { 0 }
                            }
                            if (trackFps > 0) {
                                fps = normalizeFps(trackFps)
                            }
                        }
                        if (durationMs <= 0 && format.containsKey(MediaFormat.KEY_DURATION)) {
                            durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                        }
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    extractor.release()
                } catch (_: Exception) {}
            }
        }

        if (fps <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val fallbackRetriever = MediaMetadataRetriever()
            try {
                fallbackRetriever.setDataSource(context, uri)
                val frameCountStr = fallbackRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                val frameCount = frameCountStr?.toIntOrNull() ?: 0
                if (frameCount > 0 && durationMs > 0) {
                    val rawCalculated = ((frameCount.toFloat() * 1000f) / durationMs).roundToInt()
                    fps = normalizeFps(rawCalculated)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    fallbackRetriever.release()
                } catch (_: Exception) {}
            }
        }

        var qualityFps: String? = null
        if (width > 0 && height > 0) {
            val minDim = min(width, height)
            qualityFps = if (fps > 0) {
                "${minDim}p@$fps"
            } else {
                "${minDim}p"
            }
        }

        val result = Pair(durationMs, qualityFps)
        metadataCache[cacheKey] = result
        return result
    }
}
