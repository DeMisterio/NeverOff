package com.neveroff.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.neveroff.R
import com.neveroff.data.model.SocialPlatform
import com.neveroff.util.bounceClick

/**
 * Channel avatars are rounded squares, not circles — that difference is the
 * fastest way to tell a channel row from a person's row at a glance.
 */
@Composable
fun ChannelAvatar(
    avatarUrl: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(size / 3.2f)
) {
    Box(
        // Caller's modifier goes between size and clip so a caller can widen the
        // avatar (the profile banner does) without losing the rounded corners.
        modifier = Modifier
            .size(size)
            .then(modifier)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        com.neveroff.ui.theme.AccentPurpleDeep
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        val fallback: @Composable () -> Unit = {
            Text(
                text = name.trim().take(1).uppercase().ifBlank { "#" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = when {
                    size >= 96.dp -> MaterialTheme.typography.displaySmall
                    size >= 56.dp -> MaterialTheme.typography.headlineSmall
                    else -> MaterialTheme.typography.titleMedium
                }
            )
        }
        if (avatarUrl.isNullOrEmpty()) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = avatarUrl,
                contentDescription = "$name avatar",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = { fallback() },
                loading = { fallback() }
            )
        }
    }
}

val SocialPlatform.brandColor: Color
    get() = when (this) {
        SocialPlatform.INSTAGRAM -> Color(0xFFE1306C)
        SocialPlatform.SNAPCHAT -> Color(0xFFF7B500)
        SocialPlatform.TIKTOK -> Color(0xFF00F2EA)
        SocialPlatform.TELEGRAM -> Color(0xFF29A9EB)
    }

val SocialPlatform.iconRes: Int
    get() = when (this) {
        SocialPlatform.INSTAGRAM -> R.drawable.ic_social_instagram
        SocialPlatform.SNAPCHAT -> R.drawable.ic_social_snapchat
        SocialPlatform.TIKTOK -> R.drawable.ic_social_tiktok
        SocialPlatform.TELEGRAM -> R.drawable.ic_social_telegram
    }

@Composable
fun SocialPlatformIcon(platform: SocialPlatform, size: Dp = 20.dp, tint: Color = platform.brandColor) {
    Icon(
        painter = painterResource(platform.iconRes),
        contentDescription = platform.label,
        tint = tint,
        modifier = Modifier.size(size)
    )
}

/**
 * Subscribe control. On success it plays a tick, holds it briefly so the change
 * is legible, then collapses away — the button's job is done once you're in.
 */
@Composable
fun SubscribeButton(
    isSubscribed: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSubscribe: () -> Unit
) {
    var justSubscribed by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(!isSubscribed) }

    LaunchedEffect(isSubscribed) {
        if (isSubscribed) {
            if (visible) {
                // Was on screen and just flipped: show the tick before collapsing.
                justSubscribed = true
                kotlinx.coroutines.delay(900)
                visible = false
            } else {
                visible = false
            }
        } else {
            justSubscribed = false
            visible = true
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        exit = shrinkHorizontally(animationSpec = tween(280)) + fadeOut(tween(200))
    ) {
        val tickScale by animateFloatAsState(
            targetValue = if (justSubscribed) 1f else 0.6f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "tickScale"
        )
        Surface(
            shape = CircleShape,
            color = if (justSubscribed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
            modifier = Modifier.bounceClick { if (!justSubscribed) onSubscribe() }
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = if (compact) 14.dp else 20.dp,
                    vertical = if (compact) 7.dp else 11.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (justSubscribed) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Subscribed",
                        tint = Color.White,
                        modifier = Modifier.size(if (compact) 16.dp else 20.dp).scale(tickScale)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Subscribed",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
                    )
                } else {
                    Text(
                        text = "Subscribe",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/** Bell toggle: whether new posts in this channel raise a notification. */
@Composable
fun NotificationBellButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.bounceClick { onToggle(!enabled) }
    ) {
        Box(modifier = Modifier.padding(11.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (enabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                contentDescription = if (enabled) "Notifications on — tap to mute" else "Notifications off — tap to unmute",
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** "1.2K" — subscriber and view counts both outgrow the space they're given. */
fun formatCompactCount(count: Int): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f).replace(".0M", "M")
    count >= 1_000 -> String.format("%.1fK", count / 1_000f).replace(".0K", "K")
    else -> count.toString()
}

fun subscriberLabel(count: Int): String =
    "${formatCompactCount(count)} ${if (count == 1) "subscriber" else "subscribers"}"
