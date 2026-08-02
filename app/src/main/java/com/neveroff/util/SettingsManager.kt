package com.neveroff.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemePreference {
    SYSTEM, LIGHT, DARK
}

object SettingsManager {
    private const val PREFS_NAME = "neveroff_settings"
    private lateinit var prefs: SharedPreferences

    private val _themeFlow = MutableStateFlow(ThemePreference.SYSTEM)
    val themeFlow: StateFlow<ThemePreference> = _themeFlow

    private val _languageFlow = MutableStateFlow("en") // "en" or "ru"
    val languageFlow: StateFlow<String> = _languageFlow

    private val _debugPanelFlow = MutableStateFlow(false)
    val debugPanelFlow: StateFlow<Boolean> = _debugPanelFlow

    private val _bubbleTextSizeFlow = MutableStateFlow(16f)
    val bubbleTextSizeFlow: StateFlow<Float> = _bubbleTextSizeFlow

    private val _autoSaveLogsFlow = MutableStateFlow(false)
    val autoSaveLogsFlow: StateFlow<Boolean> = _autoSaveLogsFlow

    private val _logPeriodFlow = MutableStateFlow("12h")
    val logPeriodFlow: StateFlow<String> = _logPeriodFlow

    private val _myBubbleColorFlow = MutableStateFlow<Long>(0xFF3F51B5)
    val myBubbleColorFlow: StateFlow<Long> = _myBubbleColorFlow

    private val _otherBubbleColorFlow = MutableStateFlow<Long>(0xFF009688)
    val otherBubbleColorFlow: StateFlow<Long> = _otherBubbleColorFlow

    private val _chatThemeFlow = MutableStateFlow("Default")
    val chatThemeFlow: StateFlow<String> = _chatThemeFlow

    // Animation & Appearance Settings (Stage 3 & 4)
    private val _bouncinessFlow = MutableStateFlow(0.5f)
    val bouncinessFlow: StateFlow<Float> = _bouncinessFlow

    private val _stretchinessFlow = MutableStateFlow(true)
    val stretchinessFlow: StateFlow<Boolean> = _stretchinessFlow

    private val _allAnimationsFlow = MutableStateFlow(true)
    val allAnimationsFlow: StateFlow<Boolean> = _allAnimationsFlow

    private val _optimiseOptimisationFlow = MutableStateFlow(false)
    val optimiseOptimisationFlow: StateFlow<Boolean> = _optimiseOptimisationFlow

    private val _autoDownloadUpdatesFlow = MutableStateFlow(false)
    val autoDownloadUpdatesFlow: StateFlow<Boolean> = _autoDownloadUpdatesFlow

    private val _wallpaperBrightnessFlow = MutableStateFlow(1.0f)
    val wallpaperBrightnessFlow: StateFlow<Float> = _wallpaperBrightnessFlow

    private val _applyThemeToAlreadyChangedFlow = MutableStateFlow(false)
    val applyThemeToAlreadyChangedFlow: StateFlow<Boolean> = _applyThemeToAlreadyChangedFlow

    // Privacy Settings (Stage 4)
    private val _autoDeleteDaysFlow = MutableStateFlow(0)
    val autoDeleteDaysFlow: StateFlow<Int> = _autoDeleteDaysFlow

    private val _seenIndicatorSelfFlow = MutableStateFlow(true)
    val seenIndicatorSelfFlow: StateFlow<Boolean> = _seenIndicatorSelfFlow

    private val _seenIndicatorOthersFlow = MutableStateFlow(true)
    val seenIndicatorOthersFlow: StateFlow<Boolean> = _seenIndicatorOthersFlow

    private val _visibilityBioFlow = MutableStateFlow("Everyone")
    val visibilityBioFlow: StateFlow<String> = _visibilityBioFlow

    private val _visibilityAvatarFlow = MutableStateFlow("Everyone")
    val visibilityAvatarFlow: StateFlow<String> = _visibilityAvatarFlow

    private val _visibilityOnlineFlow = MutableStateFlow("Everyone")
    val visibilityOnlineFlow: StateFlow<String> = _visibilityOnlineFlow

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        val savedTheme = prefs.getString("theme", ThemePreference.SYSTEM.name) ?: ThemePreference.SYSTEM.name
        _themeFlow.value = ThemePreference.valueOf(savedTheme)

        val savedLanguage = prefs.getString("language", "en") ?: "en"
        _languageFlow.value = savedLanguage

        val savedDebug = prefs.getBoolean("debug_panel", false)
        _debugPanelFlow.value = savedDebug

        val savedTextSize = prefs.getFloat("bubble_text_size", 16f)
        _bubbleTextSizeFlow.value = savedTextSize

        val savedAutoLogs = prefs.getBoolean("auto_save_logs", false)
        _autoSaveLogsFlow.value = savedAutoLogs

        val savedLogPeriod = prefs.getString("log_period", "12h") ?: "12h"
        _logPeriodFlow.value = savedLogPeriod

        _myBubbleColorFlow.value = prefs.getLong("my_bubble_color", 0xFF3F51B5)
        _otherBubbleColorFlow.value = prefs.getLong("other_bubble_color", 0xFF009688)
        _chatThemeFlow.value = prefs.getString("chat_theme", "Default") ?: "Default"

        _bouncinessFlow.value = prefs.getFloat("bounciness", 0.5f)
        _stretchinessFlow.value = prefs.getBoolean("stretchiness", true)
        _allAnimationsFlow.value = prefs.getBoolean("all_animations", true)
        _optimiseOptimisationFlow.value = prefs.getBoolean("optimise_optimisation", false)
        _autoDownloadUpdatesFlow.value = prefs.getBoolean("auto_download_updates", false)
        _wallpaperBrightnessFlow.value = prefs.getFloat("wallpaper_brightness", 1.0f)
        _applyThemeToAlreadyChangedFlow.value = prefs.getBoolean("apply_to_already_changed", false)

        _autoDeleteDaysFlow.value = prefs.getInt("auto_delete_days", 0)
        _seenIndicatorSelfFlow.value = prefs.getBoolean("seen_indicator_self", true)
        _seenIndicatorOthersFlow.value = prefs.getBoolean("seen_indicator_others", true)
        _visibilityBioFlow.value = prefs.getString("visibility_bio", "Everyone") ?: "Everyone"
        _visibilityAvatarFlow.value = prefs.getString("visibility_avatar", "Everyone") ?: "Everyone"
        _visibilityOnlineFlow.value = prefs.getString("visibility_online", "Everyone") ?: "Everyone"
    }

    fun setTheme(theme: ThemePreference) {
        _themeFlow.value = theme
        prefs.edit().putString("theme", theme.name).apply()
    }

    fun setLanguage(language: String) {
        _languageFlow.value = language
        prefs.edit().putString("language", language).apply()
    }

    fun setDebugPanelEnabled(enabled: Boolean) {
        _debugPanelFlow.value = enabled
        prefs.edit().putBoolean("debug_panel", enabled).apply()
    }

    fun setBubbleTextSize(size: Float) {
        _bubbleTextSizeFlow.value = size
        prefs.edit().putFloat("bubble_text_size", size).apply()
    }

    fun setAutoSaveLogs(enabled: Boolean) {
        _autoSaveLogsFlow.value = enabled
        prefs.edit().putBoolean("auto_save_logs", enabled).apply()
    }

    fun setLogPeriod(period: String) {
        _logPeriodFlow.value = period
        prefs.edit().putString("log_period", period).apply()
    }

    fun setMyBubbleColor(color: Long) {
        _myBubbleColorFlow.value = color
        prefs.edit().putLong("my_bubble_color", color).apply()
    }

    fun setOtherBubbleColor(color: Long) {
        _otherBubbleColorFlow.value = color
        prefs.edit().putLong("other_bubble_color", color).apply()
    }

    fun setChatTheme(theme: String) {
        _chatThemeFlow.value = theme
        prefs.edit().putString("chat_theme", theme).apply()
    }

    fun setBounciness(value: Float) {
        _bouncinessFlow.value = value
        prefs.edit().putFloat("bounciness", value).apply()
    }

    fun setStretchiness(enabled: Boolean) {
        _stretchinessFlow.value = enabled
        prefs.edit().putBoolean("stretchiness", enabled).apply()
    }

    fun setAllAnimations(enabled: Boolean) {
        _allAnimationsFlow.value = enabled
        prefs.edit().putBoolean("all_animations", enabled).apply()
    }

    fun setOptimiseOptimisation(enabled: Boolean) {
        _optimiseOptimisationFlow.value = enabled
        prefs.edit().putBoolean("optimise_optimisation", enabled).apply()
    }

    fun setAutoDownloadUpdates(enabled: Boolean) {
        _autoDownloadUpdatesFlow.value = enabled
        prefs.edit().putBoolean("auto_download_updates", enabled).apply()
    }

    fun setWallpaperBrightness(value: Float) {
        _wallpaperBrightnessFlow.value = value
        prefs.edit().putFloat("wallpaper_brightness", value).apply()
    }

    fun setApplyThemeToAlreadyChanged(enabled: Boolean) {
        _applyThemeToAlreadyChangedFlow.value = enabled
        prefs.edit().putBoolean("apply_to_already_changed", enabled).apply()
    }

    fun setAutoDeleteDays(days: Int) {
        _autoDeleteDaysFlow.value = days
        prefs.edit().putInt("auto_delete_days", days).apply()
    }

    fun setSeenIndicatorSelf(enabled: Boolean) {
        _seenIndicatorSelfFlow.value = enabled
        prefs.edit().putBoolean("seen_indicator_self", enabled).apply()
    }

    fun setSeenIndicatorOthers(enabled: Boolean) {
        _seenIndicatorOthersFlow.value = enabled
        prefs.edit().putBoolean("seen_indicator_others", enabled).apply()
    }

    fun setVisibilityBio(value: String) {
        _visibilityBioFlow.value = value
        prefs.edit().putString("visibility_bio", value).apply()
    }

    fun setVisibilityAvatar(value: String) {
        _visibilityAvatarFlow.value = value
        prefs.edit().putString("visibility_avatar", value).apply()
    }

    fun setVisibilityOnline(value: String) {
        _visibilityOnlineFlow.value = value
        prefs.edit().putString("visibility_online", value).apply()
    }
}
