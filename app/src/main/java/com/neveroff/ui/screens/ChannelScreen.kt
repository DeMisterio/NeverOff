package com.neveroff.ui.screens

import com.neveroff.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neveroff.data.local.decodeReactionList
import com.neveroff.data.model.Channel
import com.neveroff.data.model.ChannelLinkScheme
import com.neveroff.data.repository.ChannelRepository
import com.neveroff.ui.components.ChannelAvatar
import com.neveroff.ui.components.ChannelPostItem
import com.neveroff.ui.components.NotificationBellButton
import com.neveroff.ui.components.ReactionPickerPanel
import com.neveroff.ui.components.SubscribeButton
import com.neveroff.ui.components.subscriberLabel
import com.neveroff.ui.viewmodels.ChannelViewModel
import com.neveroff.ui.viewmodels.ChannelViewModelFactory
import com.neveroff.util.BounceIconButton
import com.neveroff.util.SettingsManager
import kotlinx.coroutines.delay

/** How long a post must stay on screen before it counts as read. */
private const val VIEW_DWELL_MS = 2_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    channelId: String,
    repository: ChannelRepository,
    myId: String,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenSettings: (String) -> Unit = {}
) {
    val viewModel: ChannelViewModel = viewModel(
        key = "channel_$channelId",
        factory = ChannelViewModelFactory(repository, channelId)
    )
    val channel by viewModel.channel.collectAsState()
    val posts by viewModel.posts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val pickerForPost by viewModel.pickerForPost.collectAsState()
    val textSize by SettingsManager.bubbleTextSizeFlow.collectAsState()

    val isOwner = channel?.ownerId == myId
    val allowedReactions = remember(channel?.allowedReactions) {
        decodeReactionList(channel?.allowedReactions.orEmpty())
            .ifEmpty { Channel.DEFAULT_REACTIONS }
    }

    val listState = rememberLazyListState()
    var composerText by rememberSaveable { mutableStateOf("") }
    var isPublishing by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        viewModel.onStart()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onStart()
                Lifecycle.Event.ON_STOP -> viewModel.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onStop()
        }
    }

    // A post counts as viewed only after it has been continuously on screen for
    // VIEW_DWELL_MS. Restarting the timer whenever the visible set changes means
    // scrolling straight past a post never counts it; the repository's local
    // `viewLogged` flag then keeps a re-read from counting twice.
    val visibleIds by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() }
    }
    LaunchedEffect(visibleIds) {
        if (visibleIds.isEmpty()) return@LaunchedEffect
        delay(VIEW_DWELL_MS)
        visibleIds.forEach { viewModel.registerView(it) }
    }

    // The post awaiting a forward target, or null. Holding the post rather than
    // a boolean means the dialog cannot be opened without knowing what it would
    // send.
    var forwardingPost by remember {
        mutableStateOf<com.neveroff.data.local.ChannelPostEntity?>(null)
    }

    // Open at the newest post, the way a chat does. Without this the list would
    // start at the top, which for a channel with months of history means
    // landing on a post from last year.
    var hasJumpedToLatest by remember { mutableStateOf(false) }
    LaunchedEffect(posts.isNotEmpty()) {
        if (posts.isNotEmpty() && !hasJumpedToLatest) {
            listState.scrollToItem(posts.lastIndex)
            hasJumpedToLatest = true
        }
    }

    // New posts arrive at the bottom; follow them only if the reader is already
    // there, so somebody reading back through history is not yanked away.
    LaunchedEffect(posts.size) {
        if (posts.isNotEmpty() && hasJumpedToLatest) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= posts.lastIndex - 2) {
                listState.animateScrollToItem(posts.lastIndex)
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onOpenProfile(channelId) }
                        ) {
                            ChannelAvatar(
                                avatarUrl = channel?.avatarUrl,
                                name = channel?.name ?: "Channel",
                                size = 40.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = channel?.name ?: "Channel",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = subscriberLabel(channel?.subscriberCount ?: 0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        BounceIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        var showMenu by remember { mutableStateOf(false) }
                        val menuContext = LocalContext.current
                        val clipboard = menuContext
                            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.copy_link)) },
                                    onClick = {
                                        showMenu = false
                                        val link = ChannelLinkScheme.linkFor(channel?.username.orEmpty())
                                        clipboard.setPrimaryClip(ClipData.newPlainText(menuContext.getString(R.string.channel_link), link))
                                        com.neveroff.ui.components.AppToast.success(
                                            "Link copied — paste it in any chat"
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.channel_info)) },
                                    onClick = {
                                        showMenu = false
                                        onOpenProfile(channelId)
                                    }
                                )
                                if (isOwner) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.title_settings)) },
                                        onClick = {
                                            showMenu = false
                                            onOpenSettings(channelId)
                                        }
                                    )
                                } else {
                                    if (channel?.isSubscribed == true) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.unsubscribe)) },
                                            onClick = {
                                                showMenu = false
                                                viewModel.unsubscribe()
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.remove_from_my_list), color = MaterialTheme.colorScheme.error)
                                        },
                                        onClick = {
                                            showMenu = false
                                            viewModel.removeFromList { onBack() }
                                        }
                                    )
                                }
                                // Note: no "clear history" — a channel's posts are
                                // its content, and nobody gets to wipe them here.
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
                com.neveroff.ui.components.UpdateProgressStrip()
            }
        },
        bottomBar = {
            if (isOwner) {
                PostComposer(
                    text = composerText,
                    onTextChange = { if (it.length <= 8000) composerText = it },
                    isPublishing = isPublishing,
                    onPublish = {
                        val body = composerText
                        composerText = ""
                        isPublishing = true
                        viewModel.publishPost(body) { success ->
                            isPublishing = false
                            if (!success) {
                                com.neveroff.ui.components.AppToast.error("Post couldn't be published")
                            }
                        }
                    }
                )
            } else {
                ViewerActionBar(
                    isSubscribed = channel?.isSubscribed == true,
                    notificationsEnabled = channel?.notificationsEnabled != false,
                    onSubscribe = { viewModel.subscribe() },
                    onToggleNotifications = { viewModel.setNotifications(it) }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                posts.isEmpty() && isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                posts.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isOwner) {
                                "No posts yet. Write the first one below — every subscriber will see it."
                            } else {
                                "This channel hasn't posted anything yet."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    // Plain ascending order, laid out normally: oldest at the
                    // top, newest at the bottom, scrolled to the end on open.
                    //
                    // This previously reversed the data *and* set
                    // reverseLayout, which is a double negation that is
                    // genuinely hard to reason about and was not producing what
                    // it looked like it should. One direction, stated once,
                    // with the scroll position handled explicitly below.
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(posts, key = { _, p -> p.id }) { index, post ->
                            // A channel's posts can span months, so the day
                            // pill matters more here than in a chat.
                            if (com.neveroff.ui.components.startsNewDay(
                                    post.createdAt,
                                    posts.getOrNull(index - 1)?.createdAt
                                )
                            ) {
                                com.neveroff.ui.components.DaySeparator(post.createdAt)
                            }
                            ChannelPostItem(
                                post = post,
                                allowedReactions = allowedReactions,
                                isOwner = isOwner,
                                textSize = textSize,
                                onReact = { emoji -> viewModel.toggleReaction(post.id, emoji) },
                                onOpenReactionPicker = { viewModel.openReactionPicker(post.id) },
                                onForward = { forwardingPost = post },
                                onDelete = {
                                    viewModel.deletePost(post.id) { success ->
                                        if (!success) {
                                            com.neveroff.ui.components.AppToast.error("Couldn't delete the post")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    forwardingPost?.let { post ->
        val forwardContext = androidx.compose.ui.platform.LocalContext.current
        val forwardScope = rememberCoroutineScope()
        val db = remember { com.neveroff.data.local.AppDatabase.getDatabase(forwardContext) }
        var chats by remember { mutableStateOf<List<com.neveroff.data.local.ChatEntity>>(emptyList()) }
        val selected = remember { mutableStateListOf<String>() }

        LaunchedEffect(Unit) { db.chatDao().getAllChats().collect { chats = it } }

        AlertDialog(
            onDismissRequest = { forwardingPost = null },
            title = { Text(stringResource(com.neveroff.R.string.forward_to)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    chats.forEach { chat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selected.contains(chat.id)) selected.remove(chat.id)
                                    else selected.add(chat.id)
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Checkbox(
                                checked = selected.contains(chat.id),
                                onCheckedChange = { on ->
                                    if (on) selected.add(chat.id) else selected.remove(chat.id)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(chat.otherUserName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        val targets = selected.toList()
                        val username = channel?.username
                        forwardingPost = null
                        forwardScope.launch {
                            // Without a resolved username the attribution
                            // footer would be missing, which is the one part of
                            // a forward that must not be silently dropped.
                            if (username.isNullOrBlank()) {
                                com.neveroff.ui.components.AppToast.error(
                                    forwardContext.getString(com.neveroff.R.string.forward_failed)
                                )
                                return@launch
                            }
                            val sent = com.neveroff.data.repository.PostForwarder.forward(
                                db = db,
                                myId = myId,
                                postText = post.content,
                                channelUsername = username,
                                targetChatIds = targets
                            )
                            if (sent > 0) {
                                com.neveroff.ui.components.AppToast.success(
                                    forwardContext.getString(com.neveroff.R.string.forward_sent)
                                )
                            } else {
                                com.neveroff.ui.components.AppToast.error(
                                    forwardContext.getString(com.neveroff.R.string.forward_failed)
                                )
                            }
                        }
                    }
                ) {
                    Text("${stringResource(com.neveroff.R.string.action_forward)} (${selected.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { forwardingPost = null }) {
                    Text(stringResource(com.neveroff.R.string.action_cancel))
                }
            }
        )
    }

    pickerForPost?.let { postId ->
        val post = posts.find { it.id == postId }
        ReactionPickerPanel(
            allowedReactions = allowedReactions,
            myReaction = post?.myReaction,
            onPick = { emoji ->
                viewModel.toggleReaction(postId, emoji)
                viewModel.closeReactionPicker()
            },
            onDismiss = { viewModel.closeReactionPicker() }
        )
    }
}

/** Owner-only composer. Viewers never see it — a channel is one-way. */
@Composable
private fun PostComposer(
    text: String,
    onTextChange: (String) -> Unit,
    isPublishing: Boolean,
    onPublish: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            // Fixed radius: CircleShape scales with height, so a long post
            // rounded the field into a capsule.
            shape = INPUT_FIELD_SHAPE,
            placeholder = { Text(stringResource(R.string.write_a_post)) },
            singleLine = false,
            maxLines = INPUT_FIELD_MAX_LINES,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent
            )
        )
        Spacer(Modifier.width(6.dp))
        val enabled = text.isNotBlank() && !isPublishing
        Surface(
            shape = CircleShape,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            modifier = Modifier.size(46.dp).clickable(enabled = enabled) { onPublish() }
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isPublishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.publish_post),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Bottom bar for readers: subscribe on the left (it collapses away once you're
 * in), notification bell on the right (which stays, because muting is a thing
 * you change your mind about).
 */
@Composable
private fun ViewerActionBar(
    isSubscribed: Boolean,
    notificationsEnabled: Boolean,
    onSubscribe: () -> Unit,
    onToggleNotifications: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SubscribeButton(isSubscribed = isSubscribed, onSubscribe = onSubscribe)

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
            visible = isSubscribed,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            Text(
                text = if (notificationsEnabled) "Notified of new posts" else "New posts stay silent",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp)
            )
        }

        NotificationBellButton(
            enabled = notificationsEnabled,
            onToggle = { wanted ->
                // Notification preferences live on the subscription row, so there
                // is nothing to set until you've actually subscribed. Say so
                // instead of flipping an icon that wouldn't stick.
                if (isSubscribed) {
                    onToggleNotifications(wanted)
                } else {
                    com.neveroff.ui.components.AppToast.show("Subscribe first to choose how you're notified")
                }
            }
        )
    }
}
