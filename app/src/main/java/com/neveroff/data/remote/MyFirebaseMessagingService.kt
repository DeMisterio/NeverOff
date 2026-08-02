package com.neveroff.data.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.neveroff.MainActivity
import com.neveroff.crypto.CryptoManager
import com.neveroff.data.local.AppDatabase
import com.neveroff.data.local.ChatEntity
import com.neveroff.data.local.MessageEntity
import com.neveroff.ui.screens.Profile
import com.neveroff.ui.screens.parseSupabaseTimestamp
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        try {
            CryptoManager.init(applicationContext)
            com.neveroff.util.SettingsManager.init(applicationContext)
            com.neveroff.util.LoggerManager.init(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        try {
            CryptoManager.init(applicationContext)
            com.neveroff.util.SettingsManager.init(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Whenever a new token is generated, upload it to Supabase
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val authId = SupabaseClient.client.auth.currentUserOrNull()?.id
                val prefs = applicationContext.getSharedPreferences("neveroff_prefs", android.content.Context.MODE_PRIVATE)
                val myId = if (!authId.isNullOrEmpty()) authId else com.neveroff.util.SecurePrefs.getString(prefs, "my_user_id")
                if (!myId.isNullOrEmpty()) {
                    SupabaseClient.client.postgrest["profiles"]
                        .update({ set("fcm_token", token) }) {
                            filter { eq("id", myId) }
                        }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        try {
            CryptoManager.init(applicationContext)
            com.neveroff.util.SettingsManager.init(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val data = message.data
        if (data.isEmpty()) return

        // Channel posts are a different shape entirely: broadcast, plaintext,
        // and addressed to a channel rather than a chat.
        if (data["type"] == "channel_post") {
            handleChannelPost(data)
            return
        }

        val msgId = data["id"] ?: ""
        val chatId = data["chat_id"] ?: return
        val senderId = data["sender_id"] ?: return
        val senderName = data["sender_name"] ?: "NeverOff User"
        val ciphertext = data["ciphertext"] ?: return
        val msgKey = data["msg_key"] ?: return
        val createdAtStr = data["created_at"]

        val db = AppDatabase.getDatabase(applicationContext)
        
        // Ensure CryptoManager is initialized when service starts in background
        try {
            CryptoManager.init(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Tracks whether we've already shown a notification (or determined none is
            // needed, e.g. it's our own message echoed back) — see the `finally` block,
            // which makes sure a resolution/decrypt failure still surfaces *something*
            // to the user instead of silently dropping the push.
            var handled = false
            try {
                // 1. Get or create ChatEntity to get the other user's public key
                var chat = db.chatDao().getChatById(chatId)

                // Only handle if message is from the other user (prevent self-notifications)
                if (chat != null && chat.otherUserId != senderId) {
                    handled = true
                    return@launch
                }
                if (chat == null) {
                    // Fetch sender profile to get public key
                    val senderProfile = SupabaseClient.client.postgrest["profiles"]
                        .select { filter { eq("id", senderId) } }
                        .decodeSingleOrNull<Profile>()
                    if (senderProfile != null) {
                        chat = ChatEntity(
                            id = chatId,
                            otherUserId = senderProfile.id,
                            otherUserName = senderProfile.display_name,
                            otherPubKey = senderProfile.public_key,
                            lastMessage = "",
                            updatedAt = System.currentTimeMillis(),
                            unreadCount = 0
                        )
                        db.chatDao().insertChat(chat)
                    }
                }

                if (chat != null) {
                    // 2. Derive authKey and decrypt message
                    val authKey = CryptoManager.deriveSharedSecret(chat.otherPubKey)
                    val decrypted = try {
                        CryptoManager.decryptMessage(ciphertext, msgKey, authKey)
                    } catch (e: Exception) {
                        "[Encrypted message]"
                    }

                    val displayMessage = if (decrypted.startsWith("[VIEW_ONCE]:")) "🔥 Disappearing message" else decrypted

                    // 3. Insert message locally as unread (status = 1)
                    val entity = MessageEntity(
                        id = if (msgId.isNotEmpty()) msgId else UUID.randomUUID().toString(),
                        chatId = chatId,
                        senderId = senderId,
                        content = decrypted,
                        createdAt = if (!createdAtStr.isNullOrEmpty()) parseSupabaseTimestamp(createdAtStr) else System.currentTimeMillis(),
                        status = 1 // delivered/unread
                    )
                    db.messageDao().insertMessage(entity)

                    // 4. Update chat unread count and last message
                    db.chatDao().updateLastMessageAndIncrementUnread(chatId, displayMessage, entity.createdAt)

                    // 5. Show Android System Notification
                    showSystemNotification(senderName, displayMessage, chatId)
                    handled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (!handled) {
                    // Chat/profile resolution failed (e.g. network error on a brand-new chat) —
                    // we can't decrypt without the sender's public key, but the user should still
                    // be told a message arrived rather than losing it silently.
                    showSystemNotification(senderName, "New message", chatId)
                }
            }
        }
    }

    /**
     * Stores a pushed channel post locally and raises a notification. No
     * decryption step: channel posts are plaintext by design, so the content
     * arrives ready to display.
     */
    private fun handleChannelPost(data: Map<String, String>) {
        val postId = data["id"].orEmpty().ifEmpty { UUID.randomUUID().toString() }
        val channelId = data["channel_id"] ?: return
        val authorId = data["author_id"] ?: return
        val channelName = data["channel_name"] ?: "Channel"
        val content = data["content"].orEmpty()
        val previewText = data["preview"].orEmpty().ifEmpty { content }
        val createdAt = data["created_at"]

        val db = AppDatabase.getDatabase(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val timestamp = if (!createdAt.isNullOrEmpty()) {
                    parseSupabaseTimestamp(createdAt)
                } else {
                    System.currentTimeMillis()
                }

                // Only store the post if this device already knows the channel;
                // otherwise the row would have no channel to belong to and the
                // list would show an orphan. The notification still fires.
                val channel = db.channelDao().getChannel(channelId)
                if (channel != null) {
                    db.channelPostDao().upsertPost(
                        com.neveroff.data.local.ChannelPostEntity(
                            id = postId,
                            channelId = channelId,
                            authorId = authorId,
                            content = content,
                            createdAt = timestamp
                        )
                    )
                    db.channelDao().updateLastPost(channelId, content, timestamp)

                    val prefs = applicationContext.getSharedPreferences(
                        "neveroff_prefs", android.content.Context.MODE_PRIVATE
                    )
                    val myId = com.neveroff.util.CurrentUser.id(prefs)
                    val unread = db.channelPostDao().countUnread(channelId, channel.lastReadAt, myId)
                    db.channelDao().setUnreadCount(channelId, unread)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                showSystemNotification(channelName, previewText, channelId, isChannel = true)
            }
        }
    }

    private fun showSystemNotification(
        title: String,
        message: String,
        chatId: String,
        isChannel: Boolean = false
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "neveroff_messages"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых сообщениях"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Click intent to open MainActivity
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(if (isChannel) "channel_id" else "chat_id", chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            chatId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(chatId.hashCode(), notification)
    }
}
