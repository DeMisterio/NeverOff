package com.neveroff.data.remote

import com.neveroff.crypto.KeyBackup
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Stores and retrieves the passphrase-sealed identity key.
 *
 * Everything crossing this boundary is already encrypted under a key derived
 * from a passphrase the server never receives. The row is protected by RLS to
 * its owner as well, but that is defence in depth rather than the thing keeping
 * it private — the blob is unopenable regardless of who holds it.
 */
object KeyBackupRepository {

    @Serializable
    data class Row(
        val user_id: String,
        val public_key: String,
        val wrapped_private_key: String,
        val kdf_salt: String,
        val kdf_iterations: Int,
        val kdf_algorithm: String,
        val updated_at: String? = null
    )

    /**
     * The columns this client knows about, listed explicitly.
     *
     * Not Columns.ALL. The table also has created_at, which Row does not
     * declare, and a strict decoder rejects an unexpected key by throwing —
     * which fetch() then swallowed into null. The visible effect was the
     * "don't lose your chats" banner nagging people who had already set a
     * passphrase, because a decode failure is indistinguishable from "no row"
     * once it has been turned into null.
     *
     * Naming the columns also means a column added to the table later cannot
     * silently break every client that predates it.
     */
    private const val COLUMNS =
        "user_id,public_key,wrapped_private_key,kdf_salt,kdf_iterations,kdf_algorithm,updated_at"

    /** The backup for this account, or null if they have never made one. */
    suspend fun fetch(userId: String): Row? = withContext(Dispatchers.IO) {
        try {
            SupabaseClient.client.postgrest["key_backups"]
                .select(Columns.raw(COLUMNS)) {
                    filter { eq("user_id", userId) }
                    limit(1)
                }
                .decodeSingleOrNull<Row>()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * True when a backup exists, false when it definitely does not, null when
     * the question could not be answered.
     *
     * The third case matters. Callers that prompt the user to make a backup
     * must not act on a failed lookup: telling someone who is offline that
     * they have no backup is both wrong and, since the prompt is about losing
     * their history, alarming.
     */
    suspend fun exists(userId: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val rows = SupabaseClient.client.postgrest["key_backups"]
                .select(Columns.raw("user_id")) {
                    filter { eq("user_id", userId) }
                    limit(1)
                }
                .decodeList<Map<String, String>>()
            rows.isNotEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Create or replace this account's backup.
     *
     * Upsert rather than insert: setting a new passphrase has to replace the
     * old blob, and a second row for the same user would be ambiguous about
     * which passphrase actually opens the account.
     *
     * Throws rather than swallowing. A silently failed backup is worse than no
     * backup, because the user walks away believing they are covered.
     */
    suspend fun save(userId: String, blob: KeyBackup.Blob) = withContext(Dispatchers.IO) {
        SupabaseClient.client.postgrest["key_backups"].upsert(
            Row(
                user_id = userId,
                public_key = blob.publicKeyBase64,
                wrapped_private_key = blob.wrappedPrivateKeyBase64,
                kdf_salt = blob.saltBase64,
                kdf_iterations = blob.iterations,
                kdf_algorithm = blob.algorithm
            )
        )
        Unit
    }

    /** Withdraw the backup. Leaves the key on this device untouched. */
    suspend fun delete(userId: String) = withContext(Dispatchers.IO) {
        SupabaseClient.client.postgrest["key_backups"].delete {
            filter { eq("user_id", userId) }
        }
        Unit
    }

    fun Row.toBlob(): KeyBackup.Blob = KeyBackup.Blob(
        publicKeyBase64 = public_key,
        wrappedPrivateKeyBase64 = wrapped_private_key,
        saltBase64 = kdf_salt,
        iterations = kdf_iterations,
        algorithm = kdf_algorithm
    )
}
