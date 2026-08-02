package com.neveroff.data.cache

import com.neveroff.data.remote.SupabaseClient
import com.neveroff.ui.screens.Profile
import io.github.jan.supabase.postgrest.postgrest
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for user profiles with a 5-minute TTL.
 * Avoids redundant Supabase fetches for the same user within a short window.
 */
object ProfileCache {

    private const val TTL_MS = 5 * 60 * 1000L // 5 minutes

    private data class CachedProfile(
        val profile: Profile,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isStale(): Boolean = System.currentTimeMillis() - timestamp > TTL_MS
    }

    private val cache = ConcurrentHashMap<String, CachedProfile>()

    /**
     * Returns a cached profile if fresh, otherwise fetches from Supabase.
     * Returns null if the profile cannot be found remotely.
     */
    suspend fun getProfile(userId: String): Profile? {
        cache[userId]?.let { cached ->
            if (!cached.isStale()) return cached.profile
        }

        // Fetch from Supabase
        val profile = try {
            SupabaseClient.client.postgrest["profiles"]
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<Profile>()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        profile?.let { put(it) }
        return profile
    }

    /** Insert or update a profile in the cache. */
    fun put(profile: Profile) {
        cache[profile.id] = CachedProfile(profile)
    }

    /** Insert or update multiple profiles in the cache. */
    fun putAll(profiles: List<Profile>) {
        profiles.forEach { put(it) }
    }

    /** Remove a specific user's profile from the cache. */
    fun invalidate(userId: String) {
        cache.remove(userId)
    }

    /** Clear the entire cache. */
    fun clear() {
        cache.clear()
    }
}
