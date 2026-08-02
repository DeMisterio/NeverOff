package com.neveroff.util

import android.content.SharedPreferences
import com.neveroff.crypto.CryptoManager

/**
 * Thin wrapper around SharedPreferences that transparently encrypts/decrypts string
 * values with CryptoManager's Keystore-wrapped AES key, so PII cached in "neveroff_prefs"
 * (profile id/username/display name/bio/public key) isn't stored in plaintext.
 */
object SecurePrefs {
    fun putString(prefs: SharedPreferences, key: String, value: String?) {
        val encrypted = value?.let { CryptoManager.encryptForPrefs(it) }
        prefs.edit().putString(key, encrypted).apply()
    }

    fun getString(prefs: SharedPreferences, key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return CryptoManager.decryptForPrefs(stored)
    }
}
