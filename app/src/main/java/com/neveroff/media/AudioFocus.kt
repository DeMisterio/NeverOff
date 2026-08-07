package com.neveroff.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Audio focus for playback that makes noise.
 *
 * Nothing in the app asked for focus, so a video played on top of whatever the
 * user already had going — the music kept running underneath instead of
 * pausing, and both were audible at once.
 *
 * The request is TRANSIENT rather than permanent: a voice note or a clip is an
 * interruption, not a new listening session, so the other app should pause and
 * pick up where it left off rather than stop for good. And it is TRANSIENT, not
 * TRANSIENT_MAY_DUCK, because ducking leaves music playing quietly underneath
 * speech, which is the case this exists to prevent.
 *
 * ExoPlayer can manage focus itself via setAudioAttributes(handleAudioFocus),
 * but it requests on every play() regardless of volume. Video previews in the
 * message list autoplay muted, and interrupting someone's music to show a
 * silent looping preview is worse than not asking at all — hence the explicit
 * [active] flag, which callers set only for playback that is actually audible.
 */
private fun audioManager(context: Context): AudioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

/**
 * Holds audio focus for as long as [active] is true.
 *
 * Focus is abandoned when [active] goes false and when the composable leaves
 * composition, which covers pause, stop, and navigating away in one place
 * rather than asking every call site to remember all three.
 *
 * [onFocusLost] fires when something else takes focus — a call, an alarm — so
 * the caller can pause rather than talk over it.
 */
@Composable
fun AudioFocusEffect(active: Boolean, onFocusLost: () -> Unit = {}) {
    val context = LocalContext.current
    val currentOnFocusLost by rememberUpdatedState(onFocusLost)

    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }

        val manager = audioManager(context)
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> currentOnFocusLost()
            }
        }

        // AudioFocusRequest is the only form that works from O on; the
        // deprecated call is a no-op there.
        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
        } else {
            null
        }

        if (request != null) {
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }

        onDispose {
            if (request != null) {
                manager.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(listener)
            }
        }
    }
}
