package com.neveroff.data.repository

import android.util.Log
import com.neveroff.crypto.CryptoManager
import com.neveroff.data.local.AppDatabase
import com.neveroff.data.local.MessageEntity
import com.neveroff.data.remote.SupabaseClient
import com.neveroff.ui.screens.Message
import com.neveroff.ui.screens.parseSupabaseTimestamp
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

import io.github.jan.supabase.gotrue.auth
import com.neveroff.BuildConfig
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class ChatRepository(
    val db: AppDatabase,
    /** Resolved once by the caller via CurrentUser.id(), so it stays correct with no network. */
    val myId: String,
    private val authKey: ByteArray
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var realtimeChannel: RealtimeChannel? = null
    private var subscriptionJob: Job? = null

    /** Observe messages from local database (Single Source of Truth) */
    fun observeMessages(chatId: String): Flow<List<MessageEntity>> {
        return db.messageDao().getMessagesForChat(chatId)
    }

    /** Observe chat details for customization */
    fun observeChat(chatId: String): Flow<com.neveroff.data.local.ChatEntity?> {
        return db.chatDao().observeChatById(chatId)
    }

    /** Update chat customization */
    suspend fun updateCustomization(chatId: String, wallpaperUri: String?, myBubbleColor: Long?, otherBubbleColor: Long?) {
        val rows = db.chatDao().updateChatCustomization(chatId, wallpaperUri, myBubbleColor, otherBubbleColor)
        if (rows == 0) {
            val newChat = com.neveroff.data.local.ChatEntity(
                id = chatId,
                otherUserId = if (chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID) myId else "",
                otherUserName = "Chat",
                otherPubKey = "",
                lastMessage = null,
                updatedAt = System.currentTimeMillis(),
                unreadCount = 0,
                wallpaperUri = wallpaperUri,
                myBubbleColor = myBubbleColor,
                otherBubbleColor = otherBubbleColor,
                customName = if (chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID) "Favorites" else null
            )
            db.chatDao().insertChat(newChat)
        }
    }

    /** Load missing history from server and save to local DB (paginated) */
    suspend fun syncHistory(chatId: String, limit: Int = 50, beforeTimestamp: Long? = null): Boolean {
        if (chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID) return true
        return try {
            val remoteMessages = SupabaseClient.client.postgrest["messages"]
                .select {
                    filter {
                        eq("chat_id", chatId)
                        if (beforeTimestamp != null) {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                            lt("created_at", sdf.format(java.util.Date(beforeTimestamp)))
                        }
                    }
                    order("created_at", Order.DESCENDING)
                    limit(limit.toLong())
                }
                .decodeList<Message>()

            val entities = remoteMessages.map { msg ->
                val decrypted = try {
                    CryptoManager.decryptMessage(msg.ciphertext, msg.msg_key, authKey)
                } catch (e: Exception) {
                    "[Encrypted message]"
                }

                val statusInt = when (msg.status) {
                    "sent" -> 0
                    "delivered" -> 1
                    "read" -> 2
                    else -> 0
                }

                MessageEntity(
                    id = msg.id ?: UUID.randomUUID().toString(),
                    chatId = msg.chat_id,
                    senderId = msg.sender_id,
                    content = decrypted,
                    createdAt = parseSupabaseTimestamp(msg.created_at),
                    status = statusInt
                )
            }

            // Batch insert into local DB
            if (entities.isNotEmpty()) {
                db.messageDao().insertMessages(entities)
                // Rows already present are IGNOREd above, so anything cached
                // with the old broken timestamp would stay broken forever.
                // Only writes when the value actually differs.
                entities.forEach { db.messageDao().repairTimestamp(it.id, it.createdAt) }
            }

            // Update last message in chat list (use the most recent message)
            entities.maxByOrNull { it.createdAt }?.let { last ->
                db.chatDao().updateLastMessage(
                    chatId = chatId,
                    lastMessage = last.content,
                    updatedAt = last.createdAt
                )
            }

            // Mark unread as read remotely and locally
            val unreadRemoteIds = remoteMessages
                .filter { it.sender_id != myId && it.status != "read" && it.id != null }
                .map { it.id!! }
            
            if (unreadRemoteIds.isNotEmpty()) {
                db.messageDao().markMessagesAsRead(unreadRemoteIds)
                db.chatDao().resetUnreadCount(chatId)
                scope.launch {
                    try {
                        SupabaseClient.client.postgrest["messages"]
                            .update({ set("status", "read") }) {
                                filter { isIn("id", unreadRemoteIds) }
                            }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ChatRepository", "Sync failed: ${e.message}")
            false
        }
    }

    /** Load older messages for infinite scroll */
    suspend fun loadMoreMessages(chatId: String, beforeTimestamp: Long, limit: Int = 50) {
        syncHistory(chatId, limit, beforeTimestamp)
    }

    suspend fun updateCustomName(chatId: String, customName: String?) {
        db.chatDao().updateChatCustomName(chatId, customName)
    }

    /** Connect to Realtime for this chat */
    fun subscribeToRealtime(chatId: String) {
        if (chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID) return

        if (realtimeChannel != null) {
            com.neveroff.util.DebugLogger.log("Subscribe skipped: channel already exists")
            return
        }

        // Reserve the slot synchronously so a second subscribeToRealtime call (e.g. a fast
        // onStop/onStart cycle) doesn't race past the guard above while this coroutine is
        // still waiting on the connection below.
        val pendingChannel = SupabaseClient.client.realtime.channel("chat_${chatId}")
        realtimeChannel = pendingChannel

        scope.launch {
            try {
                // Unlike GlobalRealtimeManager/PresenceManager, this used to subscribe the
                // channel immediately regardless of whether the underlying socket was actually
                // connected. If the socket was still connecting (e.g. right after app launch)
                // or had dropped while backgrounded, the subscribe attempt would silently fail
                // and this chat would never receive live messages again until the screen (and
                // this channel) was torn down and recreated — i.e. exactly "reopen the app to
                // see new messages". Ensure the socket is actually up first, kicking it if not.
                val alreadyConnected = SupabaseClient.client.realtime.status.value == Realtime.Status.CONNECTED
                if (!alreadyConnected) {
                    com.neveroff.util.DebugLogger.log("Realtime not connected yet, reconnecting before subscribing to chat_${chatId}")
                    try { SupabaseClient.client.realtime.connect() } catch (e: Exception) { e.printStackTrace() }
                    val connected = withTimeoutOrNull(15_000L) {
                        SupabaseClient.client.realtime.status.first { it == Realtime.Status.CONNECTED }
                    }
                    if (connected == null) {
                        com.neveroff.util.DebugLogger.log("Realtime still not connected after 15s, giving up on chat_${chatId}")
                        return@launch
                    }
                }

                com.neveroff.util.DebugLogger.log("Subscribing to chat_${chatId}")
                val changeFlow = pendingChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "messages"
                    filter = "chat_id=eq.$chatId"
                }

                subscriptionJob = changeFlow.onEach { action ->
                    val typeName = action::class.simpleName ?: "Unknown"
                    com.neveroff.util.DebugLogger.log("Realtime event received: $typeName")
                    try {
                        when (action) {
                            is PostgresAction.Insert -> {
                                val msg = action.decodeRecord<Message>()
                                com.neveroff.util.DebugLogger.log("Insert msg decoded: id=${msg.id}, sender=${msg.sender_id}")
                                if (msg.chat_id == chatId && msg.sender_id != myId) {
                                    handleIncomingMessage(msg)
                                } else {
                                    com.neveroff.util.DebugLogger.log("Filtered out insert: same sender or wrong chat")
                                }
                            }
                            is PostgresAction.Update -> {
                                val msg = action.decodeRecord<Message>()
                                com.neveroff.util.DebugLogger.log("Update msg decoded: id=${msg.id}, status=${msg.status}")
                                if (msg.chat_id == chatId && msg.id != null) {
                                    val statusInt = when (msg.status) {
                                        "sent" -> 0
                                        "delivered" -> 1
                                        "read" -> 2
                                        else -> 0
                                    }
                                    db.messageDao().updateMessageStatus(msg.id, statusInt)

                                    // Decrypt and update content (for edits)
                                    try {
                                        val decrypted = CryptoManager.decryptMessage(msg.ciphertext, msg.msg_key, authKey)
                                        db.messageDao().updateMessageContent(msg.id, decrypted)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                            is PostgresAction.Delete -> {
                                val msgId = action.oldRecord["id"]?.toString()?.removeSurrounding("\"")
                                if (msgId != null) {
                                    com.neveroff.util.DebugLogger.log("Delete msg event: id=$msgId")
                                    db.messageDao().deleteMessage(msgId)
                                }
                            }
                            else -> {
                                com.neveroff.util.DebugLogger.log("Other PostgresAction: $action")
                            }
                        }
                    } catch (e: Exception) {
                        com.neveroff.util.DebugLogger.log("Error processing realtime action: ${e.message}")
                        Log.e("ChatRepository", "Error processing realtime action: ${e.message}")
                        e.printStackTrace()
                    }
                }.launchIn(scope)

                com.neveroff.util.DebugLogger.log("Calling channel.subscribe()...")
                pendingChannel.subscribe(blockUntilSubscribed = true)
                com.neveroff.util.DebugLogger.log("Channel subscribe() completed successfully!")
            } catch (e: Exception) {
                com.neveroff.util.DebugLogger.log("Channel subscribe() failed: ${e.message}")
                Log.e("ChatRepository", "Subscribe failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private suspend fun handleIncomingMessage(msg: Message) {
        val decrypted = try {
            CryptoManager.decryptMessage(msg.ciphertext, msg.msg_key, authKey)
        } catch (e: Exception) {
            "[Encrypted message]"
        }

        // A message is "read" only if the recipient is actually looking at this
        // conversation right now.
        //
        // This used to assume that an active realtime channel meant the chat
        // screen was open. It does not: GlobalRealtimeManager holds a
        // subscription for the whole app from sign-in onwards, so every
        // incoming message was marked read on arrival — the sender saw two blue
        // ticks for messages the recipient had never opened, and often was not
        // even in the app for. Read receipts that lie are worse than none.
        //
        // currentActiveChatId is set when a chat is opened and cleared when it
        // is left, and the app must also be in the foreground: a chat left open
        // behind a locked screen is not being read either.
        val isViewingThisChat =
            com.neveroff.data.remote.GlobalRealtimeManager.currentActiveChatId == msg.chat_id &&
                com.neveroff.data.remote.GlobalRealtimeManager.isAppInForeground

        val statusInt = if (isViewingThisChat) 2 else 1 // 2 = read, 1 = delivered

        if (msg.id != null) {
            db.messageDao().insertMessage(
                MessageEntity(
                    id = msg.id,
                    chatId = msg.chat_id,
                    senderId = msg.sender_id,
                    content = decrypted,
                    createdAt = parseSupabaseTimestamp(msg.created_at),
                    status = statusInt
                )
            )
            db.chatDao().updateLastMessage(
                chatId = msg.chat_id,
                lastMessage = decrypted,
                updatedAt = System.currentTimeMillis()
            )

            // Tell the sender what actually happened: delivered on arrival, read
            // only when it was genuinely read. If the recipient opens the chat
            // later, markChatAsRead promotes it.
            try {
                SupabaseClient.client.postgrest["messages"]
                    .update({ set("status", if (isViewingThisChat) "read" else "delivered") }) {
                        filter { eq("id", msg.id) }
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun markChatAsRead(chatId: String) {
        try {
            val unread = db.messageDao().getUnreadMessagesForChat(chatId, myId)
            val unreadIds = unread.map { it.id }
            db.messageDao().markAllMessagesInChatAsRead(chatId, myId)
            db.chatDao().resetUnreadCount(chatId)
            
            if (unreadIds.isNotEmpty()) {
                SupabaseClient.client.postgrest["messages"]
                    .update({ set("status", "read") }) {
                        filter { isIn("id", unreadIds) }
                    }
            } else {
                SupabaseClient.client.postgrest["messages"]
                    .update({ set("status", "read") }) {
                        filter {
                            eq("chat_id", chatId)
                            neq("sender_id", myId)
                            neq("status", "read")
                        }
                    }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Deletes a message for everyone. Remote delete is attempted first; the local
     * row is only removed if the remote delete actually succeeds, so a rejected
     * remote delete (e.g. missing/denied RLS policy) can't leave the message gone
     * locally while still visible to the other participant.
     */
    suspend fun deleteMessageForEveryone(msgId: String): Boolean {
        return try {
            SupabaseClient.client.postgrest["messages"].delete {
                filter { eq("id", msgId) }
            }
            db.messageDao().deleteMessage(msgId)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun unsubscribeRealtime() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        
        realtimeChannel?.let { channel ->
            scope.launch {
                try { channel.unsubscribe() } catch (e: Exception) { e.printStackTrace() }
            }
        }
        realtimeChannel = null
    }


    
    /** Send media message: Encrypt file, upload, then send normal text message with metadata */
    suspend fun sendMediaMessage(chatId: String, prefix: String, file: java.io.File, onProgress: ((String, Float) -> Unit)? = null): Pair<Boolean, String?> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var newMessageId: String? = null
            try {
                // 1. Encrypt file locally
                val encryptedFile = java.io.File(file.parent, "${file.name}.enc")
                val fileKeyBase64 = com.neveroff.crypto.CryptoManager.encryptFile(file, encryptedFile)
                
                // 2. Prepare message text and insert to Room optimistically
                val remoteName = "${java.util.UUID.randomUUID()}_${file.name}.enc"
                
                // Cache the file locally so MediaMessagePlayer can find it instantly
                val ext = when {
                    prefix == "VOICE" -> "m4a"
                    prefix == "VIDEO" || prefix == "FILE_VIDEO" -> "mp4"
                    prefix == "IMAGE" -> "jpg"
                    // "FILE:report.pdf" — keep the real extension so the cached
                    // copy can be opened by whatever handles that type. Writing
                    // everything as ".file" left the receiver with a blob no
                    // app would claim.
                    prefix.startsWith("FILE:") ->
                        com.neveroff.util.FileKind.extensionOf(prefix.removePrefix("FILE:"))
                            .ifEmpty { "file" }
                    else -> "file"
                }
                val cachedFile = java.io.File(file.parent, "dec_${remoteName}.$ext")
                file.copyTo(cachedFile, overwrite = true)
                
                val text = "[$prefix] $remoteName $fileKeyBase64"
                newMessageId = java.util.UUID.randomUUID().toString()
                
                val entity = com.neveroff.data.local.MessageEntity(
                    id = newMessageId,
                    chatId = chatId,
                    senderId = myId,
                    content = text,
                    createdAt = System.currentTimeMillis(),
                    status = 0 // pending
                )
                db.messageDao().insertMessage(entity)
                db.chatDao().updateLastMessage(chatId, text, System.currentTimeMillis())

                if (chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID) {
                    return@withContext Pair(true, newMessageId) // Local only!
                }

                // 3. Upload to Supabase using Ktor for progress tracking
                val url = "${BuildConfig.SUPABASE_URL}/storage/v1/object/chat_media/$remoteName"
                
                val urlObj = java.net.URL(url)
                val connection = urlObj.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer ${com.neveroff.data.remote.SupabaseClient.client.auth.currentAccessTokenOrNull() ?: ""}")
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                
                val fileLength = encryptedFile.length()
                connection.setFixedLengthStreamingMode(fileLength)
                connection.connect()
                
                encryptedFile.inputStream().use { input ->
                    connection.outputStream.use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesCopied = 0L
                        var bytes = input.read(buffer)
                        while (bytes >= 0) {
                            output.write(buffer, 0, bytes)
                            bytesCopied += bytes
                            if (fileLength > 0) {
                                onProgress?.invoke(newMessageId, bytesCopied.toFloat() / fileLength.toFloat())
                            }
                            bytes = input.read(buffer)
                        }
                    }
                }
                
                val responseCode = connection.responseCode
                connection.disconnect()
                if (responseCode !in 200..299) {
                    throw Exception("Upload failed with code $responseCode")
                }
                
                // Cleanup temp files
                encryptedFile.delete()
                file.delete()
                
                // 4. Send message metadata to Supabase
                // Extract logic from sendMessage
                val (ciphertext, msgKey) = com.neveroff.crypto.CryptoManager.encryptMessage(text, authKey)
                val message = com.neveroff.ui.screens.Message(
                    id = newMessageId,
                    chat_id = chatId,
                    sender_id = myId,
                    ciphertext = ciphertext,
                    msg_key = msgKey,
                    status = "sent"
                )
                com.neveroff.data.remote.SupabaseClient.client.postgrest["messages"].upsert(message)
                
                // CALL PUSH NOTIFY
                triggerPushNotification(com.neveroff.ui.screens.Message(
                    id = newMessageId,
                    chat_id = chatId,
                    sender_id = myId,
                    ciphertext = ciphertext,
                    msg_key = msgKey,
                    status = "sent"
                ))
                
                Pair(true, newMessageId)
            } catch (e: Exception) {
                e.printStackTrace()
                // Cleanup temp files if they exist
                java.io.File(file.parent, "${file.name}.enc").delete()
                file.delete()
                // Mark the optimistic local row as failed instead of leaving it ambiguously "pending".
                newMessageId?.let { db.messageDao().updateMessageStatus(it, -1) }
                Pair(false, newMessageId)
            }
        }
    }

    /** Send message: First save pending to DB, then upload */
    suspend fun sendMessage(chatId: String, text: String): Boolean {
        // Encrypt
        val (ciphertext, msgKey) = try {
            CryptoManager.encryptMessage(text, authKey)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        val newMessageId = UUID.randomUUID().toString()
        val displayMessage = if (text.startsWith("[VIEW_ONCE]:")) "🔥 Disappearing message" else text

        // 1. Optimistic insert to Room (offline-first)
        val entity = MessageEntity(
            id = newMessageId,
            chatId = chatId,
            senderId = myId,
            content = text,
            createdAt = System.currentTimeMillis(),
            status = 0 // pending/sent
        )
        db.messageDao().insertMessage(entity)
        db.chatDao().updateLastMessage(
            chatId = chatId,
            lastMessage = displayMessage,
            updatedAt = System.currentTimeMillis()
        )

        if (chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID) {
            return true // Local only!
        }

        // 2. Upload to Supabase (idempotent upsert + short retry; marks failed on final failure)
        return sendMessageToRemoteWithRetry(newMessageId, chatId, ciphertext, msgKey)
    }

    /**
     * Upserts the message row to Supabase — idempotent, so a retry that reuses the same
     * [messageId] can never create a duplicate row. Retries once on failure; on final
     * failure marks the local row as failed (-1) instead of leaving it ambiguously "pending".
     */
    private suspend fun sendMessageToRemoteWithRetry(messageId: String, chatId: String, ciphertext: String, msgKey: String): Boolean {
        val message = Message(
            id = messageId,
            chat_id = chatId,
            sender_id = myId,
            ciphertext = ciphertext,
            msg_key = msgKey,
            status = "sent"
        )
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                SupabaseClient.client.postgrest["messages"].upsert(message)
                triggerPushNotification(message)
                return true
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) delay(1000)
            }
        }
        lastError?.printStackTrace()
        db.messageDao().updateMessageStatus(messageId, -1)
        return false
    }

    /**
     * Re-attempts sending a text message that previously failed (status == -1), reusing
     * its existing id so the idempotent remote upsert can't create a duplicate.
     */
    suspend fun retrySendMessage(messageId: String): Boolean {
        val existing = db.messageDao().getMessageById(messageId) ?: return false
        val (ciphertext, msgKey) = try {
            CryptoManager.encryptMessage(existing.content, authKey)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        if (existing.chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID) {
            db.messageDao().updateMessageStatus(messageId, 0)
            return true
        }
        val success = sendMessageToRemoteWithRetry(messageId, existing.chatId, ciphertext, msgKey)
        if (success) {
            db.messageDao().updateMessageStatus(messageId, 0)
        }
        return success
    }

    suspend fun editMessage(msgId: String, newText: String): Boolean {
        // Only the original sender may edit a message's content.
        val existing = db.messageDao().getMessageById(msgId)
        if (existing == null || existing.senderId != myId) {
            return false
        }

        // Encrypt new text
        val (ciphertext, msgKey) = try {
            CryptoManager.encryptMessage(newText, authKey)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        // 1. Update locally immediately
        db.messageDao().updateMessageContent(msgId, newText)

        // 2. Update on Supabase
        return try {
            SupabaseClient.client.postgrest["messages"].update(
                {
                    set("ciphertext", ciphertext)
                    set("msg_key", msgKey)
                }
            ) {
                filter { eq("id", msgId) }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun deleteChat(chatId: String) {
        // Delete locally
        db.chatDao().deleteChatById(chatId)
        // Call RPC
        try {
            SupabaseClient.client.postgrest.rpc(
                "delete_chat_for_all",
                buildJsonObject { put("p_chat_id", chatId) }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    private suspend fun triggerPushNotification(message: com.neveroff.ui.screens.Message) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = "${com.neveroff.BuildConfig.SUPABASE_URL}/functions/v1/push-notify"
                val urlObj = java.net.URL(url)
                val connection = urlObj.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer ${SupabaseClient.client.auth.currentAccessTokenOrNull() ?: ""}")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                
                // Construct JSON manually or use serialization
                val jsonPayload = """
                    {
                      "record": {
                        "id": "${message.id}",
                        "chat_id": "${message.chat_id}",
                        "sender_id": "${message.sender_id}",
                        "ciphertext": "${message.ciphertext}",
                        "msg_key": "${message.msg_key}",
                        "created_at": "${message.created_at ?: ""}"
                      }
                    }
                """.trimIndent()
                
                connection.outputStream.use { os ->
                    val input = jsonPayload.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }
                
                val responseCode = connection.responseCode
                val responseMessage = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error stream"
                }
                com.neveroff.util.DebugLogger.log("Push notify response: $responseCode - $responseMessage")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    /**
     * Deletes messages for everyone. Remote delete is attempted first; local rows are
     * only removed if the remote delete succeeds, so a rejected/failed remote delete
     * (e.g. RLS denial) doesn't leave messages gone locally while still visible remotely.
     */
    suspend fun deleteMessages(ids: List<String>): Boolean {
        return try {
            SupabaseClient.client.postgrest["messages"]
                .delete {
                    filter {
                        isIn("id", ids)
                    }
                }
            db.messageDao().deleteMessages(ids)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
