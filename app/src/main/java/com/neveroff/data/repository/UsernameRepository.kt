package com.neveroff.data.repository

import com.neveroff.data.remote.SupabaseClient
import com.neveroff.util.ErrorReporter
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** One username claimed by an account. See supabase/migrations/10_usernames.sql. */
@Serializable
data class UserUsername(
    val id: String,
    val user_id: String,
    val username: String,
    val is_primary: Boolean = false,
    val is_active: Boolean = true,
    val created_at: String? = null
)

@Serializable
private data class UsernameInsert(
    val user_id: String,
    val username: String,
    val is_primary: Boolean,
    val is_active: Boolean = true
)

/**
 * Usernames for the signed-in account.
 *
 * A handle is claimed permanently: giving one up deactivates it rather than
 * deleting it, so it can never be taken by somebody else and start receiving
 * messages meant for its former owner. Nothing here deletes a row, and the
 * table has no DELETE policy to allow it.
 */
class UsernameRepository(private val myId: String) {

    /** Live handles, primary first. Deactivated claims are not shown. */
    suspend fun myUsernames(): List<UserUsername> = withContext(Dispatchers.IO) {
        try {
            SupabaseClient.client.postgrest["user_usernames"]
                .select(Columns.ALL) {
                    filter {
                        eq("user_id", myId)
                        eq("is_active", true)
                    }
                }
                .decodeList<UserUsername>()
                .sortedByDescending { it.is_primary }
        } catch (e: Exception) {
            ErrorReporter.report(TAG, e)
            emptyList()
        }
    }

    /**
     * Whether [username] can be claimed right now.
     *
     * A handle this account already holds — including one it gave up — counts
     * as available to it, so taking your own old name back is not blocked by
     * your own claim on it.
     */
    suspend fun isAvailable(username: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = SupabaseClient.client.postgrest["user_usernames"]
                .select(Columns.ALL) { filter { eq("username", username.lowercase()) } }
                .decodeList<UserUsername>()
                .firstOrNull()
            existing == null || existing.user_id == myId
        } catch (e: Exception) {
            ErrorReporter.report(TAG, e)
            // Not knowing is not the same as free. Saying "available" here would
            // send the user into a save that fails on the unique constraint.
            false
        }
    }

    /**
     * Claims [username] for this account, or reactivates it if already claimed
     * here. Made primary when it is the account's first.
     */
    suspend fun addUsername(username: String, makePrimary: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val normalized = username.lowercase()
                val mine = SupabaseClient.client.postgrest["user_usernames"]
                    .select(Columns.ALL) { filter { eq("user_id", myId) } }
                    .decodeList<UserUsername>()

                val existing = mine.firstOrNull { it.username == normalized }
                val shouldBePrimary = makePrimary || mine.none { it.is_active && it.is_primary }

                if (shouldBePrimary) clearPrimaryFlag()

                if (existing != null) {
                    // Reclaiming a handle this account gave up earlier.
                    SupabaseClient.client.postgrest["user_usernames"].update({
                        set("is_active", true)
                        set("is_primary", shouldBePrimary)
                    }) {
                        filter { eq("id", existing.id) }
                    }
                } else {
                    SupabaseClient.client.postgrest["user_usernames"].insert(
                        UsernameInsert(
                            user_id = myId,
                            username = normalized,
                            is_primary = shouldBePrimary
                        )
                    )
                }
                Result.success(Unit)
            } catch (e: Exception) {
                ErrorReporter.report(TAG, e)
                Result.failure(e)
            }
        }

    /** Moves the profile's displayed handle to [usernameId]. */
    suspend fun setPrimary(usernameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            clearPrimaryFlag()
            SupabaseClient.client.postgrest["user_usernames"].update({
                set("is_primary", true)
            }) {
                filter { eq("id", usernameId) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            ErrorReporter.report(TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Gives up [usernameId]. The claim survives; only the routing stops.
     *
     * The primary cannot be given up — an account with no primary has nothing
     * to show on its profile and nothing for `profiles.username` to mirror.
     */
    suspend fun removeUsername(usernameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val mine = myUsernames()
            val target = mine.firstOrNull { it.id == usernameId }
                ?: return@withContext Result.failure(IllegalArgumentException("Not yours"))
            if (target.is_primary) {
                return@withContext Result.failure(
                    IllegalStateException("Make another username primary first")
                )
            }
            SupabaseClient.client.postgrest["user_usernames"].update({
                set("is_active", false)
            }) {
                filter { eq("id", usernameId) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            ErrorReporter.report(TAG, e)
            Result.failure(e)
        }
    }

    private suspend fun clearPrimaryFlag() {
        SupabaseClient.client.postgrest["user_usernames"].update({
            set("is_primary", false)
        }) {
            filter {
                eq("user_id", myId)
                eq("is_primary", true)
            }
        }
    }

    companion object {
        private const val TAG = "UsernameRepository"

        /** Live handles allowed per account, mirroring the trigger in migration 10. */
        const val MAX_ACTIVE = 3
    }
}
