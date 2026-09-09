package com.haseeb.recorder.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.graphics.Rect
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import com.haseeb.recorder.R
import kotlin.math.min

/*
 * Central configuration manager for recording settings, video encoding,
 * resolution scaling, orientation, and theme preferences.
 */
class ConfigManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("recorder_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_MIC_ENABLED = "mic_enabled"
        const val KEY_SYSTEM_AUDIO_ENABLED = "system_audio_enabled"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_VIDEO_FPS = "video_fps"
        const val KEY_VIDEO_BITRATE = "video_bitrate"
        const val KEY_VIDEO_ENCODER = "video_encoder"
        const val KEY_VIDEO_ORIENTATION = "video_orientation"
        const val KEY_CUSTOM_STORAGE_URI = "custom_storage_uri"
        const val KEY_SHOW_TOUCHES = "show_touches"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLORS = "dynamic_colors_enabled"
        const val KEY_APP_LANGUAGE = "app_language"
        const val KEY_CAMERA_OVERLAY_ENABLED = "camera_overlay_enabled"
        const val KEY_USE_FRONT_CAMERA = "use_front_camera"
        const val KEY_COUNTDOWN_SECONDS = "countdown_seconds"
        const val KEY_RECORDING_OVERLAY_ENABLED = "recording_overlay_enabled"
        const val KEY_OVERLAY_STYLE = "overlay_style"
        const val KEY_OVERLAY_SHOW_PAUSE = "overlay_show_pause"
        const val KEY_OVERLAY_SHOW_STOP = "overlay_show_stop"
        const val KEY_OVERLAY_SHOW_MUTE = "overlay_show_mute"
        const val KEY_OVERLAY_SHOW_CAMERA = "overlay_show_camera"
        const val KEY_OVERLAY_SHOW_DRAW = "overlay_show_draw"
        const val KEY_PAUSE_ON_SCREEN_OFF = "pause_on_screen_off"
        const val KEY_SHIZUKU_SHOW_TOUCHES = "shizuku_show_touches"
        const val KEY_SHIZUKU_DIRECT_RECORDING = "shizuku_direct_recording"
        const val KEY_SHIZUKU_HIDE_SYSTEM_BARS = "shizuku_hide_system_bars"
        const val KEY_SHIZUKU_ENHANCED_AUDIO = "shizuku_enhanced_audio"

        const val DEFAULT_MIC_ENABLED = true
        const val DEFAULT_SYSTEM_AUDIO_ENABLED = true
        const val DEFAULT_VIDEO_QUALITY = "max"
        const val DEFAULT_VIDEO_FPS = 60
        const val DEFAULT_VIDEO_BITRATE = 0
        const val DEFAULT_VIDEO_ENCODER = "h264"
        const val DEFAULT_VIDEO_ORIENTATION = "auto"
        const val DEFAULT_SHOW_TOUCHES = false
        const val DEFAULT_THEME_MODE = "system"
        const val DEFAULT_DYNAMIC_COLORS = true
        const val DEFAULT_APP_LANGUAGE = "auto"
        const val DEFAULT_CAMERA_OVERLAY_ENABLED = false
        const val DEFAULT_USE_FRONT_CAMERA = true
        const val DEFAULT_COUNTDOWN_SECONDS = 3
        const val DEFAULT_RECORDING_OVERLAY_ENABLED = true
        const val OVERLAY_STYLE_HORIZONTAL = "horizontal"
        const val OVERLAY_STYLE_RADIAL = "radial"
        const val DEFAULT_OVERLAY_STYLE = OVERLAY_STYLE_HORIZONTAL
        const val DEFAULT_OVERLAY_SHOW_PAUSE = true
        const val DEFAULT_OVERLAY_SHOW_STOP = true
        const val DEFAULT_OVERLAY_SHOW_MUTE = true
        const val DEFAULT_OVERLAY_SHOW_CAMERA = true
        const val DEFAULT_OVERLAY_SHOW_DRAW = true
        const val DEFAULT_PAUSE_ON_SCREEN_OFF = false
        const val DEFAULT_SHIZUKU_SHOW_TOUCHES = true
        const val DEFAULT_SHIZUKU_DIRECT_RECORDING = true
        const val DEFAULT_SHIZUKU_HIDE_SYSTEM_BARS = false
        const val DEFAULT_SHIZUKU_ENHANCED_AUDIO = true

        const val QUALITY_MAX = "max"
        const val QUALITY_4K = "4k"
        const val QUALITY_2K = "2k"
        const val QUALITY_1080P = "1080p"
        const val QUALITY_720P = "720p"
        const val QUALITY_480P = "480p"
        const val QUALITY_360P = "360p"
        const val QUALITY_240P = "240p"

        const val ENCODER_H264 = "h264"
        const val ENCODER_HEVC = "hevc"

        const val ORIENTATION_AUTO = "auto"
        const val ORIENTATION_PORTRAIT = "portrait"
        const val ORIENTATION_LANDSCAPE = "landscape"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }

    var isMicEnabled: Boolean
        get() = prefs.getBoolean(KEY_MIC_ENABLED, DEFAULT_MIC_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_MIC_ENABLED, value).apply()

    var isSystemAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYSTEM_AUDIO_ENABLED, DEFAULT_SYSTEM_AUDIO_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_SYSTEM_AUDIO_ENABLED, value).apply()

    var videoQuality: String
        get() = prefs.getString(KEY_VIDEO_QUALITY, DEFAULT_VIDEO_QUALITY) ?: DEFAULT_VIDEO_QUALITY
        set(value) = prefs.edit().putString(KEY_VIDEO_QUALITY, value).apply()

    var videoFps: Int
        get() = prefs.getInt(KEY_VIDEO_FPS, DEFAULT_VIDEO_FPS)
        set(value) = prefs.edit().putInt(KEY_VIDEO_FPS, value).apply()

    var videoEncoder: String
        get() = prefs.getString(KEY_VIDEO_ENCODER, DEFAULT_VIDEO_ENCODER) ?: DEFAULT_VIDEO_ENCODER
        set(value) = prefs.edit().putString(KEY_VIDEO_ENCODER, value).apply()

    var videoOrientation: String
        get() = prefs.getString(KEY_VIDEO_ORIENTATION, DEFAULT_VIDEO_ORIENTATION) ?: DEFAULT_VIDEO_ORIENTATION
        set(value) = prefs.edit().putString(KEY_VIDEO_ORIENTATION, value).apply()

    var customStorageUri: String?
        get() = prefs.getString(KEY_CUSTOM_STORAGE_URI, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_STORAGE_URI, value).apply()

    var showTouches: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOUCHES, DEFAULT_SHOW_TOUCHES)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TOUCHES, value).apply()

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    var isDynamicColorsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLORS, DEFAULT_DYNAMIC_COLORS)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_COLORS, value).apply()

    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, DEFAULT_APP_LANGUAGE) ?: DEFAULT_APP_LANGUAGE
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

    var videoBitrate: Int
        get() = prefs.getInt(KEY_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE)
        set(value) = prefs.edit().putInt(KEY_VIDEO_BITRATE, value).apply()

    var isCameraOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_OVERLAY_ENABLED, DEFAULT_CAMERA_OVERLAY_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_OVERLAY_ENABLED, value).apply()

    var useFrontCamera: Boolean
        get() = prefs.getBoolean(KEY_USE_FRONT_CAMERA, DEFAULT_USE_FRONT_CAMERA)
        set(value) = prefs.edit().putBoolean(KEY_USE_FRONT_CAMERA, value).apply()

    var countdownSeconds: Int
        get() = prefs.getInt(KEY_COUNTDOWN_SECONDS, DEFAULT_COUNTDOWN_SECONDS)
        set(value) = prefs.edit().putInt(KEY_COUNTDOWN_SECONDS, value).apply()

    var isRecordingOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECORDING_OVERLAY_ENABLED, DEFAULT_RECORDING_OVERLAY_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_RECORDING_OVERLAY_ENABLED, value).apply()

    var overlayStyle: String
        get() = prefs.getString(KEY_OVERLAY_STYLE, DEFAULT_OVERLAY_STYLE) ?: DEFAULT_OVERLAY_STYLE
        set(value) = prefs.edit().putString(KEY_OVERLAY_STYLE, value).apply()

    var isOverlayShowPauseEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_SHOW_PAUSE, DEFAULT_OVERLAY_SHOW_PAUSE)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_SHOW_PAUSE, value).apply()

    var isOverlayShowStopEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_SHOW_STOP, DEFAULT_OVERLAY_SHOW_STOP)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_SHOW_STOP, value).apply()

    var isOverlayShowMuteEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_SHOW_MUTE, DEFAULT_OVERLAY_SHOW_MUTE)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_SHOW_MUTE, value).apply()

    var isOverlayShowCameraEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_SHOW_CAMERA, DEFAULT_OVERLAY_SHOW_CAMERA)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_SHOW_CAMERA, value).apply()

    var isOverlayShowDrawEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_SHOW_DRAW, DEFAULT_OVERLAY_SHOW_DRAW)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_SHOW_DRAW, value).apply()

    var isPauseOnScreenOff: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_ON_SCREEN_OFF, DEFAULT_PAUSE_ON_SCREEN_OFF)
        set(value) = prefs.edit().putBoolean(KEY_PAUSE_ON_SCREEN_OFF, value).apply()

    var isShizukuShowTouchesEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIZUKU_SHOW_TOUCHES, DEFAULT_SHIZUKU_SHOW_TOUCHES)
        set(value) = prefs.edit().putBoolean(KEY_SHIZUKU_SHOW_TOUCHES, value).apply()

    var isShizukuDirectRecordingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIZUKU_DIRECT_RECORDING, DEFAULT_SHIZUKU_DIRECT_RECORDING)
        set(value) = prefs.edit().putBoolean(KEY_SHIZUKU_DIRECT_RECORDING, value).apply()

    var isShizukuHideSystemBarsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIZUKU_HIDE_SYSTEM_BARS, DEFAULT_SHIZUKU_HIDE_SYSTEM_BARS)
        set(value) = prefs.edit().putBoolean(KEY_SHIZUKU_HIDE_SYSTEM_BARS, value).apply()

    var isShizukuEnhancedAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIZUKU_ENHANCED_AUDIO, DEFAULT_SHIZUKU_ENHANCED_AUDIO)
        set(value) = prefs.edit().putBoolean(KEY_SHIZUKU_ENHANCED_AUDIO, value).apply()

    /*
     * Ensures all default preference entries exist on first app run.
     */
    fun applyDefaults() {
        val editor = prefs.edit()
        var changed = false
        if (!prefs.contains(KEY_MIC_ENABLED)) { editor.putBoolean(KEY_MIC_ENABLED, DEFAULT_MIC_ENABLED); changed = true }
        if (!prefs.contains(KEY_SYSTEM_AUDIO_ENABLED)) { editor.putBoolean(KEY_SYSTEM_AUDIO_ENABLED, DEFAULT_SYSTEM_AUDIO_ENABLED); changed = true }
        if (!prefs.contains(KEY_VIDEO_QUALITY)) { editor.putString(KEY_VIDEO_QUALITY, DEFAULT_VIDEO_QUALITY); changed = true }
        if (!prefs.contains(KEY_VIDEO_FPS)) { editor.putInt(KEY_VIDEO_FPS, DEFAULT_VIDEO_FPS); changed = true }
        if (!prefs.contains(KEY_VIDEO_BITRATE)) { editor.putInt(KEY_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE); changed = true }
        if (!prefs.contains(KEY_VIDEO_ENCODER)) { editor.putString(KEY_VIDEO_ENCODER, DEFAULT_VIDEO_ENCODER); changed = true }
        if (!prefs.contains(KEY_VIDEO_ORIENTATION)) { editor.putString(KEY_VIDEO_ORIENTATION, DEFAULT_VIDEO_ORIENTATION); changed = true }
        if (!prefs.contains(KEY_SHOW_TOUCHES)) { editor.putBoolean(KEY_SHOW_TOUCHES, DEFAULT_SHOW_TOUCHES); changed = true }
        if (!prefs.contains(KEY_THEME_MODE)) { editor.putString(KEY_THEME_MODE, DEFAULT_THEME_MODE); changed = true }
        if (!prefs.contains(KEY_DYNAMIC_COLORS)) { editor.putBoolean(KEY_DYNAMIC_COLORS, DEFAULT_DYNAMIC_COLORS); changed = true }
        if (!prefs.contains(KEY_APP_LANGUAGE)) { editor.putString(KEY_APP_LANGUAGE, DEFAULT_APP_LANGUAGE); changed = true }
        if (!prefs.contains(KEY_CAMERA_OVERLAY_ENABLED)) { editor.putBoolean(KEY_CAMERA_OVERLAY_ENABLED, DEFAULT_CAMERA_OVERLAY_ENABLED); changed = true }
        if (!prefs.contains(KEY_USE_FRONT_CAMERA)) { editor.putBoolean(KEY_USE_FRONT_CAMERA, DEFAULT_USE_FRONT_CAMERA); changed = true }
        if (!prefs.contains(KEY_COUNTDOWN_SECONDS)) { editor.putInt(KEY_COUNTDOWN_SECONDS, DEFAULT_COUNTDOWN_SECONDS); changed = true }
        if (!prefs.contains(KEY_RECORDING_OVERLAY_ENABLED)) { editor.putBoolean(KEY_RECORDING_OVERLAY_ENABLED, DEFAULT_RECORDING_OVERLAY_ENABLED); changed = true }
        if (!prefs.contains(KEY_OVERLAY_STYLE)) { editor.putString(KEY_OVERLAY_STYLE, DEFAULT_OVERLAY_STYLE); changed = true }
        if (!prefs.contains(KEY_OVERLAY_SHOW_PAUSE)) { editor.putBoolean(KEY_OVERLAY_SHOW_PAUSE, DEFAULT_OVERLAY_SHOW_PAUSE); changed = true }
        if (!prefs.contains(KEY_OVERLAY_SHOW_STOP)) { editor.putBoolean(KEY_OVERLAY_SHOW_STOP, DEFAULT_OVERLAY_SHOW_STOP); changed = true }
        if (!prefs.contains(KEY_OVERLAY_SHOW_MUTE)) { editor.putBoolean(KEY_OVERLAY_SHOW_MUTE, DEFAULT_OVERLAY_SHOW_MUTE); changed = true }
        if (!prefs.contains(KEY_OVERLAY_SHOW_CAMERA)) { editor.putBoolean(KEY_OVERLAY_SHOW_CAMERA, DEFAULT_OVERLAY_SHOW_CAMERA); changed = true }
        if (!prefs.contains(KEY_OVERLAY_SHOW_DRAW)) { editor.putBoolean(KEY_OVERLAY_SHOW_DRAW, DEFAULT_OVERLAY_SHOW_DRAW); changed = true }
        if (!prefs.contains(KEY_PAUSE_ON_SCREEN_OFF)) { editor.putBoolean(KEY_PAUSE_ON_SCREEN_OFF, DEFAULT_PAUSE_ON_SCREEN_OFF); changed = true }
        if (!prefs.contains(KEY_SHIZUKU_SHOW_TOUCHES)) { editor.putBoolean(KEY_SHIZUKU_SHOW_TOUCHES, DEFAULT_SHIZUKU_SHOW_TOUCHES); changed = true }
        if (!prefs.contains(KEY_SHIZUKU_DIRECT_RECORDING)) { editor.putBoolean(KEY_SHIZUKU_DIRECT_RECORDING, DEFAULT_SHIZUKU_DIRECT_RECORDING); changed = true }
        if (!prefs.contains(KEY_SHIZUKU_HIDE_SYSTEM_BARS)) { editor.putBoolean(KEY_SHIZUKU_HIDE_SYSTEM_BARS, DEFAULT_SHIZUKU_HIDE_SYSTEM_BARS); changed = true }
        if (!prefs.contains(KEY_SHIZUKU_ENHANCED_AUDIO)) { editor.putBoolean(KEY_SHIZUKU_ENHANCED_AUDIO, DEFAULT_SHIZUKU_ENHANCED_AUDIO); changed = true }
        if (changed) editor.apply()
    }

    /*
     * Converts the saved theme string to the AppCompatDelegate integer constant.
     */
    fun getThemeModeValue(): Int = when (themeMode) {
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /*
     * Retrieves the physical screen dimensions of the device in pixels.
     */
    fun getMaxSupportedResolution(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds: Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val point = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            Rect(0, 0, point.x, point.y)
        }

        var w = bounds.width()
        var h = bounds.height()
        if (w % 2 != 0) w--
        if (h % 2 != 0) h--

        return Pair(w, h)
    }

    /*
     * Calculates the target resolution based on quality and orientation settings.
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
            else -> -1
        }

        var baseWidth = physicalWidth
        var baseHeight = physicalHeight

        if (targetShortSide > 0) {
            val physicalShortSide = min(physicalWidth, physicalHeight)
            if (targetShortSide < physicalShortSide) {
                val scaleRatio = targetShortSide.toFloat() / physicalShortSide.toFloat()
                baseWidth = (physicalWidth * scaleRatio).toInt()
                baseHeight = (physicalHeight * scaleRatio).toInt()
            }
        }

        var finalWidth = baseWidth
        var finalHeight = baseHeight

        when (videoOrientation) {
            ORIENTATION_PORTRAIT -> {
                if (finalWidth > finalHeight) {
                    finalWidth = baseHeight
                    finalHeight = baseWidth
                }
            }
            ORIENTATION_LANDSCAPE -> {
                if (finalWidth < finalHeight) {
                    finalWidth = baseHeight
                    finalHeight = baseWidth
                }
            }
            ORIENTATION_AUTO -> {
            }
        }

        if (finalWidth % 2 != 0) finalWidth--
        if (finalHeight % 2 != 0) finalHeight--

        return Pair(finalWidth, finalHeight)
    }

    /*
     * Detects the maximum display refresh rate supported by the device.
     */
    fun getMaxDisplayFps(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display ?: @Suppress("DEPRECATION") wm.defaultDisplay
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                wm.defaultDisplay
            }
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay
        }
        val refreshRate = display?.mode?.refreshRate ?: @Suppress("DEPRECATION") display?.refreshRate ?: 60f
        val fps = refreshRate.toInt()
        return when {
            fps >= 120 -> 120
            fps >= 90 -> 90
            else -> 60
        }
    }

    /*
     * Returns a sorted descending list of supported FPS options without duplicates.
     */
    fun getAvailableFpsOptions(shizukuGranted: Boolean = false): List<Int> {
        val maxFps = getMaxDisplayFps()
        val allOptions = listOf(120, 90, 60, 30, 24, 15)
        return if (shizukuGranted || maxFps >= 90) {
            val peak = if (shizukuGranted) 120 else maxFps
            allOptions.filter { it <= peak }.distinct()
        } else {
            allOptions.filter { it <= 60 }.distinct()
        }
    }

    /*
     * Calculates auto bitrate in bps based on current target resolution.
     */
    fun getAutoVideoBitrate(): Int {
        val res = getScaledResolution()
        val pixels = res.first * res.second
        return when {
            pixels >= 3840 * 2160 -> 24_000_000
            pixels >= 2560 * 1440 -> 16_000_000
            pixels >= 1920 * 1080 -> 12_000_000
            pixels >= 1280 * 720 -> 8_000_000
            pixels >= 854 * 480 -> 5_000_000
            pixels >= 640 * 360 -> 2_500_000
            else -> 1_500_000
        }
    }

    /*
     * Calculates optimal video bitrate based on user selection or target resolution.
     */
    fun getOptimalVideoBitrate(): Int {
        if (videoBitrate > 0) return videoBitrate
        return getAutoVideoBitrate()
    }

    /*
     * Returns a human-readable label for the Maximum quality option.
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
     * Returns the list of quality options supported by the current device screen.
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
        return list.distinct()
    }

    /*
     * Checks if the device hardware supports HEVC/H.265 video encoding.
     */
    fun isHevcSupported(): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) return true
            }
        }
        return false
    }

    /*
     * Returns a user-friendly display name for the current storage destination.
     */
    fun getStorageLocationDisplayPath(): String {
        val uriStr = customStorageUri
        if (uriStr.isNullOrEmpty()) {
            return context.getString(R.string.ConfigManager_storage_default)
        }
        return try {
            val docFile = DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
            val name = docFile?.name ?: Uri.parse(uriStr).lastPathSegment ?: uriStr
            context.getString(R.string.ConfigManager_storage_custom, name)
        } catch (e: Exception) {
            context.getString(R.string.ConfigManager_storage_default)
        }
    }
}
