package com.haseeb.recorder.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.haseeb.recorder.R
import com.haseeb.recorder.data.ConfigManager
import java.util.Locale

/*
 * Provides helper utilities for managing application locales and supported languages.
 */
object LocaleHelper {

    data class LanguageItem(
        val code: String,
        val displayName: String
    )

    val SUPPORTED_LANGUAGES: List<LanguageItem> = listOf(
        LanguageItem("en", "English"),
        LanguageItem("ar", "العربية"),
        LanguageItem("de", "Deutsch"),
        LanguageItem("es", "Español"),
        LanguageItem("fr", "Français"),
        LanguageItem("hi", "हिन्दी"),
        LanguageItem("ja", "日本語"),
        LanguageItem("pt", "Português"),
        LanguageItem("ru", "Русский"),
        LanguageItem("tr", "Türkçe"),
        LanguageItem("ur", "اردو"),
        LanguageItem("zh", "简体中文")
    )

    /*
     * Converts a language code into a clean, localized display title.
     */
    fun getLanguageDisplayName(code: String): String {
        val found = SUPPORTED_LANGUAGES.find { it.code.equals(code, ignoreCase = true) }
        if (found != null) {
            return found.displayName
        }
        val locale = getLocaleForCode(code)
        val name = locale.getDisplayName(locale)
        return if (name.isNotBlank()) {
            name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
        } else {
            code.uppercase(Locale.ENGLISH)
        }
    }

    /*
     * Parses a language tag string into a valid Java Locale instance.
     */
    fun getLocaleForCode(code: String): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Locale.forLanguageTag(code.replace('_', '-'))
        } else {
            val parts = code.split("-", "_")
            if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
        }
    }

    /*
     * Returns the static list of all supported languages compiled in the application.
     */
    fun getSupportedLanguages(context: Context): List<LanguageItem> {
        return SUPPORTED_LANGUAGES
    }

    /*
     * Returns the formatted display name for the current language or system language mode.
     */
    fun getCurrentLanguageDisplayName(context: Context, langCode: String): String {
        if (langCode.isBlank() || langCode.equals("auto", ignoreCase = true)) {
            return context.getString(R.string.LocaleHelper_language_system)
        }
        return getLanguageDisplayName(langCode)
    }

    /*
     * Applies the selected language code across the app using AppCompatDelegate.
     */
    fun applyLanguage(languageCode: String) {
        val localeList = if (languageCode.isBlank() || languageCode.equals("auto", ignoreCase = true)) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageCode)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    /*
     * Wraps a context with the user-selected locale configuration for instant and persistent layout inflation.
     */
    fun wrapContext(context: Context): Context {
        return try {
            val configManager = ConfigManager(context)
            val lang = configManager.appLanguage
            if (lang.isBlank() || lang.equals("auto", ignoreCase = true)) {
                return context
            }
            val locale = getLocaleForCode(lang)
            Locale.setDefault(locale)
            val config = Configuration(context.resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            context.createConfigurationContext(config)
        } catch (e: Exception) {
            context
        }
    }
}
