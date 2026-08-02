package com.neveroff.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neveroff.data.local.ChannelEntity
import com.neveroff.data.local.ChannelPostEntity
import com.neveroff.data.repository.ChannelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChannelViewModel(
    private val repository: ChannelRepository,
    private val channelId: String
) : ViewModel() {

    val channel: StateFlow<ChannelEntity?> = repository.observeChannel(channelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val posts: StateFlow<List<ChannelPostEntity>> = repository.observePosts(channelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** Set once the reaction picker should be shown, holding the target post id. */
    private val _pickerForPost = MutableStateFlow<String?>(null)
    val pickerForPost: StateFlow<String?> = _pickerForPost

    init {
        viewModelScope.launch {
            try {
                val channel = repository.fetchChannel(channelId)
                // A channel opened by link may already be followed from another
                // device; ask the server rather than assuming "not subscribed".
                if (channel != null) repository.refreshSubscriptionState(channelId)
                repository.syncPosts(channelId)
                repository.markChannelRead(channelId)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onStart() {
        repository.subscribeToRealtime(channelId)
        viewModelScope.launch { repository.markChannelRead(channelId) }
    }

    fun onStop() {
        repository.unsubscribeRealtime(channelId)
    }

    fun refresh() {
        viewModelScope.launch {
            repository.fetchChannel(channelId)
            repository.syncPosts(channelId)
        }
    }

    /**
     * Drops this channel from the device's list without unsubscribing anyone.
     * Merely opening a link adds a channel to the list, so there has to be a way
     * to take one back out again.
     */
    fun removeFromList(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.unsubscribe(channelId)
            repository.forgetLocally(channelId)
            onDone()
        }
    }

    fun publishPost(content: String, onComplete: (Boolean) -> Unit = {}) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { onComplete(repository.createPost(channelId, trimmed)) }
    }

    fun deletePost(postId: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onComplete(repository.deletePost(postId)) }
    }

    /** Called once a post has been on screen long enough to count as read. */
    fun registerView(postId: String) {
        viewModelScope.launch { repository.registerView(postId) }
    }

    fun toggleReaction(postId: String, emoji: String) {
        viewModelScope.launch { repository.toggleReaction(postId, emoji) }
    }

    fun openReactionPicker(postId: String) { _pickerForPost.value = postId }

    fun closeReactionPicker() { _pickerForPost.value = null }

    fun subscribe(onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onComplete(repository.subscribe(channelId)) }
    }

    fun unsubscribe(onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onComplete(repository.unsubscribe(channelId)) }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch { repository.setNotifications(channelId, enabled) }
    }

    override fun onCleared() {
        super.onCleared()
        repository.unsubscribeRealtime(channelId)
    }
}

class ChannelViewModelFactory(
    private val repository: ChannelRepository,
    private val channelId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChannelViewModel::class.java)) {
            return ChannelViewModel(repository, channelId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
