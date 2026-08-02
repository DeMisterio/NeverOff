package com.neveroff.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Local mirror of a channel. Channels sit alongside chats in the main list, but
 * they are a different shape (no counterpart user, no shared key, no per-chat
 * lock) so they get their own table rather than overloading [ChatEntity].
 */
@Entity(
    tableName = "local_channels",
    indices = [Index(value = ["updatedAt"]), Index(value = ["username"], unique = true)]
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val name: String,
    val description: String? = null,
    val avatarUrl: String? = null,
    val username: String,
    val isPublic: Boolean = true,
    /** Allowed reaction emojis, newline-separated (emojis themselves never contain \n). */
    val allowedReactions: String = "",
    val subscriberCount: Int = 0,
    val isSubscribed: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val lastPost: String? = null,
    val updatedAt: Long = 0L,
    val unreadCount: Int = 0,
    /** Timestamp of the last time this channel was opened; drives [unreadCount]. */
    val lastReadAt: Long = 0L
)

/**
 * Local mirror of a channel post. Unlike [MessageEntity], `content` is genuinely
 * plaintext everywhere (channel posts are broadcast, never E2E-encrypted).
 */
@Entity(
    tableName = "local_channel_posts",
    indices = [Index(value = ["channelId"]), Index(value = ["channelId", "createdAt"])]
)
data class ChannelPostEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val authorId: String,
    val content: String,
    val createdAt: Long,
    val viewCount: Int = 0,
    /** Reaction tallies, encoded by [encodeReactionCounts]. */
    val reactionCounts: String = "",
    /** The emoji this device's user picked, or null. */
    val myReaction: String? = null,
    /** True once the local user's view has been registered on the server. */
    val viewLogged: Boolean = false,
    /** 0 = synced, 1 = pending upload, -1 = failed to send. */
    val sendState: Int = 0
)

@Dao
interface ChannelDao {
    @Query("SELECT * FROM local_channels ORDER BY updatedAt DESC")
    fun getAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM local_channels WHERE id = :channelId LIMIT 1")
    fun observeChannel(channelId: String): Flow<ChannelEntity?>

    @Query("SELECT * FROM local_channels WHERE id = :channelId LIMIT 1")
    suspend fun getChannel(channelId: String): ChannelEntity?

    @Query("SELECT * FROM local_channels WHERE username = :username LIMIT 1")
    suspend fun getChannelByUsername(username: String): ChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: ChannelEntity)

    @Query("UPDATE local_channels SET isSubscribed = :subscribed, notificationsEnabled = :notify WHERE id = :channelId")
    suspend fun updateSubscription(channelId: String, subscribed: Boolean, notify: Boolean)

    @Query("UPDATE local_channels SET subscriberCount = :count WHERE id = :channelId")
    suspend fun updateSubscriberCount(channelId: String, count: Int)

    @Query("UPDATE local_channels SET lastPost = :lastPost, updatedAt = :updatedAt WHERE id = :channelId")
    suspend fun updateLastPost(channelId: String, lastPost: String, updatedAt: Long)

    @Query("UPDATE local_channels SET unreadCount = :count WHERE id = :channelId")
    suspend fun setUnreadCount(channelId: String, count: Int)

    @Query("UPDATE local_channels SET unreadCount = 0, lastReadAt = :readAt WHERE id = :channelId")
    suspend fun markRead(channelId: String, readAt: Long)

    @Query("DELETE FROM local_channels WHERE id = :channelId")
    suspend fun deleteChannel(channelId: String)
}

@Dao
interface ChannelPostDao {
    @Query("SELECT * FROM local_channel_posts WHERE channelId = :channelId ORDER BY createdAt ASC")
    fun observePosts(channelId: String): Flow<List<ChannelPostEntity>>

    @Query("SELECT * FROM local_channel_posts WHERE id = :postId LIMIT 1")
    suspend fun getPost(postId: String): ChannelPostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPost(post: ChannelPostEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosts(posts: List<ChannelPostEntity>)

    @Query("UPDATE local_channel_posts SET reactionCounts = :counts, myReaction = :mine WHERE id = :postId")
    suspend fun updateReactions(postId: String, counts: String, mine: String?)

    @Query("UPDATE local_channel_posts SET viewCount = :viewCount WHERE id = :postId")
    suspend fun updateViewCount(postId: String, viewCount: Int)

    @Query("UPDATE local_channel_posts SET viewLogged = 1, viewCount = viewCount + 1 WHERE id = :postId AND viewLogged = 0")
    suspend fun markViewLogged(postId: String)

    @Query("UPDATE local_channel_posts SET sendState = :state WHERE id = :postId")
    suspend fun updateSendState(postId: String, state: Int)

    @Query("DELETE FROM local_channel_posts WHERE id = :postId")
    suspend fun deletePost(postId: String)

    @Query("DELETE FROM local_channel_posts WHERE channelId = :channelId")
    suspend fun deletePostsForChannel(channelId: String)

    @Query("SELECT MAX(createdAt) FROM local_channel_posts WHERE channelId = :channelId")
    suspend fun latestPostTime(channelId: String): Long?

    /** Posts published after the channel was last opened, excluding the reader's own. */
    @Query("SELECT COUNT(*) FROM local_channel_posts WHERE channelId = :channelId AND createdAt > :since AND authorId != :myId")
    suspend fun countUnread(channelId: String, since: Long, myId: String): Int
}

// --- Encoding helpers for the two "packed" columns -------------------------
// Room converters would need a TypeConverter registration per type; these two
// shapes are small and local enough that plain functions are clearer.

/** ASCII control chars: never present in an emoji or a decimal count. */
private const val PAIR_SEPARATOR = '\u0002'
private const val KEY_VALUE_SEPARATOR = '\u0001'

fun encodeReactionCounts(counts: Map<String, Int>): String =
    counts.entries.joinToString(PAIR_SEPARATOR.toString()) { "${it.key}$KEY_VALUE_SEPARATOR${it.value}" }

fun decodeReactionCounts(encoded: String): Map<String, Int> {
    if (encoded.isBlank()) return emptyMap()
    return encoded.split(PAIR_SEPARATOR)
        .mapNotNull { pair ->
            val parts = pair.split(KEY_VALUE_SEPARATOR)
            val count = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size == 2 && count != null && parts[0].isNotEmpty()) parts[0] to count else null
        }
        .toMap()
}

fun encodeReactionList(reactions: List<String>): String = reactions.joinToString("\n")

fun decodeReactionList(encoded: String): List<String> =
    if (encoded.isBlank()) emptyList() else encoded.split("\n").filter { it.isNotBlank() }
