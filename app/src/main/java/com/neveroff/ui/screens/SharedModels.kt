package com.neveroff.ui.screens

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

fun parseSupabaseTimestamp(timestamp: String?): Long {
    if (timestamp.isNullOrEmpty()) return System.currentTimeMillis()
    return try {
        java.time.Instant.parse(timestamp).toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
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
                contentDescription = "Failed to send — tap to retry",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp)
            )
        }
        status >= 2 -> {
            // Read: double green ticks
            Icon(Icons.Default.Done, contentDescription = "Read", tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp).offset(x = 4.dp))
            Icon(Icons.Default.Done, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
        }
        status == 1 -> {
            // Delivered: double gray ticks
            Icon(Icons.Default.Done, contentDescription = "Delivered", tint = color, modifier = Modifier.size(14.dp).offset(x = 4.dp))
            Icon(Icons.Default.Done, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        }
        else -> {
            // Sent: single gray tick
            Icon(Icons.Default.Done, contentDescription = "Sent", tint = color, modifier = Modifier.size(14.dp))
        }
    }
    }
}
