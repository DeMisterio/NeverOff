package com.neveroff.util

import android.content.Context
import java.io.File

/**
 * The one directory decrypted media lives in.
 *
 * Sender and receiver have to agree on where a decrypted copy goes, because the
 * lookup that skips a download is "is the file already there". They did not: the
 * player looked in `context.cacheDir` while the send path wrote next to the
 * source file, so a sender's own upload was never found in cache and was
 * downloaded back from the server to be displayed.
 *
 * Naming the location once removes the chance of them drifting apart again.
 */
object MediaCache {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Cache dir, or a temp fallback if [init] has not run yet. */
    val dir: File
        get() = if (this::appContext.isInitialized) {
            appContext.cacheDir
        } else {
            File(System.getProperty("java.io.tmpdir") ?: ".")
        }

    /** Where the plaintext copy of [remoteName] belongs. */
    fun decryptedFile(remoteName: String, extension: String): File =
        File(dir, "dec_$remoteName.$extension")

    /** Where the ciphertext is staged while downloading [remoteName]. */
    fun encryptedFile(remoteName: String): File = File(dir, "enc_$remoteName")
}
