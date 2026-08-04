package com.neveroff.data.repository

import com.neveroff.crypto.CryptoManager
import com.neveroff.data.cache.ProfileCache
import com.neveroff.data.local.AppDatabase
import com.neveroff.ui.screens.FORWARDED_CHANNEL_MARKER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * Sends a channel post into one or more chats as an ordinary message.
 *
 * A forwarded post is a normal end-to-end encrypted message — it is encrypted
 * separately for each recipient, exactly like anything else typed into that
 * chat. It does not inherit the channel's plaintext status: channel posts are
 * public by design, but a post someone chose to send you privately is a private
 * message, and treating it otherwise would leak who forwards what to whom.
 *
 * The source channel travels inside the encrypted body, appended after
 * [FORWARDED_CHANNEL_MARKER], for the same reason. A `from_channel` column
 * would hand the server a plaintext record of which channels each user reads
 * and passes on, which is precisely the metadata this app exists to withhold.
 */
object PostForwarder {

    /**
     * @return the number of chats the post actually reached.
     *
     * Counts successes rather than returning Unit or a bare Boolean: forwarding
     * to five people and reaching three is neither success nor failure, and the
     * caller needs to be able to say which happened.
     */
    suspend fun forward(
        db: AppDatabase,
        myId: String,
        postText: String,
        channelUsername: String,
        targetChatIds: List<String>
    ): Int = withContext(Dispatchers.IO) {
        if (myId.isBlank() || targetChatIds.isEmpty()) return@withContext 0

        val body = "[FWD]$postText$FORWARDED_CHANNEL_MARKER$channelUsername"
        var delivered = 0

        for (chatId in targetChatIds) {
            // Each chat has its own shared secret, so the ciphertext differs per
            // recipient even though the plaintext is identical.
            val authKey = try {
                if (chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID) {
                    ProfileCache.getProfile(myId)?.public_key
                        ?.let { CryptoManager.deriveSharedSecret(it) }
                } else {
                    db.chatDao().observeChatById(chatId).firstOrNull()?.otherPubKey
                        ?.takeIf { it.isNotBlank() }
                        ?.let { CryptoManager.deriveSharedSecret(it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } ?: continue

            try {
                ChatRepository(db, myId, authKey).sendMessage(chatId, body)
                delivered += 1
            } catch (e: Exception) {
                // One unreachable chat must not abandon the rest of the list.
                e.printStackTrace()
            }
        }

        delivered
    }
}
