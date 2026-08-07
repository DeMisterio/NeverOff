package com.neveroff.data.repository

import android.util.Log
import com.neveroff.data.local.AppDatabase
import com.neveroff.data.local.ChannelEntity
import com.neveroff.data.local.ChannelPostEntity
import com.neveroff.data.local.decodeReactionCounts
import com.neveroff.data.local.encodeReactionCounts
import com.neveroff.data.local.encodeReactionList
import com.neveroff.data.model.Channel
import com.neveroff.data.model.ChannelInsert
import com.neveroff.data.model.ChannelLink
import com.neveroff.data.model.ChannelPost
import com.neveroff.data.model.ChannelPostInsert
import com.neveroff.data.model.ChannelPostReaction
import com.neveroff.data.model.ChannelPostViewRow
import com.neveroff.data.model.ChannelReactionInsert
import com.neveroff.data.model.ChannelSubscriber
import com.neveroff.data.model.ChannelSubscriberInsert
import com.neveroff.data.model.SocialPlatform
import com.neveroff.data.model.normalizeReactionSet
import com.neveroff.data.remote.SupabaseClient
import com.neveroff.ui.screens.parseSupabaseTimestamp
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Everything the app does with channels: creation, discovery, subscription,
 * posting, views and reactions.
 *
 * Channel content is deliberately *not* encrypted (see the migration header) —
 * so unlike [ChatRepository] this class needs no key material at all.
 */
class ChannelRepository(
    private val db: AppDatabase,
    private val myId: String
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var postsChannel: RealtimeChannel? = null
    private var postsJob: Job? = null
    private var reactionsJob: Job? = null

    /**
     * Which channel the live subscription currently belongs to.
     *
     * One repository instance is shared across the whole nav graph, so opening
     * channel B while A is still on the back stack means both screens talk to
     * this object. Without an owner, B's subscribe would be swallowed by A's
     * still-open socket channel and A's later teardown would then kill B's.
     */
    private var subscribedChannelId: String? = null

    private class CachedChannel(val channel: Channel, val at: Long = System.currentTimeMillis()) {
        fun isStale() = System.currentTimeMillis() - at > LINK_CACHE_TTL_MS
    }

    companion object {
        /** Matches ProfileCache: long enough to survive a scroll, short enough to stay fresh. */
        private const val LINK_CACHE_TTL_MS = 5 * 60 * 1000L

        /**
         * Shared across instances on purpose — every chat screen builds its own
         * repository, and a link resolved in one should not be re-fetched in the next.
         */
        private val resolvedLinks = java.util.concurrent.ConcurrentHashMap<String, CachedChannel>()
    }

    private val channels get() = SupabaseClient.client.postgrest["channels"]
    private val links get() = SupabaseClient.client.postgrest["channel_links"]
    private val subscribers get() = SupabaseClient.client.postgrest["channel_subscribers"]
    private val posts get() = SupabaseClient.client.postgrest["channel_posts"]
    private val reactions get() = SupabaseClient.client.postgrest["channel_post_reactions"]

    // ---------------------------------------------------------------- local

    fun observeChannels(): Flow<List<ChannelEntity>> = db.channelDao().getAllChannels()

    fun observeChannel(channelId: String): Flow<ChannelEntity?> = db.channelDao().observeChannel(channelId)

    fun observePosts(channelId: String): Flow<List<ChannelPostEntity>> = db.channelPostDao().observePosts(channelId)

    suspend fun cachedChannel(channelId: String): ChannelEntity? = db.channelDao().getChannel(channelId)

    suspend fun markChannelRead(channelId: String) =
        db.channelDao().markRead(channelId, System.currentTimeMillis())

    /**
     * Recomputes the unread badge from what's actually stored: posts newer than
     * the last time the channel was opened. Deriving it beats incrementing a
     * counter, which drifts as soon as one delivery path is missed.
     */
    private suspend fun recomputeUnread(channelId: String) {
        val channel = db.channelDao().getChannel(channelId) ?: return
        val unread = db.channelPostDao().countUnread(channelId, channel.lastReadAt, myId)
        db.channelDao().setUnreadCount(channelId, unread)
    }

    // ------------------------------------------------------------- creation

    /**
     * True if [username] is free. Checks profile usernames too, so an n.me link
     * can never be ambiguous between a person and a channel.
     */
    suspend fun isUsernameAvailable(username: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = SupabaseClient.client.postgrest.rpc(
                "is_channel_username_available",
                buildJsonObject { put("p_username", username) }
            )
            result.data.trim().equals("true", ignoreCase = true)
        } catch (e: Exception) {
            e.printStackTrace()
            // Don't claim the name is taken on a network blip — the UNIQUE
            // constraint is the real gate and will reject a genuine collision.
            true
        }
    }

    /** Unguessable slug for a private channel, which has no user-chosen link. */
    private fun randomPrivateSlug(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        return "c" + (1..15).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    suspend fun createChannel(
        name: String,
        description: String?,
        username: String?,
        isPublic: Boolean,
        avatarBytes: ByteArray?,
        socialLinks: List<Pair<SocialPlatform, String>>,
        allowedReactions: List<String> = Channel.DEFAULT_REACTIONS
    ): Result<Channel> = withContext(Dispatchers.IO) {
        try {
            val channelId = UUID.randomUUID().toString()
            val slug = if (isPublic) {
                username?.takeIf { it.isNotBlank() } ?: return@withContext Result.failure(
                    IllegalArgumentException("A public channel needs a link")
                )
            } else {
                randomPrivateSlug()
            }

            val avatarUrl = avatarBytes?.let { uploadChannelAvatar(channelId, it) }

            channels.insert(
                ChannelInsert(
                    id = channelId,
                    owner_id = myId,
                    name = name.trim(),
                    description = description?.trim()?.takeIf { it.isNotBlank() },
                    avatar_url = avatarUrl,
                    username = slug,
                    is_public = isPublic,
                    allowed_reactions = normalizeReactionSet(allowedReactions).take(Channel.MAX_REACTIONS)
                )
            )

            saveLinks(channelId, socialLinks)

            // The owner is a subscriber too, so the channel shows up in their list
            // and the subscriber count starts at a truthful 1.
            runCatching { subscribers.insert(ChannelSubscriberInsert(channelId, myId, notifications_enabled = true)) }

            val created = fetchChannel(channelId)
                ?: return@withContext Result.failure(IllegalStateException("Channel created but could not be loaded"))
            Result.success(created)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun updateChannel(
        channelId: String,
        name: String,
        description: String?,
        username: String?,
        isPublic: Boolean,
        avatarBytes: ByteArray? = null,
        socialLinks: List<Pair<SocialPlatform, String>>? = null,
        allowedReactions: List<String>? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val avatarUrl = avatarBytes?.let { uploadChannelAvatar(channelId, it) }
            channels.update({
                set("name", name.trim())
                set("description", description?.trim()?.takeIf { it.isNotBlank() })
                set("is_public", isPublic)
                if (!username.isNullOrBlank()) set("username", username)
                if (avatarUrl != null) set("avatar_url", avatarUrl)
                // Normalised, not just truncated: a duplicate spelling of the same
                // emoji would otherwise eat a slot and trip channels_reactions_distinct.
                if (allowedReactions != null) {
                    set("allowed_reactions", normalizeReactionSet(allowedReactions).take(Channel.MAX_REACTIONS))
                }
            }) {
                filter { eq("id", channelId) }
            }
            if (socialLinks != null) {
                links.delete { filter { eq("channel_id", channelId) } }
                saveLinks(channelId, socialLinks)
            }
            // Any already-resolved link card for this channel is now out of date
            // (the name, picture or even the slug itself may have changed).
            resolvedLinks.values.removeAll { it.channel.id == channelId }
            fetchChannel(channelId)
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private suspend fun saveLinks(channelId: String, socialLinks: List<Pair<SocialPlatform, String>>) {
        val rows = socialLinks
            .filter { it.second.isNotBlank() }
            .mapIndexed { index, (platform, url) ->
                ChannelLink(
                    id = UUID.randomUUID().toString(),
                    channel_id = channelId,
                    platform = platform.key,
                    url = url.trim(),
                    position = index
                )
            }
        if (rows.isNotEmpty()) links.insert(rows)
    }

    private suspend fun uploadChannelAvatar(channelId: String, bytes: ByteArray): String? = try {
        val bucket = SupabaseClient.client.storage["avatars"]
        val fileName = "channel_${channelId}_${System.currentTimeMillis()}.jpg"
        bucket.upload(fileName, bytes, upsert = true)
        bucket.publicUrl(fileName)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    // ------------------------------------------------------------ discovery

    /** Public channels only — a private channel is unlisted by design. */
    suspend fun searchChannels(query: String): List<Channel> = withContext(Dispatchers.IO) {
        val clean = query.removePrefix("@").removePrefix(com.neveroff.data.model.ChannelLinkScheme.PREFIX).trim()
        if (clean.isBlank()) return@withContext emptyList()
        try {
            // Two passes rather than one OR filter: a channel is findable by its
            // link or by its display name, and merging locally keeps the query
            // shape simple enough to stay readable.
            val byUsername = channels.select {
                filter {
                    eq("is_public", true)
                    ilike("username", "%$clean%")
                }
                limit(20)
            }.decodeList<Channel>()

            val byName = channels.select {
                filter {
                    eq("is_public", true)
                    ilike("name", "%$clean%")
                }
                limit(20)
            }.decodeList<Channel>()

            (byUsername + byName).distinctBy { it.id }.take(20)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Resolves an `n.me/<username>` link. Works for private channels too — being
     * private means unlisted, not unreachable.
     *
     * Results are memoised process-wide: a chat full of shared links would
     * otherwise re-query the same channel every time a bubble scrolls back into
     * view. Resolving a link is not the same as joining, so nothing here writes
     * to the local channel list.
     */
    suspend fun channelByUsername(username: String): Channel? = withContext(Dispatchers.IO) {
        val key = username.lowercase()
        resolvedLinks[key]?.let { cached ->
            if (!cached.isStale()) return@withContext cached.channel
        }
        try {
            val channel = channels.select { filter { eq("username", key) } }
                .decodeList<Channel>()
                .firstOrNull()
            if (channel != null) resolvedLinks[key] = CachedChannel(channel)
            channel
        } catch (e: Exception) {
            e.printStackTrace()
            // Fall back to whatever we last saw rather than showing a dead card.
            resolvedLinks[key]?.channel
        }
    }

    suspend fun fetchChannel(channelId: String): Channel? = withContext(Dispatchers.IO) {
        try {
            val channel = channels.select { filter { eq("id", channelId) } }
                .decodeList<Channel>()
                .firstOrNull()
            channel?.let { cacheChannel(it) }
            channel
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun fetchLinks(channelId: String): List<ChannelLink> = withContext(Dispatchers.IO) {
        try {
            links.select {
                filter { eq("channel_id", channelId) }
                order("position", Order.ASCENDING)
            }.decodeList<ChannelLink>()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Writes a channel into the local mirror, preserving local-only columns. */
    private suspend fun cacheChannel(channel: Channel) {
        val existing = db.channelDao().getChannel(channel.id)
        val subscribed = existing?.isSubscribed ?: (channel.owner_id == myId)
        db.channelDao().upsertChannel(
            ChannelEntity(
                id = channel.id,
                ownerId = channel.owner_id,
                name = channel.name,
                description = channel.description,
                avatarUrl = channel.avatar_url,
                username = channel.username,
                isPublic = channel.is_public,
                allowedReactions = encodeReactionList(channel.allowed_reactions),
                subscriberCount = channel.subscriber_count,
                isSubscribed = subscribed,
                notificationsEnabled = existing?.notificationsEnabled ?: true,
                lastPost = existing?.lastPost,
                updatedAt = existing?.updatedAt ?: parseSupabaseTimestamp(channel.created_at),
                unreadCount = existing?.unreadCount ?: 0,
                lastReadAt = existing?.lastReadAt ?: 0L
            )
        )
    }

    /** Pulls every channel this user is subscribed to into the local list. */
    suspend fun syncMyChannels() = withContext(Dispatchers.IO) {
        try {
            val mySubs = subscribers.select { filter { eq("user_id", myId) } }
                .decodeList<ChannelSubscriber>()
            val ownedChannels = channels.select { filter { eq("owner_id", myId) } }
                .decodeList<Channel>()

            val subscribedIds = mySubs.map { it.channel_id }
            val subscribedChannels = if (subscribedIds.isNotEmpty()) {
                channels.select { filter { isIn("id", subscribedIds) } }.decodeList<Channel>()
            } else emptyList()

            val notifyById = mySubs.associate { it.channel_id to it.notifications_enabled }

            (ownedChannels + subscribedChannels).distinctBy { it.id }.forEach { channel ->
                cacheChannel(channel)
                db.channelDao().updateSubscription(
                    channelId = channel.id,
                    subscribed = channel.id in subscribedIds || channel.owner_id == myId,
                    notify = notifyById[channel.id] ?: true
                )
                db.channelDao().updateSubscriberCount(channel.id, channel.subscriber_count)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --------------------------------------------------------- subscription

    suspend fun isSubscribed(channelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            subscribers.select {
                filter {
                    eq("channel_id", channelId)
                    eq("user_id", myId)
                }
            }.decodeList<ChannelSubscriber>().isNotEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            db.channelDao().getChannel(channelId)?.isSubscribed ?: false
        }
    }

    /**
     * Reconciles the local mirror with the server's view of this subscription.
     * Needed when a channel is opened from a link on a device that has never
     * seen it, but where the account may already follow it elsewhere.
     */
    suspend fun refreshSubscriptionState(channelId: String) = withContext(Dispatchers.IO) {
        try {
            val row = subscribers.select {
                filter {
                    eq("channel_id", channelId)
                    eq("user_id", myId)
                }
            }.decodeList<ChannelSubscriber>().firstOrNull()

            val ownsIt = db.channelDao().getChannel(channelId)?.ownerId == myId
            db.channelDao().updateSubscription(
                channelId = channelId,
                subscribed = row != null || ownsIt,
                notify = row?.notifications_enabled ?: true
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun subscribe(channelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            subscribers.upsert(ChannelSubscriber(channelId, myId, notifications_enabled = true))
            db.channelDao().updateSubscription(channelId, subscribed = true, notify = true)
            fetchChannel(channelId)?.let { db.channelDao().updateSubscriberCount(channelId, it.subscriber_count) }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun unsubscribe(channelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            subscribers.delete {
                filter {
                    eq("channel_id", channelId)
                    eq("user_id", myId)
                }
            }
            db.channelDao().updateSubscription(channelId, subscribed = false, notify = true)
            fetchChannel(channelId)?.let { db.channelDao().updateSubscriberCount(channelId, it.subscriber_count) }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun setNotifications(channelId: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            subscribers.update({ set("notifications_enabled", enabled) }) {
                filter {
                    eq("channel_id", channelId)
                    eq("user_id", myId)
                }
            }
            val cached = db.channelDao().getChannel(channelId)
            db.channelDao().updateSubscription(channelId, cached?.isSubscribed ?: true, enabled)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Removes a channel from this device's list without touching the server. */
    suspend fun forgetLocally(channelId: String) {
        db.channelPostDao().deletePostsForChannel(channelId)
        db.channelDao().deleteChannel(channelId)
    }

    // ---------------------------------------------------------------- posts

    suspend fun syncPosts(channelId: String, limit: Int = 60): Boolean = withContext(Dispatchers.IO) {
        try {
            val remote = posts.select {
                filter { eq("channel_id", channelId) }
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }.decodeList<ChannelPost>()

            if (remote.isEmpty()) return@withContext true

            val postIds = remote.map { it.id }
            val allReactions = try {
                reactions.select { filter { isIn("post_id", postIds) } }.decodeList<ChannelPostReaction>()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
            val byPost = allReactions.groupBy { it.post_id }
            val alreadyViewed = try {
                SupabaseClient.client.postgrest["channel_post_views"].select {
                    filter {
                        isIn("post_id", postIds)
                        eq("user_id", myId)
                    }
                }.decodeList<ChannelPostViewRow>().map { it.post_id }.toSet()
            } catch (e: Exception) {
                e.printStackTrace()
                emptySet()
            }

            val entities = remote.map { post ->
                val postReactions = byPost[post.id].orEmpty()
                ChannelPostEntity(
                    id = post.id,
                    channelId = post.channel_id,
                    authorId = post.author_id,
                    content = post.content,
                    createdAt = parseSupabaseTimestamp(post.created_at),
                    viewCount = post.view_count,
                    reactionCounts = encodeReactionCounts(
                        postReactions.groupingBy { it.emoji }.eachCount()
                    ),
                    myReaction = postReactions.find { it.user_id == myId }?.emoji,
                    viewLogged = post.id in alreadyViewed,
                    sendState = 0
                )
            }
            db.channelPostDao().upsertPosts(entities)

            entities.maxByOrNull { it.createdAt }?.let { latest ->
                db.channelDao().updateLastPost(channelId, latest.content, latest.createdAt)
            }
            recomputeUnread(channelId)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ChannelRepository", "syncPosts failed: ${e.message}")
            false
        }
    }

    /** Publishes a post. Optimistic: the local row appears before the upload. */
    suspend fun createPost(channelId: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val postId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.channelPostDao().upsertPost(
            ChannelPostEntity(
                id = postId,
                channelId = channelId,
                authorId = myId,
                content = content,
                createdAt = now,
                sendState = 1
            )
        )
        db.channelDao().updateLastPost(channelId, content, now)

        return@withContext try {
            posts.insert(
                ChannelPostInsert(
                    id = postId,
                    channel_id = channelId,
                    author_id = myId,
                    content = content
                )
            )
            db.channelPostDao().updateSendState(postId, 0)
            notifySubscribers(postId, channelId, content)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            db.channelPostDao().updateSendState(postId, -1)
            false
        }
    }

    /**
     * Asks the edge function to push this post to subscribers who haven't muted
     * the channel. Best-effort: the post is already stored, so a failure here
     * costs a notification, not the post.
     */
    private suspend fun notifySubscribers(postId: String, channelId: String, content: String) {
        withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL("${com.neveroff.BuildConfig.SUPABASE_URL}/functions/v1/channel-notify")
                val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty(
                        "Authorization",
                        "Bearer ${SupabaseClient.client.auth.currentAccessTokenOrNull() ?: ""}"
                    )
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                val body = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    buildJsonObject {
                        put("record", buildJsonObject {
                            put("id", postId)
                            put("channel_id", channelId)
                            put("author_id", myId)
                            put("content", content)
                        })
                    }
                )
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                com.neveroff.util.DebugLogger.log("Channel notify response: ${connection.responseCode}")
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Owner-only. Viewers never reach this — the UI offers them no delete action. */
    suspend fun deletePost(postId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            posts.delete { filter { eq("id", postId) } }
            db.channelPostDao().deletePost(postId)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ---------------------------------------------------------------- views

    /**
     * Registers that this user has read [postId]. The server RPC is idempotent
     * (`ON CONFLICT DO NOTHING`), and the local `viewLogged` flag stops us from
     * even making the call twice, so a post can never count a user twice.
     */
    suspend fun registerView(postId: String) = withContext(Dispatchers.IO) {
        val local = db.channelPostDao().getPost(postId)
        if (local == null || local.viewLogged || local.authorId == myId) return@withContext
        try {
            SupabaseClient.client.postgrest.rpc(
                "register_post_view",
                buildJsonObject { put("p_post_id", postId) }
            )
            db.channelPostDao().markViewLogged(postId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ------------------------------------------------------------ reactions

    /**
     * Sets, changes or clears this user's reaction on a post. Tapping the emoji
     * you already picked removes it; picking a different one replaces it (one
     * reaction per person per post).
     */
    suspend fun toggleReaction(postId: String, emoji: String): Boolean = withContext(Dispatchers.IO) {
        val local = db.channelPostDao().getPost(postId) ?: return@withContext false
        val counts = decodeReactionCounts(local.reactionCounts).toMutableMap()
        val previous = local.myReaction
        val removing = previous == emoji

        // Optimistic local update so the pill reacts instantly.
        previous?.let { old ->
            val next = (counts[old] ?: 1) - 1
            if (next <= 0) counts.remove(old) else counts[old] = next
        }
        if (!removing) counts[emoji] = (counts[emoji] ?: 0) + 1
        val newMine = if (removing) null else emoji
        db.channelPostDao().updateReactions(postId, encodeReactionCounts(counts), newMine)

        return@withContext try {
            if (removing) {
                reactions.delete {
                    filter {
                        eq("post_id", postId)
                        eq("user_id", myId)
                    }
                }
            } else {
                reactions.upsert(ChannelReactionInsert(post_id = postId, user_id = myId, emoji = emoji))
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            // Roll the optimistic change back so the UI doesn't lie about state.
            db.channelPostDao().updateReactions(postId, local.reactionCounts, previous)
            false
        }
    }

    private suspend fun refreshReactions(postId: String) {
        try {
            val rows = reactions.select { filter { eq("post_id", postId) } }.decodeList<ChannelPostReaction>()
            db.channelPostDao().updateReactions(
                postId,
                encodeReactionCounts(rows.groupingBy { it.emoji }.eachCount()),
                rows.find { it.user_id == myId }?.emoji
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ------------------------------------------------------------- realtime

    fun subscribeToRealtime(channelId: String) {
        if (postsChannel != null && subscribedChannelId == channelId) return
        // Another channel is still holding the socket: hand it over.
        if (postsChannel != null) tearDownSubscription()

        val pending = SupabaseClient.client.realtime.channel("channel_$channelId")
        postsChannel = pending
        subscribedChannelId = channelId

        scope.launch {
            try {
                if (SupabaseClient.client.realtime.status.value != Realtime.Status.CONNECTED) {
                    runCatching { SupabaseClient.client.realtime.connect() }
                    val connected = withTimeoutOrNull(15_000L) {
                        SupabaseClient.client.realtime.status.first { it == Realtime.Status.CONNECTED }
                    }
                    if (connected == null) {
                        com.neveroff.util.DebugLogger.log("Realtime never connected; channel_$channelId stays offline")
                        return@launch
                    }
                }

                // While we were waiting on the socket the user may have moved to
                // another channel, which already claimed the subscription slot.
                // Binding now would overwrite its jobs with this dead one's.
                if (postsChannel !== pending) {
                    runCatching { pending.unsubscribe() }
                    return@launch
                }

                postsJob = pending.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "channel_posts"
                    filter = "channel_id=eq.$channelId"
                }.onEach { action ->
                    try {
                        when (action) {
                            is PostgresAction.Insert -> {
                                val post = action.decodeRecord<ChannelPost>()
                                db.channelPostDao().upsertPost(
                                    ChannelPostEntity(
                                        id = post.id,
                                        channelId = post.channel_id,
                                        authorId = post.author_id,
                                        content = post.content,
                                        createdAt = parseSupabaseTimestamp(post.created_at),
                                        viewCount = post.view_count
                                    )
                                )
                                db.channelDao().updateLastPost(
                                    post.channel_id,
                                    post.content,
                                    parseSupabaseTimestamp(post.created_at)
                                )
                                recomputeUnread(post.channel_id)
                            }
                            is PostgresAction.Update -> {
                                val post = action.decodeRecord<ChannelPost>()
                                val existing = db.channelPostDao().getPost(post.id)
                                db.channelPostDao().upsertPost(
                                    ChannelPostEntity(
                                        id = post.id,
                                        channelId = post.channel_id,
                                        authorId = post.author_id,
                                        content = post.content,
                                        createdAt = parseSupabaseTimestamp(post.created_at),
                                        viewCount = post.view_count,
                                        reactionCounts = existing?.reactionCounts.orEmpty(),
                                        myReaction = existing?.myReaction,
                                        viewLogged = existing?.viewLogged ?: false
                                    )
                                )
                            }
                            is PostgresAction.Delete -> {
                                val postId = action.oldRecord["id"]?.jsonPrimitive?.content
                                if (postId != null) db.channelPostDao().deletePost(postId)
                            }
                            else -> {}
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.launchIn(scope)

                // Reactions arrive on the same socket channel; we can't filter by
                // channel_id here (the table has none), so refresh only posts we
                // actually hold locally.
                reactionsJob = pending.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "channel_post_reactions"
                }.onEach { action ->
                    try {
                        val postId = when (action) {
                            is PostgresAction.Insert -> action.decodeRecord<ChannelPostReaction>().post_id
                            is PostgresAction.Update -> action.decodeRecord<ChannelPostReaction>().post_id
                            is PostgresAction.Delete -> action.oldRecord["post_id"]?.jsonPrimitive?.content
                            else -> null
                        }
                        if (postId != null && db.channelPostDao().getPost(postId) != null) {
                            refreshReactions(postId)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.launchIn(scope)

                pending.subscribe(blockUntilSubscribed = true)
            } catch (e: Exception) {
                com.neveroff.util.DebugLogger.log("Channel subscribe failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Tears down the live subscription. Pass the caller's [channelId] so a screen
     * leaving the back stack can't cancel a subscription that has since been
     * taken over by a newer channel screen.
     */
    fun unsubscribeRealtime(channelId: String? = null) {
        if (channelId != null && subscribedChannelId != null && subscribedChannelId != channelId) return
        tearDownSubscription()
    }

    private fun tearDownSubscription() {
        postsJob?.cancel()
        postsJob = null
        reactionsJob?.cancel()
        reactionsJob = null
        postsChannel?.let { ch ->
            scope.launch { runCatching { ch.unsubscribe() } }
        }
        postsChannel = null
        subscribedChannelId = null
    }
}
