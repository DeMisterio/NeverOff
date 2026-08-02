package com.neveroff.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ToastType { INFO, SUCCESS, ERROR }

data class ToastMessage(val text: String, val type: ToastType, val id: Long)

/**
 * In-app replacement for [android.widget.Toast] so feedback matches the app's own theme
 * (rounded card, icon, slide/fade motion) instead of the stock OS popup.
 */
object AppToast {
    private val _current = MutableStateFlow<ToastMessage?>(null)
    val current: StateFlow<ToastMessage?> = _current
    private var counter = 0L

    fun show(text: String, type: ToastType = ToastType.INFO) {
        counter++
        _current.value = ToastMessage(text, type, counter)
    }

    fun success(text: String) = show(text, ToastType.SUCCESS)
    fun error(text: String) = show(text, ToastType.ERROR)

    internal fun clear(id: Long) {
        if (_current.value?.id == id) _current.value = null
    }
}

/** Mount once at the app root, above navigation, so any screen's [AppToast.show] surfaces here. */
@Composable
fun AppToastHost(modifier: Modifier = Modifier) {
    val message by AppToast.current.collectAsState()

    LaunchedEffect(message?.id) {
        val msg = message ?: return@LaunchedEffect
        delay(if (msg.type == ToastType.ERROR) 3800L else 2400L)
        AppToast.clear(msg.id)
    }

    // Keep rendering the last message's content while it slides/fades out, since `message`
    // itself flips to null the instant the visibility flag does.
    var lastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    LaunchedEffect(message) { if (message != null) lastMessage = message }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.padding(bottom = 32.dp, start = 20.dp, end = 20.dp)
        ) {
            lastMessage?.let { msg ->
                val (icon, accent) = when (msg.type) {
                    ToastType.SUCCESS -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
                    ToastType.ERROR -> Icons.Default.Warning to MaterialTheme.colorScheme.error
                    ToastType.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.primary
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
