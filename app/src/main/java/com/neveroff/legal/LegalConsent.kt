package com.neveroff.legal

import android.content.Context
import com.neveroff.util.AppLanguage

/**
 * Whether this person has accepted the Terms and the Privacy Policy, and which
 * version of them.
 *
 * The version matters. If the wording changes materially we bump
 * [LegalTexts.VERSION], and everyone is asked again rather than being treated as
 * having agreed to text they never saw. Silently carrying an old acceptance
 * forward would make the record worthless — which is the situation the record
 * exists to prevent.
 *
 * The language is stored alongside because "they agreed" is a weaker claim than
 * "they agreed to the Russian text, on this date, at this version".
 */
object LegalConsent {

    private const val PREFS_NAME = "neveroff_legal"
    private const val KEY_VERSION = "accepted_version"
    private const val KEY_AT = "accepted_at"
    private const val KEY_LANGUAGE = "accepted_language"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True only for the version currently shipping. An older acceptance does not count. */
    fun hasAccepted(context: Context): Boolean =
        prefs(context).getInt(KEY_VERSION, 0) >= LegalTexts.VERSION

    fun record(context: Context, language: AppLanguage.Language) {
        prefs(context).edit()
            .putInt(KEY_VERSION, LegalTexts.VERSION)
            .putLong(KEY_AT, System.currentTimeMillis())
            .putString(KEY_LANGUAGE, language.code)
            .apply()
    }

    fun acceptedAt(context: Context): Long = prefs(context).getLong(KEY_AT, 0L)

    fun acceptedLanguage(context: Context): AppLanguage.Language =
        AppLanguage.Language.fromCode(prefs(context).getString(KEY_LANGUAGE, null))

    /**
     * Cleared on sign-out only if you want the next person on this device to be
     * asked again. Currently NOT called from logout: the acceptance belongs to
     * the device's user, and re-asking on every sign-out would train people to
     * dismiss it without reading.
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
