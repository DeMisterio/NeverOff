package com.neveroff.data.remote

import android.content.Context
import com.neveroff.crypto.CryptoManager
import com.neveroff.data.cache.ProfileCache
import com.neveroff.data.local.AppDatabase
import com.neveroff.data.local.ChatEntity
import com.neveroff.data.local.MessageEntity
import com.neveroff.ui.screens.Message
import com.neveroff.util.DebugLogger
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

object GlobalRealtimeManager {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var globalChannel: RealtimeChannel? = null
    private var subscriptionJob: Job? = null
    
    // To avoid showing notifications when the user is already inside a specific chat
    var currentActiveChatId: String? = null

    /**
     * Whether the app is on screen right now.
     *
     * Read receipts depend on this alongside [currentActiveChatId]: a chat left
     * open behind a locked screen or under another app is not being read, and
     * marking its messages "read" tells the sender something untrue. Updated
     * from MainActivity's lifecycle observer.
     */
    @Volatile
    var isAppInForeground: Boolean = false

    fun start(context: Context) {
        if (globalChannel != null) return
        
        scope.launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val myId = SupabaseClient.client.auth.currentUserOrNull()?.id ?: return@launch
                
                // Wait for Realtime to be connected
                val connected = withTimeoutOrNull(15_000L) {
                    SupabaseClient.client.realtime.status.first { it == Realtime.Status.CONNECTED }
                }
                if (connected == null) {
                    DebugLogger.log("Realtime not connected after 15s, skipping global notifications")
                    return@launch
                }
                
                val channel = SupabaseClient.client.channel("global-notifications")
                globalChannel = channel
                val changes = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "messages"
                }
                subscriptionJob = changes.onEach { action ->
                    val msg = action.decodeRecord<Message>()
                    // If message is for us, and we are not currently in that chat
                    if (msg.sender_id != myId && msg.chat_id != currentActiveChatId) {
                        try {
                            var chat = db.chatDao().getChatById(msg.chat_id)
                            var remotePubKey: String? = chat?.otherPubKey
                            var senderName = chat?.otherUserName ?: "someone"

                            if (chat == null) {
                                val senderProfile = ProfileCache.getProfile(msg.sender_id)
                                if (senderProfile != null) {
                                    remotePubKey = senderProfile.public_key
                                    senderName = senderProfile.display_name
                                }
                            }

                            if (remotePubKey != null) {
                                val authKey = CryptoManager.deriveSharedSecret(remotePubKey)
                                val decrypted = try {
                                    CryptoManager.decryptMessage(msg.ciphertext, msg.msg_key, authKey)
                                } catch (e: Exception) {
                                    "[Encrypted message]"
                                }

                                if (chat == null) {
                                    val senderProfile = ProfileCache.getProfile(msg.sender_id)
                                    if (senderProfile != null) {
                                        chat = ChatEntity(
                                            id = msg.chat_id,
                                            otherUserId = senderProfile.id,
                                            otherUserName = senderProfile.display_name,
                                            otherPubKey = senderProfile.public_key,
                                            lastMessage = decrypted,
                                            updatedAt = System.currentTimeMillis(),
                                            unreadCount = 1
                                        )
                                        db.chatDao().insertChat(chat)
                                    }
                                } else {
                                    db.chatDao().updateLastMessageAndIncrementUnread(msg.chat_id, decrypted, System.currentTimeMillis())
                                }

                                val entity = MessageEntity(
                                    id = msg.id ?: UUID.randomUUID().toString(),
                                    chatId = msg.chat_id,
                                    senderId = msg.sender_id,
                                    content = decrypted,
                                    createdAt = System.currentTimeMillis(),
                                    status = 1
                                )
                                db.messageDao().insertMessage(entity)

                                // No Toast here: the FCM data-message path (MyFirebaseMessagingService)
                                // fires a system notification for this exact same condition regardless of
                                // foreground/background state, so a Toast would just be a duplicate alert.
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }.launchIn(this)
                channel.subscribe()
            } catch (e: Exception) {
                DebugLogger.log("Global notifications error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        scope.launch {
            try { globalChannel?.unsubscribe() } catch (e: Exception) { e.printStackTrace() }
            globalChannel = null
        }
    }
}
