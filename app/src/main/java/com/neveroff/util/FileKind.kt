package com.neveroff.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.Color

/**
 * Works out what a file actually is, from its name.
 *
 * Everything sent through the file picker used to be labelled IMAGE — a PDF, a
 * zip, a spreadsheet, all of them — so the receiving end tried to render a
 * document as a photo and showed a broken frame with no way to tell what had
 * arrived.
 *
 * Classification is by extension rather than by sniffing content, because the
 * classification happens on the *sending* side while the receiving side only
 * ever sees ciphertext. A name is the one piece of information that can travel
 * with an encrypted file without the server learning anything it could not
 * already infer from the size.
 */
enum class FileKind(
    val label: String,
    /** Distinguishes at a glance in the bubble, without needing an icon set. */
    val tint: Color,
    val extensions: Set<String>
) {
    IMAGE("Image", Color(0xFF42A5F5), setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg"
    )),
    VIDEO("Video", Color(0xFFAB47BC), setOf(
        "mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v", "wmv", "flv"
    )),
    AUDIO("Audio", Color(0xFF26A69A), setOf(
        "mp3", "m4a", "wav", "ogg", "opus", "flac", "aac", "wma", "aiff"
    )),
    DOCUMENT("Document", Color(0xFFEF5350), setOf(
        "pdf", "doc", "docx", "odt", "rtf", "txt", "md", "pages", "epub", "djvu"
    )),
    SPREADSHEET("Spreadsheet", Color(0xFF66BB6A), setOf(
        "xls", "xlsx", "ods", "csv", "tsv", "numbers"
    )),
    PRESENTATION("Presentation", Color(0xFFFFA726), setOf(
        "ppt", "pptx", "odp", "key"
    )),
    ARCHIVE("Archive", Color(0xFF8D6E63), setOf(
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "dmg"
    )),
    CODE("Code", Color(0xFF5C6BC0), setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h", "cs", "go",
        "rs", "rb", "php", "swift", "sh", "sql", "json", "xml", "yml", "yaml", "html", "css"
    )),
    APP("App", Color(0xFF78909C), setOf("apk", "aab", "exe", "msi", "deb", "rpm")),
    FONT("Font", Color(0xFF9575CD), setOf("ttf", "otf", "woff", "woff2")),

    /** Anything unrecognised. Named honestly rather than guessed at. */
    OTHER("File", Color(0xFF90A4AE), emptySet());

    companion object {
        fun extensionOf(fileName: String): String =
            fileName.substringAfterLast('.', "").lowercase()

        fun of(fileName: String): FileKind {
            val ext = extensionOf(fileName)
            if (ext.isEmpty()) return OTHER
            return entries.firstOrNull { ext in it.extensions } ?: OTHER
        }

        /**
         * The name the user gave the file, from the content provider.
         *
         * Falls back to the last path segment, then to a generic name. A file
         * arriving as "file" is unhelpful but honest; inventing an extension
         * would make it render as the wrong kind of thing.
         */
        fun displayName(context: Context, uri: Uri): String {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        val name = c.getString(idx)
                        if (!name.isNullOrBlank()) return sanitise(name)
                    }
                }
            }
            return sanitise(uri.lastPathSegment?.substringAfterLast('/') ?: "file")
        }

        /**
         * Strips characters that would break the message envelope.
         *
         * The name travels inside "[FILE:<name>] ..." so a ']' in it would end
         * the tag early and truncate what the receiver sees. Newlines would
         * split the message body outright.
         */
        fun sanitise(name: String): String =
            name.replace(']', ')').replace('[', '(').replace('\n', ' ').trim().take(120)

        /** "4.2 MB" — for showing beside the name. */
        fun humanSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}
