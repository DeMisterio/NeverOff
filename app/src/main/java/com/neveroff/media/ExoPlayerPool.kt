package com.neveroff.media

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer

/**
 * Object pool for ExoPlayer instances.
 * Reuses released players instead of creating new ones on every media playback,
 * reducing GC pressure and initialization latency.
 */
object ExoPlayerPool {
    private val available = ArrayDeque<ExoPlayer>()
    private val inUse = mutableSetOf<ExoPlayer>()
    private const val maxSize: Int = 3

    @Synchronized
    fun acquire(context: Context): ExoPlayer {
        val player = available.removeFirstOrNull() ?: ExoPlayer.Builder(context.applicationContext).build()
        inUse.add(player)
        return player
    }

    /**
     * Returns a player to the pool. The player is stopped and its media items cleared.
     * If the pool is full, the player is fully released instead.
     */
    @Synchronized
    fun release(player: ExoPlayer) {
        player.stop()
        player.clearMediaItems()
        inUse.remove(player)
        if (available.size < maxSize) {
            available.addLast(player)
        } else {
            player.release()
        }
    }

    /**
     * Releases all players (both available and in-use) and clears the pool.
     * Call this when the hosting Activity/Fragment is destroyed.
     */
    @Synchronized
    fun releaseAll() {
        (available + inUse).forEach { it.release() }
        available.clear()
        inUse.clear()
    }
}
