package com.neveroff.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the channels feature.
 *
 * Channel posts are plaintext on purpose — see supabase/migrations/07_channels.sql.
 * A channel is read by everyone who opens its link, including people who subscribe
 * long after a post was written, so there is no counterpart to derive a shared
 * secret with the way 1:1 chat does.
 */

/** In-app channel link scheme: `n.me/<username>`. */
object ChannelLinkScheme {
    const val PREFIX = "n.me/"

    /**
     * Matches an `n.me/<username>` link anywhere in a message body. The scheme is
     * matched case-insensitively (people type "N.me/" too), but the username part
     * is not: it is lowercase by definition, matching the `channels_username_format`
     * CHECK constraint in the migration.
     */
    val REGEX = Regex("""(?<![A-Za-z0-9_.])(?i:n\.me/)([a-z0-9_]{3,32})(?![A-Za-z0-9_])""")

    fun linkFor(username: String) = "$PREFIX$username"

    /** The single channel username in [text], or null if it isn't exactly one link. */
    fun soleUsernameIn(text: String): String? {
        val trimmed = text.trim()
        val match = REGEX.find(trimmed) ?: return null
        return if (match.value == trimmed) match.groupValues[1] else null
    }

    /** Every channel username referenced in [text], in order of appearance. */
    fun usernamesIn(text: String): List<String> =
        REGEX.findAll(text).map { it.groupValues[1] }.toList()
}

/**
 * Normalises user input for a channel link: lowercase, no spaces, and nothing
 * but letters, digits and underscore. Applied as the user types so the field
 * can never hold a value the server would reject.
 */
fun sanitizeChannelUsername(raw: String): String =
    raw.lowercase()
        .replace(' ', '_')
        .filter { it.isLetterOrDigit() && it.code < 128 || it == '_' }
        .take(32)

/** Mirrors the server's `channels_username_format` constraint, so the UI never offers a value it would reject. */
fun isValidChannelUsername(value: String): Boolean =
    value.length in 3..32 &&
        value.all { (it in 'a'..'z') || (it in '0'..'9') || it == '_' }

@Serializable
data class Channel(
    val id: String,
    val owner_id: String,
    val name: String,
    val description: String? = null,
    val avatar_url: String? = null,
    val username: String,
    val is_public: Boolean = true,
    val allowed_reactions: List<String> = DEFAULT_REACTIONS,
    val subscriber_count: Int = 0,
    val created_at: String? = null
) {
    val link: String get() = ChannelLinkScheme.linkFor(username)

    companion object {
        /** The six reactions offered out of the box, in picker order. */
        val DEFAULT_REACTIONS = listOf("❤️", "👍", "👎", "🤡", "⚡", "😍")

        /**
         * Everything a channel owner can choose to allow.
         *
         * Widened from 14 after readers said the choice felt cramped. Grouped
         * by what a reaction is *for* — approval, amusement, surprise, upset,
         * celebration, emphasis — because an owner picking a set wants one
         * from each register, not fourteen variations on a smile.
         */
        val AVAILABLE_REACTIONS = listOf(
            // Approval and disapproval
            "❤️", "👍", "👎", "🔥", "💯", "🫡",
            // Amusement
            "😂", "🤣", "😍", "🤡", "🤪", "😎",
            // Surprise and doubt
            "😮", "🤯", "🤔", "👀", "🤨", "😱",
            // Upset
            "😢", "😡", "💔", "🥺",
            // Celebration and thanks
            "🎉", "🙏", "🥳", "👏", "🍾",
            // Emphasis
            "⚡", "✅", "❌", "⭐", "🚀", "💡"
        )

        /**
         * How many pills a post can show.
         *
         * Ten rather than six: the grid wraps, so the old cap was a choice
         * about clutter rather than a layout limit, and channels with a broad
         * audience were running out of room to express anything but agreement.
         */
        const val MAX_REACTIONS = 10
    }
}

/** Payload for creating a channel; omits server-managed columns. */
@Serializable
data class ChannelInsert(
    val id: String,
    val owner_id: String,
    val name: String,
    val description: String? = null,
    val avatar_url: String? = null,
    val username: String,
    val is_public: Boolean,
    val allowed_reactions: List<String>
)

enum class SocialPlatform(val key: String, val label: String, val hint: String) {
    INSTAGRAM("instagram", "Instagram", "instagram.com/username"),
    SNAPCHAT("snapchat", "Snapchat", "snapchat.com/add/username"),
    TIKTOK("tiktok", "TikTok", "tiktok.com/@username"),
    TELEGRAM("telegram", "Telegram", "t.me/username");

    companion object {
        fun fromKey(key: String): SocialPlatform? = entries.find { it.key == key }
    }
}

@Serializable
data class ChannelLink(
    val id: String,
    val channel_id: String,
    val platform: String,
    val url: String,
    val position: Int = 0
) {
    val socialPlatform: SocialPlatform? get() = SocialPlatform.fromKey(platform)
}

@Serializable
data class ChannelPost(
    val id: String,
    val channel_id: String,
    val author_id: String,
    val content: String,
    val view_count: Int = 0,
    val created_at: String? = null,
    val edited_at: String? = null
)

@Serializable
data class ChannelSubscriber(
    val channel_id: String,
    val user_id: String,
    val notifications_enabled: Boolean = true,
    val subscribed_at: String? = null
)

@Serializable
data class ChannelPostReaction(
    val post_id: String,
    val user_id: String,
    val emoji: String,
    val created_at: String? = null
)

// Insert payloads carry only the columns we actually set. Reusing the full row
// models would serialise `created_at: null` and clobber the server-side
// DEFAULT NOW(), which those NOT NULL columns reject.

@Serializable
data class ChannelPostInsert(
    val id: String,
    val channel_id: String,
    val author_id: String,
    val content: String
)

@Serializable
data class ChannelSubscriberInsert(
    val channel_id: String,
    val user_id: String,
    val notifications_enabled: Boolean = true
)

@Serializable
data class ChannelReactionInsert(
    val post_id: String,
    val user_id: String,
    val emoji: String
)

/** Only the columns needed to tell which posts this user has already viewed. */
@Serializable
data class ChannelPostViewRow(
    val post_id: String,
    val user_id: String
)

/** A post's reaction tallies plus which emoji (if any) the current user picked. */
data class ReactionSummary(
    val counts: Map<String, Int> = emptyMap(),
    val mine: String? = null
) {
    val total: Int get() = counts.values.sum()
}

/** Column subset used when we only need to know who owns/what a channel is called. */
@Serializable
data class ChannelUsernameRow(
    @SerialName("username") val username: String
)
