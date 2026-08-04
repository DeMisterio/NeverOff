package com.neveroff.ui.screens

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class Message(
    val id: String? = null,
    val chat_id: String = "",
    val sender_id: String = "",
    val ciphertext: String = "",
    val msg_key: String = "",
    val created_at: String = "",
    val status: String = "sent"
)

/**
 * Parses a timestamp as PostgREST actually emits it.
 *
 * This used to be a single Instant.parse with a fallback to "now", and it was
 * silently failing for every row ever fetched. PostgREST renders a timestamptz
 * with a numeric offset — "2026-08-02T22:17:33.7173+00:00" — while
 * Instant.parse uses ISO_INSTANT, which requires the literal "Z" designator and
 * rejects "+00:00". So every parse threw, every row fell through to
 * System.currentTimeMillis(), and every message and channel post was stamped
 * with the moment it happened to be downloaded.
 *
 * That is why a whole channel read as posted seconds ago, and why a re-synced
 * chat came back with every message sharing one timestamp and sorting by
 * insertion order instead of by time.
 *
 * "Now" was the worst possible fallback here precisely because it looks
 * plausible: a wrong-but-believable value produces no error, no crash and no
 * report, just quietly wrong history. The three parsers below cover every shape
 * Postgres emits, so it is now genuinely a last resort.
 */
fun parseSupabaseTimestamp(timestamp: String?): Long {
    if (timestamp.isNullOrEmpty()) return System.currentTimeMillis()

    // "...Z" — ISO_INSTANT, what Instant.parse alone handles.
    try {
        return java.time.Instant.parse(timestamp).toEpochMilli()
    } catch (e: Exception) { /* fall through */ }

    // "...+00:00" / "...+02:00" — what PostgREST actually sends for timestamptz.
    try {
        return java.time.OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()
    } catch (e: Exception) { /* fall through */ }

    // No offset at all — a plain `timestamp` column. Postgres stores those in
    // UTC and PostgREST renders them naked, so reading them as anything else
    // would shift every value by the device's offset.
    try {
        return java.time.LocalDateTime.parse(timestamp)
            .toInstant(java.time.ZoneOffset.UTC)
            .toEpochMilli()
    } catch (e: Exception) { /* fall through */ }

    return System.currentTimeMillis()
}

fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * Shared backdrop for the auth/onboarding flow (login, OTP, profile setup): a soft
 * vertical gradient, so those screens don't sit on a flat single-color background.
 */
@Composable
fun AuthGradientBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                )
            )
    ) {
        content()
    }
}

@Composable
fun StatusTicks(status: Int, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
    when {
        status < 0 -> {
            // Failed to send
            Icon(
                Icons.Default.Warning,
                contentDescription = stringResource(R.string.failed_to_send_tap_to_retry),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp)
            )
        }
        status >= 2 -> {
            // Read: double green ticks
            Icon(Icons.Default.Done, contentDescription = stringResource(R.string.read), tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp).offset(x = 4.dp))
            Icon(Icons.Default.Done, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
        }
        status == 1 -> {
            // Delivered: double gray ticks
            Icon(Icons.Default.Done, contentDescription = stringResource(R.string.delivered), tint = color, modifier = Modifier.size(14.dp).offset(x = 4.dp))
            Icon(Icons.Default.Done, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        }
        else -> {
            // Sent: single gray tick
            Icon(Icons.Default.Done, contentDescription = stringResource(R.string.sent), tint = color, modifier = Modifier.size(14.dp))
        }
    }
    }
}
