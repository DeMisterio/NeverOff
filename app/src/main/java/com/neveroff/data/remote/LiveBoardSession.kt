package com.neveroff.data.remote

import com.neveroff.crypto.CryptoManager
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The wire for a shared drawing board.
 *
 * Strokes travel over Supabase Realtime broadcast rather than through a table.
 * A board is a live conversation, not a record: nobody wants to reopen a chat
 * and find last Tuesday's doodle, and writing every pointer sample to Postgres
 * to delete it minutes later would be a lot of work to achieve nothing.
 *
 * The payload is encrypted with the same per-chat shared secret as the messages
 * around it. Broadcast goes through Supabase in the clear otherwise, and a
 * drawing board would then be the single surface in a private conversation the
 * server could read — which is exactly the property this app exists to avoid.
 * A doodle is not less private than the sentence it replaces.
 *
 * Coordinates are normalised to 0..1 of the canvas. The two people have windows
 * of different sizes and possibly different rotations, so sending pixels would
 * put the same stroke in two different places.
 */
class LiveBoardSession(
    private val chatId: String,
    private val myId: String,
    private val authKey: ByteArray
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(Dispatchers.IO)
    private var channel: RealtimeChannel? = null
    private var job: Job? = null

    @Serializable
    data class Point(val x: Float, val y: Float)

    /**
     * One continuous stroke, or a piece of one.
     *
     * Sent in pieces while the finger is still down so the other side sees the
     * line grow rather than appearing whole when the finger lifts. [id] ties the
     * pieces together so the receiver extends a stroke instead of starting a new
     * one at each batch, which would leave visible seams where the segments meet.
     */
    @Serializable
    data class Stroke(
        val id: String,
        val authorId: String,
        val color: Long,
        val width: Float,
        val points: List<Point>,
        val done: Boolean = false
    )

    @Serializable
    data class Envelope(val c: String, val k: String)

    sealed interface Event {
        data class Invited(val fromUserId: String) : Event
        data class Joined(val userId: String) : Event
        data class Drew(val stroke: Stroke) : Event
        data object Cleared : Event
        data object Closed : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events

    /** Subscribes and starts relaying. Safe to call twice. */
    suspend fun connect() {
        if (channel != null) return
        val ch = SupabaseClient.client.realtime.channel("board_$chatId")
        channel = ch

        job = ch.broadcastFlow<Envelope>(EVENT)
            .onEach { env ->
                val plain = try {
                    CryptoManager.decryptMessage(env.c, env.k, authKey)
                } catch (e: Exception) {
                    // Not for us, or from a stale session with a different key.
                    // Dropping it silently is right: a board is live, and there
                    // is nothing useful to tell the user about one lost sample.
                    return@onEach
                }
                val msg = runCatching { json.decodeFromString<Wire>(plain) }.getOrNull()
                    ?: return@onEach
                // Never echo our own strokes back onto our own canvas: they are
                // already drawn locally, and re-adding them doubles every line.
                if (msg.from == myId && msg.type != TYPE_CLOSE) return@onEach

                when (msg.type) {
                    TYPE_INVITE -> _events.tryEmit(Event.Invited(msg.from))
                    TYPE_JOIN -> _events.tryEmit(Event.Joined(msg.from))
                    TYPE_STROKE -> msg.stroke?.let { _events.tryEmit(Event.Drew(it)) }
                    TYPE_CLEAR -> _events.tryEmit(Event.Cleared)
                    TYPE_CLOSE -> _events.tryEmit(Event.Closed)
                }
            }
            .launchIn(scope)

        ch.subscribe(blockUntilSubscribed = true)
    }

    @Serializable
    private data class Wire(
        val type: String,
        val from: String,
        val stroke: Stroke? = null
    )

    private suspend fun send(wire: Wire) {
        val ch = channel ?: return
        try {
            val (cipher, key) = CryptoManager.encryptMessage(json.encodeToString(wire), authKey)
            ch.broadcast(EVENT, Envelope(cipher, key))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun invite() = send(Wire(TYPE_INVITE, myId))
    suspend fun join() = send(Wire(TYPE_JOIN, myId))
    suspend fun draw(stroke: Stroke) = send(Wire(TYPE_STROKE, myId, stroke))
    suspend fun clear() = send(Wire(TYPE_CLEAR, myId))
    suspend fun close() = send(Wire(TYPE_CLOSE, myId))

    fun disconnect() {
        job?.cancel()
        job = null
        val ch = channel
        channel = null
        scope.launch { runCatching { ch?.unsubscribe() } }
    }

    private companion object {
        const val EVENT = "board"
        const val TYPE_INVITE = "invite"
        const val TYPE_JOIN = "join"
        const val TYPE_STROKE = "stroke"
        const val TYPE_CLEAR = "clear"
        const val TYPE_CLOSE = "close"
    }
}
