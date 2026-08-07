package com.neveroff.data.model

import com.neveroff.util.FileKind

/**
 * What a media message *is*, parsed once from the message body.
 *
 * A media message travels as `[TAG] <remoteName> <fileKey>`, all of it inside
 * the end-to-end encrypted body, so the original filename and type are known to
 * the two participants and to nobody else — including the server, which sees
 * only an opaque object name.
 *
 * Before this existed, every screen re-derived the type with its own
 * `startsWith` chain, and they disagreed. ChatScreen's list of media prefixes
 * omitted `[FILE_VIDEO]` and `[FILE:`, so those messages never reached the
 * media renderer at all and were drawn as their own raw envelope — which is
 * how a video sent through the file picker showed up as a `.enc` filename
 * instead of a video. The player's download guard omitted `[FILE_VIDEO]` too,
 * so even reaching it would have left a spinner that never resolved.
 *
 * One parser, one answer.
 */
enum class MediaKind {
    IMAGE,
    /** A rectangular video with controls. */
    VIDEO,
    /** The round note from the circle recorder — and only from there. */
    VIDEO_NOTE,
    VOICE,
    /** Anything else, shown as a named attachment. */
    FILE
}

data class MediaEnvelope(
    val kind: MediaKind,
    /** Storage object key. Opaque; carries no information about the content. */
    val remoteName: String,
    /** Base64 key for this file, usable only by someone who can read the body. */
    val fileKey: String,
    /**
     * The name the sender's file had, when there was one. Camera captures and
     * voice notes have no original name and get a generated one on save.
     */
    val originalName: String?
) {
    /** Extension to give the decrypted copy so the OS can open it. */
    val extension: String
        get() = originalName
            ?.let { FileKind.extensionOf(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: when (kind) {
                MediaKind.IMAGE -> "jpg"
                MediaKind.VIDEO, MediaKind.VIDEO_NOTE -> "mp4"
                MediaKind.VOICE -> "m4a"
                MediaKind.FILE -> "file"
            }

    /**
     * MIME type, derived from the original extension where there is one.
     *
     * Derived rather than stored: the extension is already carried inside the
     * encrypted body, and a MIME string in the envelope would be a second
     * source of truth that could disagree with it.
     */
    val mimeType: String
        get() = mimeTypeFor(extension) ?: when (kind) {
            MediaKind.IMAGE -> "image/jpeg"
            MediaKind.VIDEO, MediaKind.VIDEO_NOTE -> "video/mp4"
            MediaKind.VOICE -> "audio/mp4"
            MediaKind.FILE -> "application/octet-stream"
        }

    /** True when this should play as a video, however it was sent. */
    val isVideo: Boolean
        get() = kind == MediaKind.VIDEO || kind == MediaKind.VIDEO_NOTE ||
            mimeType.startsWith("video/")

    /** A filename to save under, falling back to a generated one. */
    fun saveAsName(timestamp: Long): String =
        originalName?.takeIf { it.isNotBlank() } ?: "NeverOff_$timestamp.$extension"

    companion object {
        /**
         * Tags written by current builds. The type is stated, not inferred:
         * a round note is a round note because the circle recorder said so,
         * not because of where the file came from.
         */
        const val TAG_IMAGE = "IMAGE"
        const val TAG_VIDEO = "VIDEO_FILE"
        const val TAG_VIDEO_NOTE = "VIDEO_NOTE"
        const val TAG_VOICE = "VOICE"
        const val TAG_FILE = "FILE"

        /**
         * Builds the message body for a send.
         *
         * [originalName] rides along for anything the user picked rather than
         * captured, so the receiver can restore it.
         */
        fun buildBody(
            kind: MediaKind,
            remoteName: String,
            fileKey: String,
            originalName: String? = null
        ): String {
            val tag = when (kind) {
                MediaKind.IMAGE -> TAG_IMAGE
                MediaKind.VIDEO -> TAG_VIDEO
                MediaKind.VIDEO_NOTE -> TAG_VIDEO_NOTE
                MediaKind.VOICE -> TAG_VOICE
                MediaKind.FILE -> TAG_FILE
            }
            val named = originalName?.takeIf { it.isNotBlank() }?.let { ":${FileKind.sanitise(it)}" }.orEmpty()
            return "[$tag$named] $remoteName $fileKey"
        }

        /** True if [body] is a media message at all, without fully parsing it. */
        fun isMediaBody(body: String): Boolean = parse(body) != null

        /**
         * Reads a message body, or null if it is not a media message.
         *
         * Legacy tags are still understood, because they are sitting in message
         * histories and in Room on every installed device:
         *   `[VIDEO]`      — a round note, from when that was the only video.
         *   `[FILE_VIDEO]` — a rectangular video with no name carried.
         */
        fun parse(body: String): MediaEnvelope? {
            if (!body.startsWith("[")) return null
            val close = body.indexOf(']')
            if (close <= 0) return null

            val tagPart = body.substring(1, close)
            val rest = body.substring(close + 1).trim()
            val parts = rest.split(" ")
            if (parts.size < 2) return null
            val remoteName = parts[0]
            val fileKey = parts[1]
            if (remoteName.isEmpty() || fileKey.isEmpty()) return null

            val tag = tagPart.substringBefore(':')
            val name = tagPart.substringAfter(':', "").takeIf { it.isNotBlank() }

            val kind = when (tag) {
                TAG_IMAGE -> MediaKind.IMAGE
                TAG_VIDEO -> MediaKind.VIDEO
                TAG_VIDEO_NOTE -> MediaKind.VIDEO_NOTE
                TAG_VOICE -> MediaKind.VOICE
                TAG_FILE -> MediaKind.FILE
                // Legacy, read-only.
                "VIDEO" -> MediaKind.VIDEO_NOTE
                "FILE_VIDEO" -> MediaKind.VIDEO
                else -> return null
            }

            // A file whose extension says video plays as video rather than
            // sitting as an attachment nobody can open in place.
            val resolvedKind =
                if (kind == MediaKind.FILE && name != null && FileKind.of(name) == FileKind.VIDEO) {
                    MediaKind.VIDEO
                } else {
                    kind
                }

            return MediaEnvelope(resolvedKind, remoteName, fileKey, name)
        }

        /** Minimal extension-to-MIME map, covering what the pickers can produce. */
        private fun mimeTypeFor(extension: String): String? = when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt", "md" -> "text/plain"
            else -> null
        }
    }
}
