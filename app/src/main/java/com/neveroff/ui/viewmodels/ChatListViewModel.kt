package com.neveroff.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neveroff.data.local.ChatDao
import com.neveroff.data.local.ChatEntity
import com.neveroff.data.local.MessageDao
import com.neveroff.data.local.MessageEntity
import com.neveroff.data.remote.SupabaseClient
import com.neveroff.ui.screens.ChatItemUiModel
import com.neveroff.ui.screens.Message
import com.neveroff.ui.screens.Profile
import com.neveroff.ui.screens.parseSupabaseTimestamp
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ChatListViewModel(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val channelRepository: com.neveroff.data.repository.ChannelRepository
) : ViewModel() {

    /** Channels this device knows about — owned or subscribed. */
    val channelList: Flow<List<com.neveroff.data.local.ChannelEntity>> = channelRepository.observeChannels()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    /**
     * True once syncing has failed repeatedly, so the UI can stop pretending it's still
     * "Connecting..." and tell the user something is actually wrong. Only raised after
     * [FAILURE_THRESHOLD] consecutive failures — a single blip on a flaky network is normal
     * and shouldn't produce a scary warning.
     */
    private val _connectionFailed = MutableStateFlow(false)
    val connectionFailed: StateFlow<Boolean> = _connectionFailed.asStateFlow()

    private var consecutiveFailures = 0

    val chatList: Flow<List<ChatItemUiModel>> = chatDao.getAllChats().map { entities ->
        val myId = SupabaseClient.client.auth.currentUserOrNull()?.id ?: "me"
        val savedEntity = entities.find { it.id == com.neveroff.util.Constants.SAVED_MESSAGES_ID }
        val savedMessages = ChatItemUiModel(
            chatId = com.neveroff.util.Constants.SAVED_MESSAGES_ID,
            otherUserId = myId,
            otherUserName = "Favorites",
            otherPubKey = "",
            lastMessage = "My Notes and Messages",
            unreadCount = 0,
            isLocked = savedEntity?.isLocked ?: false,
            pinHash = savedEntity?.pinHash,
            pinSalt = savedEntity?.pinSalt
        )
        
        val list = entities.filter { it.id != com.neveroff.util.Constants.SAVED_MESSAGES_ID }.map { entity ->
            ChatItemUiModel(
                chatId = entity.id,
                otherUserId = entity.otherUserId,
                otherUserName = entity.customName ?: entity.otherUserName,
                otherPubKey = entity.otherPubKey,
                lastMessage = entity.lastMessage ?: "No messages yet",
                unreadCount = entity.unreadCount,
                isLocked = entity.isLocked,
                pinHash = entity.pinHash,
                pinSalt = entity.pinSalt,
                updatedAt = entity.updatedAt
            )
        }
        listOf(savedMessages) + list
    }

    fun loadChats(currentProfile: Profile?) {
        if (currentProfile == null) return
        val myId = SupabaseClient.client.auth.currentUserOrNull()?.id ?: return
        
        viewModelScope.launch(Dispatchers.IO) {
            _isConnecting.value = true
            try {
                // 1. Get all chats for the current user
                val myParticipants = SupabaseClient.client.postgrest["chat_participants"]
                    .select { filter { eq("user_id", currentProfile.id) } }
                    .decodeList<com.neveroff.ChatParticipantInsert>()
                
                if (myParticipants.isNotEmpty()) {
                    val chatIds = myParticipants.map { it.chat_id }
                    
                    // 2. Get the other participants in these chats
                    val allParticipants = SupabaseClient.client.postgrest["chat_participants"]
                        .select { filter { isIn("chat_id", chatIds) } }
                        .decodeList<com.neveroff.ChatParticipantInsert>()
                    
                    val allOtherParticipants = allParticipants.filter { it.user_id != currentProfile.id }

                    // Defensive guard: this table/loop assumes exactly one "other" participant per
                    // chat (1:1 chats). Group chats aren't supported in the UI yet, so a chat with
                    // more than one other participant would otherwise have its local ChatEntity
                    // silently overwritten once per participant (last one wins). Skip those chats
                    // here instead of corrupting them until group chat is actually built.
                    val multiParticipantChatIds = allOtherParticipants
                        .groupingBy { it.chat_id }
                        .eachCount()
                        .filterValues { it > 1 }
                        .keys
                    if (multiParticipantChatIds.isNotEmpty()) {
                        com.neveroff.util.DebugLogger.log(
                            "Skipping local sync for ${multiParticipantChatIds.size} chat(s) with >1 other participant (group chats not supported yet)"
                        )
                    }
                    val otherParticipants = allOtherParticipants.filter { it.chat_id !in multiParticipantChatIds }

                    if (otherParticipants.isNotEmpty()) {
                        val otherUserIds = otherParticipants.map { it.user_id }

                        // 3. Get profiles for the other users
                        val otherProfiles = SupabaseClient.client.postgrest["profiles"]
                            .select { filter { isIn("id", otherUserIds) } }
                            .decodeList<Profile>()

                        val uiModels = otherParticipants.mapNotNull { participant ->
                            val profile = otherProfiles.find { it.id == participant.user_id }
                            if (profile != null) {
                                ChatItemUiModel(
                                    chatId = participant.chat_id,
                                    otherUserId = profile.id,
                                    otherUserName = profile.display_name,
                                    otherPubKey = profile.public_key
                                )
                            } else null
                        }
                        
                        // Update Local DB — preserve existing lastMessage and unreadCount
                        uiModels.forEach { uiModel ->
                            val existing = chatDao.getChatById(uiModel.chatId)
                            chatDao.insertChat(
                                ChatEntity(
                                    id = uiModel.chatId,
                                    otherUserId = uiModel.otherUserId,
                                    otherUserName = uiModel.otherUserName,
                                    otherPubKey = uiModel.otherPubKey,
                                    lastMessage = existing?.lastMessage,
                                    updatedAt = existing?.updatedAt ?: System.currentTimeMillis(),
                                    unreadCount = existing?.unreadCount ?: 0,
                                    wallpaperUri = existing?.wallpaperUri,
                                    myBubbleColor = existing?.myBubbleColor,
                                    otherBubbleColor = existing?.otherBubbleColor,
                                    customName = existing?.customName,
                                    isLocked = existing?.isLocked ?: false,
                                    pinHash = existing?.pinHash
                                )
                            )
                        }
                    }
                }

                // 4. Sync new messages for all chats from Supabase
                val latestLocalTime = messageDao.getLatestMessageTimestamp() ?: 0L
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val supabaseTimeStr = sdf.format(Date(latestLocalTime))

                val newMessages = SupabaseClient.client.postgrest["messages"]
                    .select {
                        filter { gt("created_at", supabaseTimeStr) }
                    }
                    .decodeList<Message>()

                for (msg in newMessages) {
                    if (msg.sender_id != myId) {
                        val chat = chatDao.getChatById(msg.chat_id)
                        if (chat != null) {
                            val authKey = com.neveroff.crypto.CryptoManager.deriveSharedSecret(chat.otherPubKey)
                            val decrypted = try {
                                com.neveroff.crypto.CryptoManager.decryptMessage(msg.ciphertext, msg.msg_key, authKey)
                            } catch (e: Exception) {
                                "[Encrypted message]"
                            }

                            val entity = MessageEntity(
                                id = msg.id ?: UUID.randomUUID().toString(),
                                chatId = msg.chat_id,
                                senderId = msg.sender_id,
                                content = decrypted,
                                createdAt = parseSupabaseTimestamp(msg.created_at),
                                status = if (msg.status == "read") 2 else 1
                            )
                            messageDao.insertMessage(entity)

                            if (msg.status != "read") {
                                chatDao.updateLastMessageAndIncrementUnread(
                                    chatId = msg.chat_id,
                                    lastMessage = decrypted,
                                    updatedAt = parseSupabaseTimestamp(msg.created_at)
                                )
                            } else {
                                chatDao.updateLastMessage(
                                    chatId = msg.chat_id,
                                    lastMessage = decrypted,
                                    updatedAt = parseSupabaseTimestamp(msg.created_at)
                                )
                            }
                        }
                    }
                }
                // Channels the user owns or follows belong in the same list, so
                // they refresh on the same pull-to-refresh / resume cycle.
                channelRepository.syncMyChannels()

                consecutiveFailures = 0
                _connectionFailed.value = false
            } catch (e: Exception) {
                e.printStackTrace()
                consecutiveFailures++
                if (consecutiveFailures >= FAILURE_THRESHOLD) {
                    _connectionFailed.value = true
                }
            } finally {
                _isConnecting.value = false
            }
        }
    }

    companion object {
        /** Consecutive failed syncs before the "can't connect" warning is shown. */
        private const val FAILURE_THRESHOLD = 3
    }
}

class ChatListViewModelFactory(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val channelRepository: com.neveroff.data.repository.ChannelRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatListViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatListViewModel(chatDao, messageDao, channelRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
