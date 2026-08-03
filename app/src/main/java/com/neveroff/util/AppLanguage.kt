package com.neveroff.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The language the person chose on first launch.
 *
 * Asked before anything else, because the very next thing they are shown is the
 * Terms and the Privacy Policy — and consent given to a document somebody could
 * not read is not consent. That is the whole reason the picker comes first
 * rather than living in Settings.
 *
 * Stored in plain SharedPreferences rather than SecurePrefs: it is needed before
 * sign-in, it is not sensitive, and encrypting it would only mean the first
 * screen could fail to render if the keystore misbehaved.
 */
object AppLanguage {

    private const val PREFS_NAME = "neveroff_locale"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_CHOSEN = "language_chosen"

    enum class Language(
        val code: String,
        /** Written in the language itself — a picker in a language you cannot read is useless. */
        val nativeName: String,
        val englishName: String
    ) {
        ENGLISH("en", "English", "English"),
        RUSSIAN("ru", "Русский", "Russian");

        companion object {
            fun fromCode(code: String?): Language =
                entries.firstOrNull { it.code == code } ?: ENGLISH

            /** Best guess from the device, used to preselect rather than to decide. */
            fun deviceDefault(): Language = fromCode(Locale.getDefault().language)
        }
    }

    private val _current = MutableStateFlow(Language.ENGLISH)
    val current: StateFlow<Language> = _current.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        val stored = prefs(context).getString(KEY_LANGUAGE, null)
        _current.value = if (stored != null) Language.fromCode(stored) else Language.deviceDefault()
    }

    /** True once the person has actively chosen, as opposed to us guessing from the device. */
    fun hasChosen(context: Context): Boolean = prefs(context).getBoolean(KEY_CHOSEN, false)

    fun set(context: Context, language: Language) {
        prefs(context).edit()
            .putString(KEY_LANGUAGE, language.code)
            .putBoolean(KEY_CHOSEN, true)
            .apply()
        _current.value = language
    }

    fun get(context: Context): Language =
        Language.fromCode(prefs(context).getString(KEY_LANGUAGE, null))
            .takeIf { hasChosen(context) } ?: Language.deviceDefault()
}
