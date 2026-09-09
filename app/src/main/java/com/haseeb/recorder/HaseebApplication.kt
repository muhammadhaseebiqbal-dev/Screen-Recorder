package com.haseeb.recorder

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.shizuku.ShizukuManager
import com.haseeb.recorder.util.LocaleHelper

/*
 * Application class that runs before any activity.
 * Applies the saved theme mode, language locale, and registers dynamic colors with a precondition.
 */
class HaseebApplication : Application() {

    /*
     * Wraps application base context with saved locale.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(base))
    }

    /*
     * Sets up theme, dynamic color registration, locale, and silent Shizuku manager initialization.
     */
    override fun onCreate() {
        super.onCreate()
        val config = ConfigManager(this)
        config.applyDefaults()

        AppCompatDelegate.setDefaultNightMode(config.getThemeModeValue())
        LocaleHelper.applyLanguage(config.appLanguage)
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { _, _ ->
                    config.isDynamicColorsEnabled
                }
                .build()
        )

        ShizukuManager.init()
    }
}
