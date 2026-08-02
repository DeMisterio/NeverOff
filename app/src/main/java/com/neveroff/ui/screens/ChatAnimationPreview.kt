@file:OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)

package com.neveroff.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

/**
 * Tracks whether the Activity is currently resumed. LaunchedEffect loops keep ticking (delay()
 * calls keep firing) as long as their composable stays in composition, regardless of whether the
 * app is actually visible — without this, backgrounding the app while a Live Preview is on
 * screen would leave its animation loop spinning in the background for no visible benefit.
 */
@Composable
private fun rememberIsAppForeground(): State<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> state.value = true
                Lifecycle.Event.ON_PAUSE -> state.value = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** How much slower than real chat the demo motion plays, so the physics are easy to actually see. */
private const val PreviewSpeed = 0.35f

/** How long (ms) a preview waits after the user last touched it before its auto-loop resumes. */
private const val InteractionCooldownMs = 2000L

private fun previewMessage(id: String, text: String, isMine: Boolean, status: Int = 0): UiMessage =
    UiMessage(id = id, text = text, isMine = isMine, timestamp = System.currentTimeMillis(), status = status)

private fun scrollDemoMessages(): List<UiMessage> = listOf(
    previewMessage("s1", "Hey! Long time no talk", false),
    previewMessage("s2", "I know, it's been forever!", true, status = 2),
    previewMessage("s3", "How's the new job going?", false),
    previewMessage("s4", "Really good actually, keeping busy", true, status = 2),
    previewMessage("s5", "Nice! We should catch up sometime", false),
    previewMessage("s6", "Definitely, this week?", true, status = 2),
    previewMessage("s7", "Hey, are you free later?", false),
    previewMessage("s8", "Yeah, what's up?", true, status = 2),
    previewMessage("s9", "Wanna grab coffee?", false),
    previewMessage("s10", "Sure! 3pm works", true, status = 2),
    previewMessage("s11", "Perfect, see you then 👋", false),
    previewMessage("s12", "See you there!", true, status = 1),
    previewMessage("s13", "Running 5 min late, sorry!", false),
    previewMessage("s14", "No worries at all", true, status = 2),
    previewMessage("s15", "Just parked, walking in now", false),
    previewMessage("s16", "Great, I'm at the corner table", true, status = 2)
)

/** Shared themed backdrop every preview window renders inside. */
@Composable
private fun PreviewFrame(theme: ChatTheme, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val backgroundBrush = if (theme.backgroundGradient.size >= 2 && theme.backgroundGradient[0] != Color.Transparent) {
        Brush.verticalGradient(theme.backgroundGradient)
    } else {
        SolidColor(MaterialTheme.colorScheme.background)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundBrush)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
        content = content
    )
}

@Composable
private fun PreviewCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

// ---------------------------------------------------------------------------------------------
// Scroll-physics content: a real, user-scrollable message history that auto-demos a
// fast-up / slow-stop / back-down bounce when nobody is touching it.
// ---------------------------------------------------------------------------------------------
@Composable
private fun ScrollPhysicsContent(
    theme: ChatTheme,
    textSize: Float,
    bounciness: Float,
    stretchiness: Boolean,
    allAnimations: Boolean,
    onInteract: () -> Unit
) {
    val messages = remember { scrollDemoMessages() }
    val listState = rememberLazyListState()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val isForeground by rememberIsAppForeground()
    var lastInteractionAt by remember { mutableStateOf(0L) }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            lastInteractionAt = System.currentTimeMillis()
            onInteract()
        }
    }

    LaunchedEffect(allAnimations) {
        if (!allAnimations) return@LaunchedEffect
        while (true) {
            while (!isForeground || isDragged || listState.isScrollInProgress || System.currentTimeMillis() - lastInteractionAt < InteractionCooldownMs) {
                delay(150)
            }
            listState.animateScrollBy(1000f, tween(1500, easing = FastOutSlowInEasing))
            delay(900)
            while (!isForeground || isDragged || System.currentTimeMillis() - lastInteractionAt < InteractionCooldownMs) {
                delay(150)
            }
            listState.animateScrollBy(-1000f, tween(1300, easing = FastOutSlowInEasing))
            delay(1500)
        }
    }

    // Claim any scroll delta the list itself can't consume (i.e. once it hits the top/bottom)
    // so it never bubbles up into whatever outer scrollable this preview is embedded in.
    val consumeOverscroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
        }
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(consumeOverscroll),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(messages.reversed(), key = { it.id }) { msg ->
            MessageBubble(
                msg = msg,
                allMessages = messages,
                remoteName = "Alex",
                onReply = {},
                onReplyClick = {},
                onDelete = {},
                onFullScreen = { _, _ -> },
                theme = theme,
                textSize = textSize,
                bouncinessFactor = bounciness,
                stretchinessEnabled = stretchiness,
                allAnimationsEnabled = allAnimations
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Send & receive content: an outgoing bubble pops in, then (after a beat) an incoming reply does.
// ---------------------------------------------------------------------------------------------
@Composable
private fun SendReceiveContent(
    theme: ChatTheme,
    textSize: Float,
    bounciness: Float,
    stretchiness: Boolean,
    allAnimations: Boolean
) {
    var tick by remember { mutableStateOf(0) }
    var showIncoming by remember { mutableStateOf(false) }
    val isForeground by rememberIsAppForeground()

    LaunchedEffect(allAnimations) {
        if (!allAnimations) return@LaunchedEffect
        while (true) {
            while (!isForeground) delay(200)
            tick++
            showIncoming = false
            delay(600)
            showIncoming = true
            delay(2600)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        key(tick) {
            MessageBubble(
                msg = previewMessage("out-$tick", "On my way!", isMine = true, status = 2),
                allMessages = emptyList(),
                remoteName = "Alex",
                onReply = {}, onReplyClick = {}, onDelete = {}, onFullScreen = { _, _ -> },
                theme = theme, textSize = textSize, bouncinessFactor = bounciness,
                stretchinessEnabled = stretchiness, allAnimationsEnabled = allAnimations,
                speedMultiplier = PreviewSpeed
            )
        }
        if (showIncoming) {
            key(tick) {
                MessageBubble(
                    msg = previewMessage("in-$tick", "Great, see you soon!", isMine = false),
                    allMessages = emptyList(),
                    remoteName = "Alex",
                    onReply = {}, onReplyClick = {}, onDelete = {}, onFullScreen = { _, _ -> },
                    theme = theme, textSize = textSize, bouncinessFactor = bounciness,
                    stretchinessEnabled = stretchiness, allAnimationsEnabled = allAnimations,
                    speedMultiplier = PreviewSpeed
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Reply-gesture content: a bubble that demos the swipe-to-reply drag on its own, but the user
// can also just grab it — real dragging always wins over the scripted demo.
// ---------------------------------------------------------------------------------------------
@Composable
private fun ReplyGestureContent(
    theme: ChatTheme,
    textSize: Float,
    bounciness: Float,
    stretchiness: Boolean,
    allAnimations: Boolean,
    onInteract: () -> Unit
) {
    var demoTarget by remember { mutableStateOf(0f) }
    var isUserDragging by remember { mutableStateOf(false) }
    var lastInteractionAt by remember { mutableStateOf(0L) }
    val isForeground by rememberIsAppForeground()

    LaunchedEffect(allAnimations) {
        if (!allAnimations) { demoTarget = 0f; return@LaunchedEffect }
        while (true) {
            while (!isForeground || isUserDragging || System.currentTimeMillis() - lastInteractionAt < InteractionCooldownMs) {
                delay(150)
            }
            demoTarget = 90f
            delay(1200)
            demoTarget = 0f
            delay(1500)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        MessageBubble(
            msg = previewMessage("reply-demo", "Swipe me to reply →", isMine = false),
            allMessages = emptyList(),
            remoteName = "Alex",
            onReply = {}, onReplyClick = {}, onDelete = {}, onFullScreen = { _, _ -> },
            theme = theme, textSize = textSize, bouncinessFactor = bounciness,
            stretchinessEnabled = stretchiness, allAnimationsEnabled = allAnimations,
            speedMultiplier = PreviewSpeed,
            demoDragTarget = if (allAnimations) demoTarget else null,
            onUserDragActive = { active ->
                isUserDragging = active
                lastInteractionAt = System.currentTimeMillis()
                onInteract()
            }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Combined loop: cycles scroll -> send/receive -> reply demo in the same window, forever.
// This is what stands in for a "live chat" preview (used both as gallery page 1 and standalone
// in the Appearance screen).
// ---------------------------------------------------------------------------------------------
private enum class ComboPhase { SCROLL, SEND, REPLY }

@Composable
fun CombinedChatPreview(
    theme: ChatTheme,
    textSize: Float,
    bounciness: Float,
    stretchiness: Boolean,
    allAnimations: Boolean,
    modifier: Modifier = Modifier
) {
    var phase by remember { mutableStateOf(ComboPhase.SCROLL) }
    var lastInteractionAt by remember { mutableStateOf(0L) }
    val isForeground by rememberIsAppForeground()
    val onInteract: () -> Unit = { lastInteractionAt = System.currentTimeMillis() }

    LaunchedEffect(allAnimations) {
        if (!allAnimations) return@LaunchedEffect
        while (true) {
            val holdMs = when (phase) {
                ComboPhase.SCROLL -> 5300L
                ComboPhase.SEND -> 3300L
                ComboPhase.REPLY -> 2900L
            }
            delay(holdMs)
            while (!isForeground || System.currentTimeMillis() - lastInteractionAt < 1500L) delay(200)
            phase = when (phase) {
                ComboPhase.SCROLL -> ComboPhase.SEND
                ComboPhase.SEND -> ComboPhase.REPLY
                ComboPhase.REPLY -> ComboPhase.SCROLL
            }
        }
    }

    PreviewFrame(theme = theme, modifier = modifier) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) },
            label = "comboPhase",
            modifier = Modifier.fillMaxSize()
        ) { p ->
            when (p) {
                ComboPhase.SCROLL -> ScrollPhysicsContent(theme, textSize, bounciness, stretchiness, allAnimations, onInteract)
                ComboPhase.SEND -> SendReceiveContent(theme, textSize, bounciness, stretchiness, allAnimations)
                ComboPhase.REPLY -> ReplyGestureContent(theme, textSize, bounciness, stretchiness, allAnimations, onInteract)
            }
        }
    }
}

/**
 * Swipeable gallery of chat-animation previews: the full combined loop first, then dedicated
 * single-purpose windows (scroll only, send/receive only, reply gesture only). Each window is
 * the real [MessageBubble] and is fully interactive — dragging or scrolling it pauses its
 * auto-loop, which resumes on its own a couple seconds after you let go.
 */
@Composable
fun ChatAnimationPreviewGallery(
    theme: ChatTheme,
    textSize: Float,
    bounciness: Float,
    stretchiness: Boolean,
    allAnimations: Boolean,
    modifier: Modifier = Modifier
) {
    val captions = listOf(
        "Full experience — scroll, send & reply, looping",
        "Scroll physics only",
        "Sending & receiving",
        "Swipe to reply"
    )
    val pagerState = rememberPagerState(pageCount = { captions.size })

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 16.dp
        ) { page ->
            when (page) {
                0 -> CombinedChatPreview(theme, textSize, bounciness, stretchiness, allAnimations)
                1 -> PreviewFrame(theme = theme) {
                    ScrollPhysicsContent(theme, textSize, bounciness, stretchiness, allAnimations, onInteract = {})
                }
                2 -> PreviewFrame(theme = theme) {
                    SendReceiveContent(theme, textSize, bounciness, stretchiness, allAnimations)
                }
                3 -> PreviewFrame(theme = theme) {
                    ReplyGestureContent(theme, textSize, bounciness, stretchiness, allAnimations, onInteract = {})
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        PreviewCaption(captions.getOrElse(pagerState.currentPage) { "" })
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(captions.size) { i ->
                val selected = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        )
                )
            }
        }
    }
}
