package com.haseeb.recorder

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import kotlin.math.min

/*
 * Central configuration manager for all recording settings.
 * Handles dynamic resolution calculation based on the device's actual aspect ratio.
 */
class ConfigManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("recorder_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_MIC_ENABLED = "mic_enabled"
        private const val KEY_SYSTEM_AUDIO_ENABLED = "system_audio_enabled"
        private const val KEY_VIDEO_QUALITY = "video_quality"
        private const val KEY_VIDEO_FPS = "video_fps"
        private const val KEY_USE_HEVC = "use_hevc"
        private const val KEY_SHOW_TOUCHES = "show_touches"

        const val QUALITY_MAX = "max"
        const val QUALITY_4K = "4k"
        const val QUALITY_2K = "2k"
        const val QUALITY_1080P = "1080p"
        const val QUALITY_720P = "720p"
        const val QUALITY_480P = "480p"
        const val QUALITY_360P = "360p"
        const val QUALITY_240P = "240p"
    }

    var isMicEnabled: Boolean
        get() = prefs.getBoolean(KEY_MIC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MIC_ENABLED, value).apply()

    var isSystemAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYSTEM_AUDIO_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SYSTEM_AUDIO_ENABLED, value).apply()

    var videoQuality: String
        get() = prefs.getString(KEY_VIDEO_QUALITY, QUALITY_MAX) ?: QUALITY_MAX
        set(value) = prefs.edit().putString(KEY_VIDEO_QUALITY, value).apply()

    var videoFps: Int
        get() = prefs.getInt(KEY_VIDEO_FPS, 60)
        set(value) = prefs.edit().putInt(KEY_VIDEO_FPS, value).apply()

    var useHEVC: Boolean
        get() = prefs.getBoolean(KEY_USE_HEVC, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_HEVC, value).apply()

    var showTouches: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOUCHES, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TOUCHES, value).apply()

    /*
     * Retrieves the physical screen dimensions of the device.
     * Ensures dimensions are even numbers to prevent encoder crashes.
     */
    fun getMaxSupportedResolution(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds: Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val display = wm.defaultDisplay
            val outRect = Rect()
            display.getRealSize(android.graphics.Point(outRect.right, outRect.bottom))
            outRect
        }

        var w = bounds.width()
        var h = bounds.height()

        if (w % 2 != 0) w--
        if (h % 2 != 0) h--

        return Pair(w, h)
    }

    /*
     * Calculates the target resolution by maintaining the device's original aspect ratio.
     * Prevents black borders while lowering the recording quality and file size.
     */
    fun getScaledResolution(): Pair<Int, Int> {
        val maxRes = getMaxSupportedResolution()
        val physicalWidth = maxRes.first
        val physicalHeight = maxRes.second

        val targetShortSide = when (videoQuality) {
            QUALITY_4K -> 2160
            QUALITY_2K -> 1440
            QUALITY_1080P -> 1080
            QUALITY_720P -> 720
            QUALITY_480P -> 480
            QUALITY_360P -> 360
            QUALITY_240P -> 240
            else -> return maxRes // MAX quality returns original resolution
        }

        val physicalShortSide = min(physicalWidth, physicalHeight)
        
        // If selected quality is larger than physical screen, return physical screen
        if (targetShortSide >= physicalShortSide) {
            return maxRes
        }

        val scaleRatio = targetShortSide.toFloat() / physicalShortSide.toFloat()

        var newWidth = (physicalWidth * scaleRatio).toInt()
        var newHeight = (physicalHeight * scaleRatio).toInt()

        // Encoders require even dimensions
        if (newWidth % 2 != 0) newWidth--
        if (newHeight % 2 != 0) newHeight--

        return Pair(newWidth, newHeight)
    }

    /*
     * Calculates optimal video bitrate dynamically based on the target resolution.
     */
    fun getOptimalVideoBitrate(): Int {
        val res = getScaledResolution()
        val pixels = res.first * res.second
        
        return when {
            pixels >= 3840 * 2160 -> 24_000_000 // 4K
            pixels >= 2560 * 1440 -> 16_000_000 // 2K
            pixels >= 1920 * 1080 -> 10_000_000 // 1080p
            pixels >= 1280 * 720 -> 5_000_000   // 720p
            pixels >= 854 * 480 -> 2_500_000    // 480p
            pixels >= 640 * 360 -> 1_500_000    // 360p
            else -> 1_000_000                   // 240p or lower
        }
    }

    /*
     * Returns a human-readable label for the Maximum quality option dynamically.
     */
    fun getMaxQualityLabel(): String {
        val maxRes = getMaxSupportedResolution()
        val minSide = min(maxRes.first, maxRes.second)
        val label = when {
            minSide >= 2160 -> "4K"
            minSide >= 1440 -> "2K"
            minSide >= 1080 -> "1080p"
            minSide >= 720 -> "720p"
            minSide >= 480 -> "480p"
            minSide >= 360 -> "360p"
            else -> "240p"
        }
        return context.getString(R.string.ConfigManager_quality_max_with_label, label)
    }

    /*
     * Dynamically generates the list of supported quality options for the current device.
     * Ensures options larger than the device's physical screen are excluded.
     */
    fun getAvailableQualityOptions(): List<String> {
        val minSide = min(getMaxSupportedResolution().first, getMaxSupportedResolution().second)
        val list = mutableListOf(QUALITY_MAX)

        if (minSide > 2160) list.add(QUALITY_4K)
        if (minSide > 1440) list.add(QUALITY_2K)
        if (minSide > 1080) list.add(QUALITY_1080P)
        if (minSide > 720) list.add(QUALITY_720P)
        if (minSide > 480) list.add(QUALITY_480P)
        if (minSide > 360) list.add(QUALITY_360P)
        list.add(QUALITY_240P)

        return list
    }
}
