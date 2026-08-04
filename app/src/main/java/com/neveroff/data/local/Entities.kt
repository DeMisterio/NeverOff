package com.neveroff.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_messages",
    indices = [
        Index(value = ["chatId"]),
        Index(value = ["chatId", "createdAt"]),
        Index(value = ["createdAt"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderId: String,
    val content: String, // Decrypted content
    val createdAt: Long,
    val status: Int // -1=failed, 0=pending/sent, 1=delivered, 2=read
)

@Entity(
    tableName = "local_chats",
    indices = [
        Index(value = ["updatedAt"])
    ]
)
data class ChatEntity(
    @PrimaryKey val id: String,
    val otherUserId: String,
    val otherUserName: String,
    val otherPubKey: String,
    val lastMessage: String?,
    val updatedAt: Long,
    val unreadCount: Int = 0,
    val wallpaperUri: String? = null,
    val myBubbleColor: Long? = null,
    val otherBubbleColor: Long? = null,
    val customName: String? = null,
    val isLocked: Boolean = false,
    val pinHash: String? = null,
    val pinSalt: String? = null
)

/**
 * A profile kept on disk so it survives going offline and being restarted.
 *
 * The table existed already but had no DAO and nothing reading it, while
 * ProfileCache held everything in memory behind a five-minute TTL. So every
 * avatar and bio disappeared as soon as the app was reopened without a
 * network, and reappeared only once a request succeeded.
 *
 * `bio` is new here for the same reason — it was fetched, displayed, and never
 * written down anywhere.
 */
@Entity(tableName = "local_profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String?,
    val avatarUrl: String? = null,
    val publicKey: String,
    val bio: String? = null,
    /** When this copy was written, so freshness can be judged offline. */
    val cachedAt: Long = 0L
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val timestamp: Long
)
