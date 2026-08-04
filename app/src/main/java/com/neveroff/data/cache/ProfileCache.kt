package com.neveroff.data.cache

import android.content.Context
import com.neveroff.data.local.AppDatabase
import com.neveroff.data.local.ProfileEntity
import com.neveroff.data.remote.SupabaseClient
import com.neveroff.ui.screens.Profile
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Profiles, in memory and on disk.
 *
 * This used to be memory-only behind a five-minute TTL, and a failed refresh
 * returned null. Both halves of that were wrong the same way: every avatar and
 * bio vanished the moment the app was reopened without a network, and vanished
 * again five minutes into any session that lost connectivity — because a stale
 * copy was discarded in favour of nothing at all.
 *
 * A profile is remote state we are allowed to render slightly out of date. A
 * name from an hour ago is right almost always; a blank avatar is wrong every
 * time. So the disk copy is authoritative whenever the network cannot be
 * reached, and the TTL only decides when to *try* refreshing, never whether to
 * keep what we already have.
 */
object ProfileCache {

    private const val TTL_MS = 5 * 60 * 1000L

    private data class CachedProfile(
        val profile: Profile,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isStale(): Boolean = System.currentTimeMillis() - timestamp > TTL_MS
    }

    private val cache = ConcurrentHashMap<String, CachedProfile>()

    /**
     * Set once from Application/Activity start so the cache can reach the disk.
     *
     * Held as the application context, never an Activity, so this singleton
     * cannot outlive and leak a screen.
     */
    @Volatile
    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun db(): AppDatabase? =
        appContext?.let { runCatching { AppDatabase.getDatabase(it) }.getOrNull() }

    private fun ProfileEntity.toProfile() = Profile(
        id = id,
        username = username,
        display_name = displayName ?: username,
        bio = bio,
        avatar_url = avatarUrl,
        public_key = publicKey
    )

    private fun Profile.toEntity() = ProfileEntity(
        id = id,
        username = username,
        displayName = display_name,
        avatarUrl = avatar_url,
        publicKey = public_key,
        bio = bio,
        cachedAt = System.currentTimeMillis()
    )

    /**
     * A profile, from memory, then disk, then the network.
     *
     * Returns null only when the profile has never been seen on this device.
     * Anything previously known is returned even when the refresh fails, which
     * is the entire point.
     */
    suspend fun getProfile(userId: String): Profile? {
        cache[userId]?.let { cached ->
            if (!cached.isStale()) return cached.profile
        }

        // Disk before network: it answers instantly and works offline. Its copy
        // also becomes the fallback if the request below fails.
        val stored = withContext(Dispatchers.IO) {
            runCatching { db()?.profileDao()?.get(userId) }.getOrNull()
        }?.toProfile()

        if (stored != null && cache[userId] == null) {
            cache[userId] = CachedProfile(stored, timestamp = 0L) // known, but due a refresh
        }

        val fresh = try {
            withContext(Dispatchers.IO) {
                SupabaseClient.client.postgrest["profiles"]
                    .select { filter { eq("id", userId) } }
                    .decodeSingleOrNull<Profile>()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (fresh != null) {
            put(fresh)
            return fresh
        }

        // Offline, or the row is genuinely gone. Either way the last known copy
        // beats showing a blank avatar and an empty bio.
        return stored ?: cache[userId]?.profile
    }

    /** Insert or update, in memory now and on disk shortly after. */
    fun put(profile: Profile) {
        cache[profile.id] = CachedProfile(profile)
        val database = db() ?: return
        scope.launch {
            runCatching { database.profileDao().upsert(profile.toEntity()) }
        }
    }

    fun putAll(profiles: List<Profile>) {
        profiles.forEach { cache[it.id] = CachedProfile(it) }
        val database = db() ?: return
        scope.launch {
            runCatching { database.profileDao().upsertAll(profiles.map { it.toEntity() }) }
        }
    }

    /**
     * Drop the in-memory copy so the next read refreshes.
     *
     * Deliberately leaves the disk copy in place: this marks a profile as
     * out of date, it does not mean the last known values became useless.
     */
    fun invalidate(userId: String) {
        cache.remove(userId)
    }

    /** Memory only, for the same reason as [invalidate]. */
    fun clear() {
        cache.clear()
    }

    /** Sign-out: the next account must not inherit these. */
    fun clearAll() {
        cache.clear()
        val database = db() ?: return
        scope.launch { runCatching { database.profileDao().clear() } }
    }
}
