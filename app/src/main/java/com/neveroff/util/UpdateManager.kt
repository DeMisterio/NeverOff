package com.neveroff.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.neveroff.BuildConfig
import com.neveroff.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Serializable
data class ServerVersion(
    val version: String = "",
    val description: String = "",
    val google_link: String = "",
    val sha256: String? = null
)

object UpdateManager {
    val CURRENT_APP_VERSION: String get() = BuildConfig.VERSION_NAME

    suspend fun checkForUpdates(): ServerVersion? = withContext(Dispatchers.IO) {
        try {
            val list = SupabaseClient.client.postgrest["versions"]
                .select()
                .decodeList<ServerVersion>()

            // Pick the highest version rather than whichever row came back last: the query has
            // no ORDER BY, so row order is unspecified and a newly published release is not
            // reliably last once the table has more than a couple of rows.
            val latest = list.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
                ?: return@withContext null

            if (isHigherVersion(latest.version, CURRENT_APP_VERSION)) {
                return@withContext latest
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun isHigherVersion(serverVersion: String, currentVersion: String): Boolean =
        compareVersions(serverVersion, currentVersion) > 0

    /**
     * Orders published version strings. Missing trailing components count as 0 ("2.1" == "2.1.0").
     *
     * Two numbering styles are in play and both have to sort correctly:
     *  - decimal, where a leading zero is a fraction digit: 2.01 < 2.02 < 2.1
     *  - dotted/semver, where each component is a plain integer: 2.9 < 2.10, 2.0.2 < 2.1
     *
     * Reading every component as a plain integer — what this did before — parses "02" as 2 and so
     * ranks 2.02 above 2.1. That is why 2.1 installs were offered 2.02 as an "update". A component
     * is therefore read as a fraction, right-padded against its counterpart, whenever either side
     * wrote it with a leading zero, and as an integer otherwise. The major component is always an
     * integer, so 10.0 still beats 9.9.
     */
    fun compareVersions(a: String, b: String): Int {
        val aParts = a.trim().split(".")
        val bParts = b.trim().split(".")
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val x = aParts.getOrElse(i) { "0" }.trim()
            val y = bParts.getOrElse(i) { "0" }.trim()
            val cmp = if (i > 0 && (hasLeadingZero(x) || hasLeadingZero(y))) {
                compareAsFraction(x, y)
            } else {
                (x.toLongOrNull() ?: 0L).compareTo(y.toLongOrNull() ?: 0L)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun hasLeadingZero(part: String): Boolean = part.length > 1 && part[0] == '0'

    /** Compares "02" against "1" the way 0.02 compares against 0.1, by padding to equal width. */
    private fun compareAsFraction(x: String, y: String): Int {
        val width = maxOf(x.length, y.length)
        val xv = x.padEnd(width, '0').toLongOrNull() ?: 0L
        val yv = y.padEnd(width, '0').toLongOrNull() ?: 0L
        return xv.compareTo(yv)
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        expectedSha256: String?,
        onProgress: (Float) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val url = try {
                URL(downloadUrl)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError("Invalid update URL") }
                return@withContext
            }

            val trustedHost = try { URL(BuildConfig.SUPABASE_URL).host } catch (e: Exception) { null }
            if (!url.protocol.equals("https", ignoreCase = true) || trustedHost.isNullOrBlank() || !url.host.equals(trustedHost, ignoreCase = true)) {
                withContext(Dispatchers.Main) { onError("Update source is not trusted") }
                return@withContext
            }

            if (expectedSha256.isNullOrBlank()) {
                withContext(Dispatchers.Main) { onError("Update is missing a verified checksum") }
                return@withContext
            }

            val connection = url.openConnection() as HttpURLConnection
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                withContext(Dispatchers.Main) {
                    onError("HTTP Error: ${connection.responseCode}")
                }
                return@withContext
            }

            val fileLength = connection.contentLength

            // Stage inside the app's private cache, never externalCacheDir. App-specific
            // external storage is readable and writable by any app holding
            // WRITE_EXTERNAL_STORAGE on API <= 28, which this app still supports (minSdk 26).
            // Combined with REQUEST_INSTALL_PACKAGES that was an arbitrary-install path: another
            // app could overwrite update.apk in the window between the checksum passing below
            // and the installer opening the file, so the bytes that were verified and the bytes
            // that got installed need not be the same ones.
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()
            val apkFile = File(updatesDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()

            val input = connection.inputStream
            val output = FileOutputStream(apkFile)
            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int

            while (input.read(data).also { count = it } != -1) {
                total += count
                output.write(data, 0, count)
                if (fileLength > 0) {
                    val progress = total.toFloat() / fileLength.toFloat()
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
            }

            output.flush()
            output.close()
            input.close()

            // Hash the file as it now exists on disk rather than the bytes as they streamed
            // past. Those are the bytes the installer will read, so those are the ones worth
            // checking.
            val actualSha256 = sha256Of(apkFile)
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                apkFile.delete()
                withContext(Dispatchers.Main) {
                    onError("Update file failed integrity check")
                }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                onComplete(apkFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onError(e.message ?: "Download failed")
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            // minSdk is 26, so the pre-N Uri.fromFile branch that used to sit here was dead
            // code — and on any version that actually reaches this line it would raise
            // FileUriExposedException rather than install anything.
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
