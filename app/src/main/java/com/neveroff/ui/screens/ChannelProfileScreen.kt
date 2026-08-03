package com.neveroff.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neveroff.data.local.ChannelEntity
import com.neveroff.data.model.ChannelLink
import com.neveroff.data.model.ChannelLinkScheme
import com.neveroff.data.repository.ChannelRepository
import com.neveroff.ui.components.ChannelAvatar
import com.neveroff.ui.components.SocialPlatformIcon
import com.neveroff.ui.components.SubscribeButton
import com.neveroff.ui.components.brandColor
import com.neveroff.ui.components.subscriberLabel
import kotlinx.coroutines.launch

/**
 * A channel's profile page — the same shape as a user profile, so opening one
 * feels familiar, but built around the things a channel has: its link, what it
 * posts about, and where else to find it.
 *
 * The owner sees the same page plus a settings button; there is no separate
 * "my channel" screen to keep in sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelProfileScreen(
    channelId: String,
    repository: ChannelRepository,
    myId: String,
    onBack: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenChannel: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val channel by repository.observeChannel(channelId).collectAsState(initial = null)
    var links by remember { mutableStateOf<List<ChannelLink>>(emptyList()) }

    // §6.1: channels are the highest-leverage abuse surface, so they are
    // reportable in their own right rather than only through their owner.
    var showReportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(channelId) {
        repository.fetchChannel(channelId)
        links = repository.fetchLinks(channelId)
    }

    val current = channel
    if (current == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val isOwner = current.ownerId == myId

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(16.dp))

                // Big squared logo — the channel's identity, matching how it
                // appears everywhere else in the app.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    ChannelAvatar(
                        avatarUrl = current.avatarUrl,
                        name = current.name,
                        size = 300.dp,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(70.dp)
                            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                                )
                            )
                    )

                    if (isOwner) {
                        IconButton(
                            onClick = { onOpenSettings(channelId) },
                            modifier = Modifier
                                .padding(12.dp)
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Channel settings",
                                tint = Color.White
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = current.name,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!current.isPublic) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = "Private channel",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                }
                                Text(
                                    text = subscriberLabel(current.subscriberCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!isOwner) {
                            SubscribeButton(
                                isSubscribed = current.isSubscribed,
                                compact = true,
                                onSubscribe = { scope.launch { repository.subscribe(channelId) } }
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    ProfileSectionLabel(if (current.isPublic) "Link" else "Private invite link")
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("channel link", ChannelLinkScheme.linkFor(current.username))
                            )
                            com.neveroff.ui.components.AppToast.success("Link copied — paste it in any chat")
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = ChannelLinkScheme.linkFor(current.username),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy link",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (!current.description.isNullOrBlank()) {
                        Spacer(Modifier.height(16.dp))
                        ProfileSectionLabel("About")
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = current.description,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }

                    if (links.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        ProfileSectionLabel("Links")
                        links.forEach { link ->
                            SocialLinkRow(link = link, context = context)
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = { onOpenChannel(channelId) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = if (isOwner) "Open channel" else "View posts",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!isOwner) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { showReportDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Report this channel",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(Modifier.height(40.dp))
            }

            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }
    }

    if (showReportDialog) {
        com.neveroff.ui.components.ReportDialog(
            targetType = com.neveroff.data.remote.ReportRepository.TargetType.CHANNEL,
            targetId = channelId,
            targetLabel = channel?.name ?: "this channel",
            // Channel posts are stored in plaintext, so unlike a private
            // message the server can already read them. There is nothing for
            // the reporter to volunteer.
            availableEvidence = null,
            onDismiss = { showReportDialog = false }
        )
    }
}

@Composable
private fun SocialLinkRow(link: ChannelLink, context: Context) {
    val platform = link.socialPlatform ?: return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable {
            // Links are typed by hand, so normalise before handing them to the
            // system — "instagram.com/x" without a scheme resolves to nothing.
            val normalized = if (link.url.startsWith("http://") || link.url.startsWith("https://")) {
                link.url
            } else {
                "https://${link.url}"
            }
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
            } catch (e: Exception) {
                com.neveroff.ui.components.AppToast.error("Couldn't open that link")
            }
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(platform.brandColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                SocialPlatformIcon(platform, size = 19.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = platform.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = link.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun ProfileSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}
