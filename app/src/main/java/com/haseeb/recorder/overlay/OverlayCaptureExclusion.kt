package com.haseeb.recorder.overlay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager

/** Controls how the floating recording overlay is excluded from screen recordings. */
internal object OverlayCaptureExclusion {
    enum class ExclusionMode { NONE, SKIP_SCREENSHOT, HIDE_OVERLAY }

    private const val PERMISSION_ACCESS_SURFACE_FLINGER = "android.permission.ACCESS_SURFACE_FLINGER"

    fun resolveMode(
        hideEnabled: Boolean,
        isRecording: Boolean,
        isPaused: Boolean,
        canSkipScreenshot: Boolean
    ): ExclusionMode {
        if (!hideEnabled || !isRecording || isPaused) return ExclusionMode.NONE
        return if (canSkipScreenshot) ExclusionMode.SKIP_SCREENSHOT else ExclusionMode.HIDE_OVERLAY
    }

    fun canUseSkipScreenshot(context: Context): Boolean {
        return context.checkSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER) == PackageManager.PERMISSION_GRANTED
    }

    fun applyWindowExclusion(
        view: View?,
        windowManager: WindowManager,
        params: WindowManager.LayoutParams,
        mode: ExclusionMode,
        onBeforeHide: () -> Unit = {}
    ) {
        val target = view ?: return
        when (mode) {
            ExclusionMode.NONE -> {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                target.visibility = View.VISIBLE
                setSkipScreenshotFlag(target, skip = false)
                updateViewLayoutSafe(target, windowManager, params)
            }
            ExclusionMode.SKIP_SCREENSHOT -> {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                target.visibility = View.VISIBLE
                updateViewLayoutSafe(target, windowManager, params)
                setSkipScreenshotFlag(target, skip = true)
            }
            ExclusionMode.HIDE_OVERLAY -> {
                onBeforeHide()
                setSkipScreenshotFlag(target, skip = false)
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                target.visibility = View.GONE
                updateViewLayoutSafe(target, windowManager, params)
            }
        }
    }

    private fun setSkipScreenshotFlag(view: View, skip: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        view.post {
            try {
                val root = view.parent ?: return@post
                val getSc = root.javaClass.getMethod("getSurfaceControl")
                val sc = getSc.invoke(root) as? SurfaceControl ?: return@post
                if (!sc.isValid) return@post
                val tx = SurfaceControl.Transaction()
                val setSkip = SurfaceControl.Transaction::class.java.getMethod(
                    "setSkipScreenshot",
                    SurfaceControl::class.java,
                    Boolean::class.javaPrimitiveType
                )
                setSkip.invoke(tx, sc, skip)
                tx.apply()
            } catch (_: Throwable) {
                // Restricted on non-system builds; caller handles fallback mode cleanly.
            }
        }
    }

    private fun updateViewLayoutSafe(view: View, windowManager: WindowManager, params: WindowManager.LayoutParams) {
        try {
            if (view.windowToken != null) {
                windowManager.updateViewLayout(view, params)
            }
        } catch (_: Exception) {
        }
    }
}
