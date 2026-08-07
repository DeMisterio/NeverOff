package com.neveroff.ui.components

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neveroff.data.local.ChannelPostEntity
import com.neveroff.data.model.Channel
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
    onForward: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val counts = remember(post.reactionCounts) { decodeReactionCounts(post.reactionCounts) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showActionMenu by remember { mutableStateOf(false) }

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

    // Posts read as incoming messages for everyone, the channel owner included.
    //
    // A feed where the owner sees their own posts right-aligned turns the
    // channel into a chat with themselves, and it changes shape depending on
    // who is looking — the same post sits on a different side for the author
    // than for every reader. Since a channel is a broadcast that everybody
    // receives, including the person who sent it, left is the honest side.
    // Ownership shows up in what you can *do* (long-press to delete), not in
    // which way the bubble points.
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            modifier = Modifier
                // Short of full width, like a received bubble, so the feed reads
                // as a conversation rather than as a stack of cards.
                .fillMaxWidth(0.88f)
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
                        // Long press now opens a menu rather than going
                        // straight to delete. Forwarding is available to every
                        // reader, so the gesture can no longer be owner-only.
                        onLongPress = { showActionMenu = true }
                    )
                },
            // The clipped bottom-start corner is what makes a bubble read as
            // "received" at a glance, before any colour is registered.
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 4.dp
            ),
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
                            text = stringResource(R.string.sending),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (post.sendState < 0) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(R.string.failed_to_publish),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(12.dp)
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Icon(
                        Icons.Default.RemoveRedEye,
                        contentDescription = stringResource(R.string.views),
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

    if (showActionMenu) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showActionMenu = false },
            title = { Text(androidx.compose.ui.res.stringResource(com.neveroff.R.string.action_forward)) },
            text = {
                Column {
                    Text(
                        text = androidx.compose.ui.res.stringResource(
                            com.neveroff.R.string.forward_to
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showActionMenu = false
                                onForward()
                            }
                            .padding(vertical = 12.dp)
                    )
                    if (isOwner) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                com.neveroff.R.string.action_delete
                            ),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showActionMenu = false
                                    showDeleteDialog = true
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showActionMenu = false }) {
                    Text(androidx.compose.ui.res.stringResource(com.neveroff.R.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.delete_post)) },
            text = { Text(stringResource(R.string.this_removes_the_post_for_everyone)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/**
 * Reaction pills, three to a row. Reactions nobody has picked yet are not shown
 * as empty pills — instead the trailing "add" button opens the full picker, so
 * a fresh post stays quiet instead of showing a row of zeroes.
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
    //
    // The cap is MAX_REACTIONS, not the six this grid was first drawn for.
    // Once an owner could allow ten, a hardcoded six silently dropped the four
    // least-recently-configured reactions along with the votes cast on them.
    val used = allowedReactions.filter { (counts[it] ?: 0) > 0 }.take(Channel.MAX_REACTIONS)
    val showAddButton = used.size < Channel.MAX_REACTIONS && allowedReactions.isNotEmpty()

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

    // Three per row — the grid the design calls for. It wraps to as many rows as
    // the used reactions need rather than stopping at two, since stopping meant
    // discarding real counts.
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
                contentDescription = stringResource(R.string.pick_a_reaction),
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
                text = stringResource(R.string.comments_are_coming_soon),
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
 *
 * The row scrolls. A plain Row lays its children out past the edge of the
 * screen and simply clips whatever does not fit, with no way to reach it — at
 * 46dp a cell plus spacing, a full set needs about 540dp of width, which no
 * phone has. That was survivable while a channel could allow at most six
 * reactions and became unreachable content at ten.
 */
@Composable
fun ReactionPickerPanel(
    allowedReactions: List<String>,
    myReaction: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    // Open on the reaction already picked, rather than making someone scroll to
    // find where they are before they can change it.
    LaunchedEffect(myReaction, allowedReactions) {
        val index = allowedReactions.indexOf(myReaction)
        if (index > 0) {
            val stride = with(density) { (REACTION_CELL_SIZE + REACTION_CELL_GAP).roundToPx() }
            scrollState.animateScrollTo(index * stride)
        }
    }

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
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(REACTION_CELL_GAP),
                verticalAlignment = Alignment.CenterVertically
            ) {
                allowedReactions.forEach { emoji ->
                    val isMine = emoji == myReaction
                    Box(
                        modifier = Modifier
                            .size(REACTION_CELL_SIZE)
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

/** Big enough to hit without aiming. */
private val REACTION_CELL_SIZE = 46.dp
private val REACTION_CELL_GAP = 6.dp
