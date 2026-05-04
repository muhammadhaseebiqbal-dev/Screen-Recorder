@file:JvmName("EdgeToEdge")

package com.haseeb.recorder

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.core.view.*

/**
 * Adds status bar height as top padding. Null-safe.
 */
fun View?.applyTopInsets() {
    val view = this ?: return
    val initialTop = view.paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        v.updatePadding(top = initialTop + top)
        insets
    }
}

/**
 * Standard bottom insets for Navigation Bar only. 
 * Use this for BottomNavView to keep it above the system bar.
 */
fun View?.applyBottomInsets() {
    val view = this ?: return
    val initialBottom = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        v.updatePadding(bottom = initialBottom + bottom)
        insets
    }
}

/**
 * POWERFUL: Handles both Navigation Bar and Keyboard (IME).
 * Best for ScrollViews/NestedScrollViews. 
 * This ensures the content is always scrollable above the keyboard.
 */
fun View?.applyBottomAndKeyboardInsets() {
    val view = this ?: return
    val initialBottom = view.paddingBottom
    
    // Ensure the view reacts to the keyboard smoothly
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val imeInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime())
        v.updatePadding(bottom = initialBottom + imeInsets.bottom)
        insets
    }
}

/**
 * Adjusts bottom margin with nav bar and anchor view height.
 */
@JvmOverloads
fun View?.applyBottomMargin(
    anchorView: View? = null,
    gap: Int = 0,
    includeSystemBars: Boolean = true
) {
    val target = this ?: return
    val initialMargin = (target.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

    fun resolveGapPx(): Int {
        return try {
            target.resources.getDimensionPixelSize(gap)
        } catch (_: Exception) {
            gap
        }
    }

    ViewCompat.setOnApplyWindowInsetsListener(target) { _, insets ->
        val navBarHeight = if (includeSystemBars) {
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        } else 0
        
        val anchorHeight = anchorView?.height ?: 0

        target.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = initialMargin + navBarHeight + anchorHeight + resolveGapPx()
        }
        insets
    }

    anchorView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        target.requestApplyInsets()
    }
}

/**
 * Horizontal padding for system bars and notches. 
 * Use on Root view.
 */
fun View?.applySystemBarInsets() {
    val view = this ?: return
    val initialLeft = view.paddingLeft
    val initialRight = view.paddingRight

    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val isLandscape = view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        var left = bars.left
        var right = bars.right

        if (isLandscape) {
            left += cutout.left
            right += cutout.right
        }

        v.setPadding(initialLeft + left, v.paddingTop, initialRight + right, v.paddingBottom)
        insets
    }
}

/**
 * Combined top and bottom padding.
 */
fun View?.applyVerticalInsets() {
    val view = this ?: return
    val initialTop = view.paddingTop
    val initialBottom = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(top = initialTop + bars.top, bottom = initialBottom + bars.bottom)
        insets
    }
}


/*
* Created by Ameer Muawiya Sangha, a Pakistani Developer.
*/