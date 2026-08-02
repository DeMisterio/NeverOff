package com.neveroff.data.remote

import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.presenceDataFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class PresenceUser(val id: String)

object PresenceManager {
    private val _onlineUsers = MutableStateFlow<Set<String>>(emptySet())
    val onlineUsers: StateFlow<Set<String>> = _onlineUsers.asStateFlow()

    private var presenceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startTracking() {
        if (presenceJob?.isActive == true) return

        com.neveroff.util.DebugLogger.log("PresenceManager: startTracking()")
        presenceJob = scope.launch {
            try {
                val myId = SupabaseClient.client.auth.currentUserOrNull()?.id ?: run {
                    com.neveroff.util.DebugLogger.log("PresenceManager: myId is null!")
                    return@launch
                }
                com.neveroff.util.DebugLogger.log("PresenceManager: myId = $myId")
                
                // Wait for Realtime to be connected before subscribing
                val connected = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    SupabaseClient.client.realtime.status.first { status ->
                        status == Realtime.Status.CONNECTED 
                    }
                }
                if (connected == null) {
                    com.neveroff.util.DebugLogger.log("PresenceManager: Realtime not connected after 15s, skipping")
                    return@launch
                }
                
                val channel = SupabaseClient.client.channel("online_users")
                
                val presenceFlow = channel.presenceDataFlow<PresenceUser>()
                
                presenceFlow.onEach { states ->
                    com.neveroff.util.DebugLogger.log("Presence: states updated, count = ${states.size}")
                    _onlineUsers.value = states.map { it.id }.toSet()
                }.launchIn(this)

                com.neveroff.util.DebugLogger.log("PresenceManager: subscribing to online_users channel...")
                channel.subscribe(blockUntilSubscribed = true)
                com.neveroff.util.DebugLogger.log("PresenceManager: subscribed! tracking user...")
                channel.track(PresenceUser(id = myId))
                com.neveroff.util.DebugLogger.log("PresenceManager: track() called")
            } catch (e: Exception) {
                com.neveroff.util.DebugLogger.log("PresenceManager error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun stopTracking() {
        presenceJob?.cancel()
        presenceJob = null
        _onlineUsers.value = emptySet()
    }
}
