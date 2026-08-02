package com.neveroff.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neveroff.R
import com.neveroff.data.remote.SupabaseClient
import com.neveroff.ui.viewmodels.ChatListViewModel
import com.neveroff.ui.viewmodels.ChatListViewModelFactory
import com.neveroff.util.BounceIconButton
import com.neveroff.util.ServerVersion
import com.neveroff.util.UpdateManager
import com.neveroff.util.bounceClick
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import java.io.File

/** Amber rather than error-red: connectivity is usually transient and user-fixable, not a fault. */
private val ConnectionWarningColor = Color(0xFFFFB300)

data class ChatItemUiModel(
    val chatId: String,
    val otherUserId: String,
    val otherUserName: String,
    val otherPubKey: String,
    val lastMessage: String = "No messages yet",
    val unreadCount: Int = 0,
    val isLocked: Boolean = false,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    /** Used to interleave chats and channels into one recency-ordered list. */
    val updatedAt: Long = 0L
)

/**
 * The main list holds two different things. Channels can't be swiped to delete
 * or locked with a PIN — a channel's history isn't yours to wipe, and it has no
 * private contents to hide — so they can't share a row implementation with chats.
 */
sealed interface ListEntry {
    val sortKey: Long

    data class ChatRow(val chat: ChatItemUiModel) : ListEntry {
        override val sortKey: Long get() = chat.updatedAt
    }

    data class ChannelRow(val channel: com.neveroff.data.local.ChannelEntity) : ListEntry {
        override val sortKey: Long get() = channel.updatedAt
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    currentProfile: Profile?,
    onChatSelected: (String, String, String) -> Unit,
    onSearchUsers: () -> Unit,
    onMyProfileClick: () -> Unit,
    onProfileUpdated: (Profile) -> Unit = {},
    onChannelSelected: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val db = remember { com.neveroff.data.local.AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()

    val prefs = context.getSharedPreferences("neveroff_prefs", android.content.Context.MODE_PRIVATE)
    val myId = com.neveroff.util.CurrentUser.id(prefs)

    val channelRepository = remember(myId) {
        com.neveroff.data.repository.ChannelRepository(db, myId)
    }
    val viewModel: ChatListViewModel = viewModel(
        factory = ChatListViewModelFactory(db.chatDao(), db.messageDao(), channelRepository)
    )
    val isConnecting by viewModel.isConnecting.collectAsState()
    val chatList by viewModel.chatList.collectAsState(initial = emptyList())
    val channelList by viewModel.channelList.collectAsState(initial = emptyList())

    var lastSyncTime by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = System.currentTimeMillis()
                if (now - lastSyncTime > 10_000L) {
                    lastSyncTime = now
                    viewModel.loadChats(currentProfile)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-update state (Stage 5)
    val updateAvailable by com.neveroff.util.UpdateController.available.collectAsState()
    val updateDownloadProgress by com.neveroff.util.UpdateController.progress.collectAsState()
    val downloadedApkFile by com.neveroff.util.UpdateController.downloadedFile.collectAsState()
    val updateBannerDismissedState by com.neveroff.util.UpdateController.bannerDismissed.collectAsState()
    var showWhatsNewDialog by remember { mutableStateOf(false) }
    val updateBannerDismissed = updateBannerDismissedState

    LaunchedEffect(Unit) {
        com.neveroff.util.UpdateController.refresh()
        com.neveroff.util.UpdateController.maybeAutoDownload(context)
    }

    // Connecting Opacity-Wave Animation (Stage 1)
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val waveAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveAlpha"
    )

    val connectionFailed by viewModel.connectionFailed.collectAsState()
    var showConnectionHelp by remember { mutableStateOf(false) }

    val titleText = if (connectionFailed) {
        "Can't connect"
    } else if (isConnecting) {
        "Connecting..."
    } else {
        currentProfile?.display_name ?: stringResource(R.string.app_name)
    }
    
    var showProfileId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    
    // Dialogs state
    var showDeleteConfirmDialog by remember { mutableStateOf<ChatItemUiModel?>(null) }
    var activeLockScreenMode by remember { mutableStateOf<Pair<LockMode, ChatItemUiModel>?>(null) }
    var pendingNavigationChat by remember { mutableStateOf<ChatItemUiModel?>(null) }

    val filteredChatList = if (searchQuery.isBlank()) {
        chatList
    } else {
        chatList.filter {
            it.otherUserName.contains(searchQuery, ignoreCase = true) ||
            it.lastMessage.contains(searchQuery, ignoreCase = true)
        }
    }
    val filteredChannelList = if (searchQuery.isBlank()) {
        channelList
    } else {
        channelList.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.username.contains(searchQuery, ignoreCase = true) ||
                it.lastPost.orEmpty().contains(searchQuery, ignoreCase = true)
        }
    }

    // Favorites stays pinned at the top; everything else — chats and channels
    // alike — is ordered by whichever spoke most recently.
    val listEntries: List<ListEntry> = remember(filteredChatList, filteredChannelList) {
        val savedFirst = filteredChatList.filter { it.chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID }
        val rest: List<ListEntry> =
            filteredChatList
                .filterNot { it.chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID }
                .map { ListEntry.ChatRow(it) } +
                filteredChannelList.map { ListEntry.ChannelRow(it) }
        savedFirst.map { ListEntry.ChatRow(it) } + rest.sortedByDescending { it.sortKey }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(titleText, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        if (currentProfile != null) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 16.dp, end = 8.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .bounceClick { onMyProfileClick() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (!currentProfile.avatar_url.isNullOrEmpty()) {
                                    coil.compose.SubcomposeAsyncImage(
                                        model = currentProfile?.avatar_url,
                                        contentDescription = "My Avatar",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        error = {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = (currentProfile?.display_name ?: "Me").take(1).uppercase(),
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                    )
                                } else {
                                    Text(
                                        text = (currentProfile?.display_name ?: "Me").take(1).uppercase(),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Icon-only now that this leads to more than one thing:
                        // finding a person, and creating a channel.
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .bounceClick { onSearchUsers() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Find people or create a channel",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                // Search Bar with proportional padding on leading icon and localized text
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search") },
                    leadingIcon = { 
                        Icon(
                            Icons.Default.Search, 
                            contentDescription = null,
                            modifier = Modifier.padding(start = 16.dp)
                        ) 
                    },
                    shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                // Needed independently of the banner below: if the user hid the banner before an
                // auto-download started, this is the only thing showing that it's running.
                com.neveroff.ui.components.UpdateProgressStrip()
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (listEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (connectionFailed) {
                        // Repeated sync failures: say so plainly instead of an endless
                        // "Connecting...", and offer the fixes that actually tend to work.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Can't connect",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = ConnectionWarningColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = { showConnectionHelp = true },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Why can't I connect?",
                                    tint = ConnectionWarningColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = if (isConnecting) "Connecting..." else stringResource(R.string.empty_chats),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer { alpha = if (isConnecting) waveAlpha else 1.0f }
                        )
                    }
                }
            } else {
                val onlineUsersFromPresence by com.neveroff.data.remote.PresenceManager.onlineUsers.collectAsState()
                
                var refreshing by remember { mutableStateOf(false) }
                var pullAmount by remember { mutableStateOf(0f) }
                val animatedPullAmount by animateFloatAsState(
                    targetValue = pullAmount,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "pull"
                )

                val nestedScrollConnection = remember {
                    object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                            if (available.y < 0 && pullAmount > 0) {
                                val consumed = available.y.coerceAtLeast(-pullAmount)
                                pullAmount += consumed
                                return androidx.compose.ui.geometry.Offset(0f, consumed)
                            }
                            return androidx.compose.ui.geometry.Offset.Zero
                        }

                        override fun onPostScroll(
                            consumed: androidx.compose.ui.geometry.Offset,
                            available: androidx.compose.ui.geometry.Offset,
                            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
                        ): androidx.compose.ui.geometry.Offset {
                            if (available.y > 0) {
                                val resistance = (1f - (pullAmount / 500f)).coerceIn(0.15f, 1f)
                                pullAmount += available.y * resistance * 0.6f
                                if (pullAmount > 150f && !refreshing) {
                                    refreshing = true
                                    scope.launch {
                                        viewModel.loadChats(currentProfile)
                                        refreshing = false
                                    }
                                }
                                return androidx.compose.ui.geometry.Offset(0f, available.y)
                            }
                            return androidx.compose.ui.geometry.Offset.Zero
                        }

                        override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                            pullAmount = 0f
                            return androidx.compose.ui.unit.Velocity.Zero
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nestedScrollConnection)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationY = animatedPullAmount
                                alpha = if (isConnecting) waveAlpha else 1.0f
                            }
                    ) {
                        items(
                            items = listEntries,
                            key = { entry ->
                                when (entry) {
                                    is ListEntry.ChatRow -> "chat_${entry.chat.chatId}"
                                    is ListEntry.ChannelRow -> "channel_${entry.channel.id}"
                                }
                            }
                        ) { entry ->
                            // Channels have no swipe actions at all: their history
                            // can't be cleared and they can't be PIN-locked, so the
                            // gesture layer below simply doesn't apply to them.
                            if (entry is ListEntry.ChannelRow) {
                                @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                                ChannelListItem(
                                    channel = entry.channel,
                                    modifier = Modifier.animateItemPlacement(),
                                    onClick = { onChannelSelected(entry.channel.id) },
                                    onSubscribe = {
                                        scope.launch { channelRepository.subscribe(entry.channel.id) }
                                    }
                                )
                                return@items
                            }

                            val chat = (entry as ListEntry.ChatRow).chat
                            var offsetX by remember { mutableStateOf(0f) }
                            var isSwiping by remember { mutableStateOf(false) }

                            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                            val modifierWithAnimation = Modifier.animateItemPlacement()

                            val animatedOffsetX by animateFloatAsState(
                                targetValue = offsetX,
                                animationSpec = tween(durationMillis = if (isSwiping) 0 else 300),
                                label = "swipe"
                            )

                            val color by animateColorAsState(
                                when {
                                    animatedOffsetX < -50f -> MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                                    animatedOffsetX > 50f -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                    else -> Color.Transparent
                                },
                                label = "swipeColor"
                            )

                            Box(
                                modifier = modifierWithAnimation
                                    .fillMaxWidth()
                                    .background(color)
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures(
                                            onDragStart = { isSwiping = true },
                                            onDragEnd = {
                                                isSwiping = false
                                                if (offsetX < -120f) {
                                                    showDeleteConfirmDialog = chat
                                                } else if (offsetX > 120f) {
                                                    activeLockScreenMode = Pair(if (chat.isLocked) LockMode.DISABLE else LockMode.SETUP, chat)
                                                }
                                                offsetX = 0f
                                            },
                                            onDragCancel = {
                                                isSwiping = false
                                                offsetX = 0f
                                            },
                                            onHorizontalDrag = { _, dragAmount ->
                                                offsetX += dragAmount * 0.4f
                                                if (offsetX < -300f) offsetX = -300f
                                                if (offsetX > 300f) offsetX = 300f
                                            }
                                        )
                                    }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = if (animatedOffsetX < 0) Alignment.CenterEnd else Alignment.CenterStart
                                ) {
                                    if (animatedOffsetX < -50f) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                                    } else if (animatedOffsetX > 50f) {
                                        Icon(if (chat.isLocked) Icons.Default.Clear else Icons.Default.Lock, contentDescription = "Lock", tint = Color.White)
                                    }
                                }

                                Box(
                                    modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(animatedOffsetX.toInt(), 0) }
                                ) {
                                    val isOnline = onlineUsersFromPresence.contains(chat.otherUserId)
                                    ChatListItem(
                                        chat = chat,
                                        isOnline = isOnline,
                                        onClick = { 
                                            if (chat.isLocked) {
                                                pendingNavigationChat = chat
                                                activeLockScreenMode = Pair(LockMode.UNLOCK, chat)
                                            } else {
                                                val pubKey = if (chat.chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID) {
                                                    currentProfile?.public_key ?: chat.otherPubKey
                                                } else {
                                                    chat.otherPubKey
                                                }
                                                onChatSelected(chat.chatId, pubKey, chat.otherUserName)
                                            }
                                        },
                                        onAvatarClick = { showProfileId = chat.otherUserId }
                                    )
                                }
                            }
                        }
                    }
                    
                    if (refreshing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                }
            }

            // Stage 5: Floating Auto-Update Banner
            if (updateAvailable != null && !updateBannerDismissed) {
                val serverVer = updateAvailable!!
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Update Available: v${serverVer.version}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "A new version of NeverOff is ready.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            // No dismiss while downloading: this banner holds the only progress
                            // readout and the only way to stop it.
                            if (updateDownloadProgress == null) {
                                IconButton(onClick = { com.neveroff.util.UpdateController.dismissBanner() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }

                        if (updateDownloadProgress != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { updateDownloadProgress!! },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Downloading update ${(updateDownloadProgress!! * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = { com.neveroff.util.UpdateController.cancelDownload() },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Stop download",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!serverVer.description.isBlank()) {
                                TextButton(onClick = { showWhatsNewDialog = true }) {
                                    Text("What's new")
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if (downloadedApkFile != null) {
                                Button(onClick = { UpdateManager.installApk(context, downloadedApkFile!!) }) {
                                    Text("Install")
                                }
                            } else {
                                Button(
                                    enabled = updateDownloadProgress == null,
                                    onClick = { com.neveroff.util.UpdateController.startDownload(context) }
                                ) {
                                    Text("Download")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Stage 5: What's New Dialog
    if (showWhatsNewDialog && updateAvailable != null) {
        com.neveroff.ui.components.WhatsNewDialog(
            version = updateAvailable!!.version,
            description = updateAvailable!!.description,
            onDismiss = { showWhatsNewDialog = false }
        )
    }

    if (showConnectionHelp) {
        AlertDialog(
            onDismissRequest = { showConnectionHelp = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = ConnectionWarningColor) },
            title = { Text("Can't connect", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "NeverOff can't reach the server right now. Your messages are safe — " +
                            "anything you send will go out once the connection is back.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))

                    ConnectionHelpItem(
                        number = "1",
                        title = "Try a VPN",
                        body = "In some regions the server is blocked. Turning on a VPN usually " +
                            "restores the connection immediately."
                    )
                    ConnectionHelpItem(
                        number = "2",
                        title = "Check the app is official",
                        body = "Only install NeverOff from a link you trust. An unofficial copy may " +
                            "point at a server that no longer exists."
                    )
                    ConnectionHelpItem(
                        number = "3",
                        title = "Pull down to reconnect",
                        body = "Swipe the chat list downwards to force a fresh connection attempt."
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showConnectionHelp = false }) { Text("Got it") }
            }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmDialog != null) {
        val chat = showDeleteConfirmDialog!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Chat?") },
            text = { Text("Are you sure you want to delete this chat?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            SupabaseClient.client.postgrest["chats"].delete { 
                                filter { eq("id", chat.chatId) } 
                            }
                            db.messageDao().deleteMessagesByChatId(chat.chatId)
                            db.chatDao().deleteChatById(chat.chatId)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    showDeleteConfirmDialog = null
                }) {
                    Text("Yes", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Lock Screen Overlay
    activeLockScreenMode?.let { (mode, chat) ->
        LockScreen(
            mode = mode,
            correctPinHash = chat.pinHash,
            correctPinSalt = chat.pinSalt,
            onSuccess = { newPinHash, newPinSalt ->
                scope.launch {
                    if (mode == LockMode.SETUP && newPinHash != null) {
                        val existing = db.chatDao().getChatById(chat.chatId)
                        if (existing == null) {
                            db.chatDao().insertChat(
                                com.neveroff.data.local.ChatEntity(
                                    id = chat.chatId,
                                    otherUserId = chat.otherUserId,
                                    otherUserName = chat.otherUserName,
                                    otherPubKey = chat.otherPubKey,
                                    lastMessage = chat.lastMessage,
                                    updatedAt = System.currentTimeMillis(),
                                    isLocked = true,
                                    pinHash = newPinHash,
                                    pinSalt = newPinSalt
                                )
                            )
                        } else {
                            db.chatDao().updateChatLockState(chat.chatId, true, newPinHash, newPinSalt)
                        }
                    } else if (mode == LockMode.DISABLE) {
                        db.chatDao().updateChatLockState(chat.chatId, false, null, null)
                    } else if (mode == LockMode.UNLOCK) {
                        pendingNavigationChat?.let { pending ->
                            val pubKey = if (pending.chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID) {
                                currentProfile?.public_key ?: pending.otherPubKey
                            } else {
                                pending.otherPubKey
                            }
                            onChatSelected(pending.chatId, pubKey, pending.otherUserName)
                            pendingNavigationChat = null
                        }
                    }
                }
                activeLockScreenMode = null
            },
            onCancel = {
                activeLockScreenMode = null
                pendingNavigationChat = null
            },
            onEraseChat = {
                scope.launch {
                    db.messageDao().deleteMessagesByChatId(chat.chatId)
                    db.chatDao().deleteChatById(chat.chatId)
                }
                activeLockScreenMode = null
                pendingNavigationChat = null
            }
        )
    }

    ProfileDialog(
        isVisible = showProfileId != null,
        profileId = showProfileId ?: "",
        onDismiss = {
            showProfileId = null
            scope.launch { viewModel.loadChats(currentProfile) }
        },
        onPickAvatar = null,
        selectedAvatarUri = null,
        onAvatarUploaded = {}
    )
}

/**
 * A channel in the main list. Same silhouette as a chat row so the list reads as
 * one list, with two differences that carry meaning: a squared picture instead of
 * a circle, and a Subscribe button on the right while you're only a visitor.
 */
@Composable
fun ChannelListItem(
    channel: com.neveroff.data.local.ChannelEntity,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onSubscribe: () -> Unit
) {
    val isUnread = channel.unreadCount > 0

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isUnread) 1f else 0.85f),
        tonalElevation = if (isUnread) 3.dp else 1.dp,
        shadowElevation = if (isUnread) 3.dp else 1.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.neveroff.ui.components.ChannelAvatar(
                avatarUrl = channel.avatarUrl,
                name = channel.name,
                size = 52.dp
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Campaign,
                        contentDescription = "Channel",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (channel.isSubscribed && !channel.notificationsEnabled) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = "Muted",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = channel.lastPost
                        ?: com.neveroff.ui.components.subscriberLabel(channel.subscriberCount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isUnread) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!channel.isSubscribed) {
                Spacer(modifier = Modifier.width(8.dp))
                com.neveroff.ui.components.SubscribeButton(
                    isSubscribed = false,
                    compact = true,
                    onSubscribe = onSubscribe
                )
            } else if (channel.unreadCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 24.dp)
                        .height(24.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primary, com.neveroff.ui.theme.AccentPurpleDeep)
                            )
                        )
                        .padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (channel.unreadCount > 99) "99+" else channel.unreadCount.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ChatListItem(chat: ChatItemUiModel, isOnline: Boolean, onClick: () -> Unit, onAvatarClick: () -> Unit) {
    val isSavedMessages = chat.chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID
    val displayName = if (isSavedMessages) "Favorites" else chat.otherUserName
    val isUnread = chat.unreadCount > 0

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isUnread) 1f else 0.85f),
        tonalElevation = if (isUnread) 3.dp else 1.dp,
        shadowElevation = if (isUnread) 3.dp else 1.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(52.dp)
            ) {
                if (isSavedMessages) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF03A9F4), Color(0xFF0277BD)))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Favorites",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                } else {
                    var remoteProfile by remember { mutableStateOf<com.neveroff.ui.screens.Profile?>(null) }
                    LaunchedEffect(chat.otherUserId) {
                        remoteProfile = com.neveroff.data.cache.ProfileCache.getProfile(chat.otherUserId)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { onAvatarClick() }
                            .then(
                                if (isOnline) {
                                    Modifier.border(2.dp, Color(0xFF4CAF50).copy(alpha = 0.6f), CircleShape)
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (remoteProfile?.avatar_url != null) {
                            coil.compose.AsyncImage(
                                model = remoteProfile?.avatar_url,
                                contentDescription = "Avatar",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = displayName.take(1).uppercase(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (isOnline) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                                .border(2.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (chat.isLocked) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.Lock, contentDescription = "Locked", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isUnread) "${chat.lastMessage}..." else chat.lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isUnread) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (chat.unreadCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 24.dp)
                        .height(24.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primary, com.neveroff.ui.theme.AccentPurpleDeep)
                            )
                        )
                        .padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** Numbered step in the "can't connect" FAQ. */
@Composable
private fun ConnectionHelpItem(number: String, title: String, body: String) {
    Row(modifier = Modifier.padding(bottom = 14.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp, end = 12.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(ConnectionWarningColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ConnectionWarningColor
            )
        }
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
