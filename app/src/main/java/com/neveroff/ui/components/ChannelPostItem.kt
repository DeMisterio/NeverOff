package com.neveroff.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neveroff.data.local.ChannelPostEntity
import com.neveroff.data.local.decodeReactionCounts
import com.neveroff.ui.screens.formatTime

/** The reaction a double-tap applies, matching the gesture everyone expects. */
const val DOUBLE_TAP_REACTION = "❤️"

/**
 * One post in a channel.
 *
 * Layout, top to bottom: the text, then a footer with the time on the left and
 * the view count on the right, then the "comments soon" pill, then the reaction
 * grid. Reactions sit last because they are the only interactive part for a
 * reader — putting them at the thumb end keeps the tap short.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelPostItem(
    post: ChannelPostEntity,
    allowedReactions: List<String>,
    isOwner: Boolean,
    textSize: Float,
    onReact: (String) -> Unit,
    onOpenReactionPicker: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val counts = remember(post.reactionCounts) { decodeReactionCounts(post.reactionCounts) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Reacting is gated here rather than at each call site: reactions are
    // reachable from the tap row, the picker and a double tap on the bubble,
    // and a restriction honoured by two of the three is not a restriction.
    val reactBlocked = isFeatureRestricted(
        com.neveroff.data.remote.EnforcementState.Feature.REACT_TO_POST
    )
    val reactContext = androidx.compose.ui.platform.LocalContext.current
    val reactBlockedLabel = androidx.compose.ui.res.stringResource(
        com.neveroff.R.string.feature_react_to_post
    )
    val guardedReact: (String) -> Unit = { emoji ->
        if (reactBlocked) {
            AppToast.show(
                reactContext.getString(
                    com.neveroff.R.string.restriction_blocked_toast, reactBlockedLabel
                )
            )
        } else onReact(emoji)
    }
    val guardedOpenPicker: () -> Unit = {
        if (reactBlocked) {
            AppToast.show(
                reactContext.getString(
                    com.neveroff.R.string.restriction_blocked_toast, reactBlockedLabel
                )
            )
        } else onOpenReactionPicker()
    }

    // A heart that pops on double tap, the way a "like" should feel.
    var heartBurst by remember { mutableStateOf(false) }
    val burstScale by animateFloatAsState(
        targetValue = if (heartBurst) 1.6f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "heartBurst"
    )
    LaunchedEffect(heartBurst) {
        if (heartBurst) {
            kotlinx.coroutines.delay(450)
            heartBurst = false
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(post.id, allowedReactions) {
                    detectTapGestures(
                        onDoubleTap = {
                            // Fall back to the first allowed reaction if the owner
                            // has turned the heart off, so the gesture never dead-ends.
                            val emoji = if (DOUBLE_TAP_REACTION in allowedReactions) {
                                DOUBLE_TAP_REACTION
                            } else {
                                allowedReactions.firstOrNull()
                            }
                            if (emoji != null) {
                                heartBurst = true
                                guardedReact(emoji)
                            }
                        },
                        onLongPress = { if (isOwner) showDeleteDialog = true }
                    )
                },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = post.content,
                    fontSize = textSize.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(8.dp))

                // Time on the left, views on the right — same line, as in the feed
                // of any broadcast app: when it went out, and how far it got.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(post.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (post.sendState == 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Sending…",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (post.sendState < 0) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Failed to publish",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(12.dp)
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Icon(
                        Icons.Default.RemoveRedEye,
                        contentDescription = "Views",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = formatCompactCount(post.viewCount),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(10.dp))

                CommentsSoonPill()

                Spacer(Modifier.height(8.dp))

                ReactionGrid(
                    allowedReactions = allowedReactions,
                    counts = counts,
                    myReaction = post.myReaction,
                    onReact = guardedReact,
                    onOpenPicker = guardedOpenPicker
                )
            }
        }

        if (burstScale > 0.01f) {
            Text(
                text = DOUBLE_TAP_REACTION,
                fontSize = 44.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .scale(burstScale)
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete post?") },
            text = { Text("This removes the post for everyone who follows the channel.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Reaction pills, at most six in a 3x2 grid. Reactions nobody has picked yet are
 * not shown as empty pills — instead the trailing "add" button opens the full
 * picker, so a fresh post stays quiet instead of showing six zeroes.
 */
@Composable
private fun ReactionGrid(
    allowedReactions: List<String>,
    counts: Map<String, Int>,
    myReaction: String?,
    onReact: (String) -> Unit,
    onOpenPicker: () -> Unit
) {
    // Keep the owner's configured order, and only show what somebody has used.
    val used = allowedReactions.filter { (counts[it] ?: 0) > 0 }.take(6)
    val showAddButton = used.size < 6 && allowedReactions.isNotEmpty()

    val cells: List<@Composable () -> Unit> = buildList {
        used.forEach { emoji ->
            add {
                ReactionPill(
                    emoji = emoji,
                    count = counts[emoji] ?: 0,
                    isMine = myReaction == emoji,
                    onClick = { onReact(emoji) }
                )
            }
        }
        if (showAddButton) add { AddReactionPill(onClick = onOpenPicker) }
    }

    // Three per row, two rows max — the grid the design calls for.
    cells.chunked(3).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            row.forEach { cell ->
                Box(modifier = Modifier.weight(1f)) { cell() }
            }
            // Keep pills at a consistent width when the last row is short.
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ReactionPill(emoji: String, count: Int, isMine: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (isMine) 1.04f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pillScale"
    )
    Surface(
        shape = CircleShape,
        color = if (isMine) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        border = if (isMine) androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.primary
        ) else null,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = emoji, fontSize = 15.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isMine) FontWeight.Bold else FontWeight.Medium,
                color = if (isMine) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AddReactionPill(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.AddReaction,
                contentDescription = "Pick a reaction",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "+",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Placeholder for the comments feature, so the gap reads as planned, not missing. */
@Composable
private fun CommentsSoonPill() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "Comments are coming soon",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * The long pill that slides up when you tap "+": every reaction this channel
 * allows, in one row, big enough to hit without aiming.
 */
@Composable
fun ReactionPickerPanel(
    allowedReactions: List<String>,
    myReaction: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                // Swallow taps so hitting the panel doesn't dismiss it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                allowedReactions.forEach { emoji ->
                    val isMine = emoji == myReaction
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .then(
                                if (isMine) Modifier.background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                ) else Modifier
                            )
                            .then(
                                if (isMine) Modifier.border(
                                    1.dp, MaterialTheme.colorScheme.primary, CircleShape
                                ) else Modifier
                            )
                            .clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 26.sp)
                    }
                }
            }
        }
    }
}
