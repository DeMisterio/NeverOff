package com.neveroff.ui.components

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neveroff.data.model.Channel
import com.neveroff.data.model.ChannelLinkScheme
import com.neveroff.data.repository.ChannelRepository
import com.neveroff.util.bounceClick

/**
 * The bubble a shared `n.me/<channel>` link turns into: picture on the left,
 * name and subscriber count on the right, and a View button underneath.
 *
 * Colours come from the caller's bubble palette rather than the app theme, so
 * the card sits inside a message bubble in every chat theme without clashing.
 */
@Composable
fun ChannelLinkCard(
    username: String,
    repository: ChannelRepository,
    contentColor: Color,
    accentColor: Color,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var channel by remember(username) { mutableStateOf<Channel?>(null) }
    var failed by remember(username) { mutableStateOf(false) }

    LaunchedEffect(username) {
        val resolved = repository.channelByUsername(username)
        if (resolved == null) failed = true else channel = resolved
    }

    Surface(
        modifier = modifier.widthIn(min = 210.dp, max = 280.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.16f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val resolved = channel
                if (resolved != null) {
                    ChannelAvatar(
                        avatarUrl = resolved.avatar_url,
                        name = resolved.name,
                        size = 46.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(
                                contentColor.copy(alpha = 0.15f),
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (failed) {
                            Icon(
                                Icons.Default.Campaign,
                                contentDescription = null,
                                tint = contentColor.copy(alpha = 0.6f),
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = contentColor.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (resolved?.is_public == false) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = stringResource(R.string.private_channel),
                                tint = contentColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = resolved?.name ?: if (failed) "Channel not found" else "Loading…",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (resolved != null) {
                            subscriberLabel(resolved.subscriber_count)
                        } else {
                            ChannelLinkScheme.linkFor(username)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val resolved = channel
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (resolved != null) accentColor else contentColor.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick { resolved?.let { onOpenChannel(it.id) } }
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (failed) "Unavailable" else "View channel",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (resolved != null) Color.White else contentColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
