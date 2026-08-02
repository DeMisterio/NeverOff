package com.neveroff.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neveroff.data.local.MessageEntity
import com.neveroff.data.repository.ChatRepository
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository,
    private val chatId: String,
    private val otherPubKey: String
) : ViewModel() {

    // Expose messages as StateFlow
    val messages: StateFlow<List<MessageEntity>> = repository.observeMessages(chatId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Expose chat details for customization
    val chatDetails: StateFlow<com.neveroff.data.local.ChatEntity?> = repository.observeChat(chatId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _isSyncing = kotlinx.coroutines.flow.MutableStateFlow(true)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    init {
        // Sync history once when ViewModel is created
        viewModelScope.launch {
            try {
                repository.syncHistory(chatId)
                repository.markChatAsRead(chatId)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun onStart() {
        // Called when UI becomes visible
        repository.subscribeToRealtime(chatId)
        markAsRead()
    }

    fun markAsRead() {
        viewModelScope.launch {
            repository.markChatAsRead(chatId)
        }
    }

    fun onStop() {
        // Called when UI goes to background
        repository.unsubscribeRealtime()
    }

    fun sendMessage(text: String, replyToId: String? = null, onComplete: (Boolean) -> Unit) {
        val finalText = if (replyToId != null) "$text::REPLY::$replyToId" else text
        viewModelScope.launch {
            val success = repository.sendMessage(chatId, finalText)
            onComplete(success)
        }
    }

    /** Re-attempts a previously failed (status == -1) text message send. */
    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            repository.retrySendMessage(messageId)
        }
    }

    private val _mediaProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Float>>(emptyMap())
    val mediaProgress: StateFlow<Map<String, Float>> = _mediaProgress

    fun sendMediaMessage(prefix: String, file: java.io.File, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val (success, messageId) = repository.sendMediaMessage(chatId, prefix, file) { msgId, progress ->
                _mediaProgress.value = _mediaProgress.value.toMutableMap().apply {
                    if (progress >= 1f) remove(msgId) else put(msgId, progress)
                }
            }
            if (!success && messageId != null) {
                // Clear stuck progress if upload failed
                _mediaProgress.value = _mediaProgress.value.toMutableMap().apply { remove(messageId) }
            }
            onComplete(success)
        }
    }

    fun deleteChat(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repository.deleteChat(chatId)
                onComplete(true)
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(false)
            }
        }
    }

    private val _selectedMessageIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val selectedMessageIds: StateFlow<Set<String>> = _selectedMessageIds

    fun toggleSelection(msgId: String) {
        val current = _selectedMessageIds.value
        _selectedMessageIds.value = if (current.contains(msgId)) {
            current - msgId
        } else {
            current + msgId
        }
    }

    fun clearSelection() {
        _selectedMessageIds.value = emptySet()
    }

    fun deleteSelectedMessages(onComplete: (Boolean) -> Unit) {
        val ids = _selectedMessageIds.value.toList()
        if (ids.isEmpty()) return
        
        viewModelScope.launch {
            try {
                val success = repository.deleteMessages(ids)
                if (success) clearSelection()
                onComplete(success)
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(false)
            }
        }
    }

    fun updateCustomization(wallpaperUri: String?, myBubbleColor: Long?, otherBubbleColor: Long?) {
        viewModelScope.launch {
            repository.updateCustomization(chatId, wallpaperUri, myBubbleColor, otherBubbleColor)
        }
    }

    fun editMessage(msgId: String, newText: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = repository.editMessage(msgId, newText)
            onComplete(success)
        }
    }

    fun setCustomName(name: String?) {
        viewModelScope.launch {
            repository.updateCustomName(chatId, name)
        }
    }

    fun deleteMessageForEveryone(msgId: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onComplete(repository.deleteMessageForEveryone(msgId))
        }
    }

    fun forwardMessages(messages: List<com.neveroff.ui.screens.UiMessage>, targetChatIds: List<String>) {
        // Reuse the repository's resolved id: deriving it from the auth session here would
        // yield null (and silently drop the forward) whenever the session isn't resolved.
        val myId = repository.myId
        if (myId.isEmpty()) return
        viewModelScope.launch {
            val db = repository.db
            for (targetId in targetChatIds) {
                var authKeyToUse: ByteArray? = null
                if (targetId == com.neveroff.util.Constants.SAVED_MESSAGES_ID) {
                    val profile = com.neveroff.data.cache.ProfileCache.getProfile(myId)
                    if (profile != null) {
                        authKeyToUse = com.neveroff.crypto.CryptoManager.deriveSharedSecret(profile.public_key)
                    }
                } else {
                    val chat = db.chatDao().observeChatById(targetId).firstOrNull()
                    if (chat != null) {
                        authKeyToUse = com.neveroff.crypto.CryptoManager.deriveSharedSecret(chat.otherPubKey)
                    }
                }

                if (authKeyToUse != null) {
                    val tempRepo = com.neveroff.data.repository.ChatRepository(db, myId, authKeyToUse)
                    for (msg in messages) {
                        tempRepo.sendMessage(targetId, "[FWD]" + msg.text)
                    }
                }
            }
        }
    }
}

class ChatViewModelFactory(
    private val repository: ChatRepository,
    private val chatId: String,
    private val otherPubKey: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(repository, chatId, otherPubKey) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
