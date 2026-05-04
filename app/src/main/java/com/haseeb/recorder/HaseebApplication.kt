package com.haseeb.recorder

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/*
 * Application class that runs before any activity.
 * Applies the saved theme mode and registers dynamic colors with a precondition.
 */
class HaseebApplication : Application() {

    /*
     * Sets up theme and dynamic color registration.
     * Uses a precondition to check if dynamic colors should be applied on activity creation.
     */
    override fun onCreate() {
        super.onCreate()
        val config = ConfigManager(this)

        AppCompatDelegate.setDefaultNightMode(config.getThemeModeValue())
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { _, _ ->
                    config.isDynamicColorsEnabled
                }
                .build()
        )
    }
}
