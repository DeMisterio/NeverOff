package com.neveroff.ui.screens
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.res.painterResource
import java.util.UUID
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Clear
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import com.neveroff.ui.theme.readableContentColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.neveroff.R
import com.neveroff.crypto.CryptoManager
import com.neveroff.data.local.AppDatabase
import com.neveroff.data.remote.SupabaseClient
import com.neveroff.data.repository.ChatRepository
import io.github.jan.supabase.storage.storage
import com.neveroff.ui.viewmodels.ChatViewModel
import com.neveroff.ui.viewmodels.ChatViewModelFactory
import com.neveroff.util.ImageHelper
import com.neveroff.util.SettingsManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.launch
import io.github.jan.supabase.postgrest.postgrest
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
@Serializable
data class Message(
    val id: String? = null,
    val chat_id: String,
    val sender_id: String,
    val ciphertext: String,
    val msg_key: String,
    val status: String = "sent",
    val created_at: String? = null
)
data class UiMessage(
    val id: String?,
    val text: String,
    val isMine: Boolean,
    val status: String = "sent",
    val createdAt: Long = 0L,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToAuthorIsMe: Boolean? = null,
    val isViewOnce: Boolean = false
)
private val supabaseTimestampFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("UTC")
}
private val timeOnlyFormat = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateTimeFormat = java.text.SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
fun parseSupabaseTimestamp(raw: String?): Long {
    if (raw == null) return System.currentTimeMillis()
    return try {
        val truncated = raw.take(19)
        supabaseTimestampFormat.parse(truncated)?.time ?: System.currentTimeMillis()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}
fun formatMessageTime(epochMs: Long): String {
    val now = Calendar.getInstance()
    val msgCal = Calendar.getInstance().apply { timeInMillis = epochMs }
    return if (now.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
               now.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)) {
        timeOnlyFormat.format(Date(epochMs))
    } else {
        dateTimeFormat.format(Date(epochMs))
    }
}
val EMOJI_LIST = listOf("😀", "😂", "🥰", "😎", "🤔", "😭", "😡", "👍", "🙏", "🔥", "✨", "❤️", "🎉", "💯", "👀", "🙌", "👏", "💋", "💔", "😜")
val BUILT_IN_WALLPAPERS = listOf(
    R.drawable.wp_purple_aesthetic_1,
    R.drawable.wp_purple_aesthetic_2,
    R.drawable.wp_telegram_style_black_theme,
    R.drawable.wp_telegram_style_darkblue,
    R.drawable.wp_telegram_style_light,
    R.drawable.wp_telegram_style_lightblue
)
val BUBBLE_COLORS = listOf(
    null, // Default material color
    0xFF007AFF, // iOS Blue
    0xFF34C759, // iOS Green
    0xFFFF9500, // Orange
    0xFFFF2D55, // Pink
    0xFF5856D6, // Purple
    0xFF5E5CE6, // Indigo
    0xFF30D158, // Light Green
    0xFFFF3B30, // Red
    0xFF1C1C1E, // Dark Gray
    0xFF2C2C2E  // Gray
)
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(chatId: String, remotePublicKey: String, remoteUserName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val prefs = context.getSharedPreferences("neveroff_prefs", android.content.Context.MODE_PRIVATE)
    val myId = SupabaseClient.client.auth.currentUserOrNull()?.id ?: prefs.getString("profile_id", "") ?: ""
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var authKey by remember { mutableStateOf<ByteArray?>(null) }
    var authKeyLoading by remember { mutableStateOf(true) }
    LaunchedEffect(remotePublicKey) {
        authKey = if (remotePublicKey.isNullOrBlank()) null
        else withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { CryptoManager.deriveSharedSecret(remotePublicKey) }.getOrNull()
        }
        authKeyLoading = false
    }
    if (authKeyLoading) {
        // Show loading while deriving key
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (authKey == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(remoteUserName) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "⚠️ Unable to open this chat",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The encryption key for this contact is invalid.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }
    val viewModel: ChatViewModel = viewModel(
        key = chatId,
        factory = ChatViewModelFactory(
            repository = ChatRepository(db, myId, authKey!!),
            chatId = chatId,
            otherPubKey = remotePublicKey
        )
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
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
        }
    }
    val localMessages by viewModel.messages.collectAsState()
    val chatDetails by viewModel.chatDetails.collectAsState()
    val selectedMessageIds by viewModel.selectedMessageIds.collectAsState()
    val bubbleTextSize by SettingsManager.bubbleTextSizeFlow.collectAsState()
    
    val displayUserName = chatDetails?.customName ?: remoteUserName
    
    val messages = remember(localMessages) {
        localMessages.map { entity ->
            val statusString = when (entity.status) {
                0 -> "sent"
                1 -> "delivered"
                2 -> "read"
                else -> "sent"
            }
            
            var textContent = entity.content
            var isViewOnce = false
            if (textContent.startsWith("[VIEW_ONCE]:")) {
                isViewOnce = true
                textContent = textContent.removePrefix("[VIEW_ONCE]:")
            }
            
            var replyToId: String? = null
            var replyToText: String? = null
            var replyToAuthorIsMe: Boolean? = null
            
            val replyMarkerIndex = textContent.lastIndexOf("::REPLY::")
            if (replyMarkerIndex != -1) {
                replyToId = textContent.substring(replyMarkerIndex + 9)
                textContent = textContent.substring(0, replyMarkerIndex)
                val repliedEntity = localMessages.find { it.id == replyToId }
                if (repliedEntity != null) {
                    var rText = repliedEntity.content
                    if (rText.startsWith("[VIEW_ONCE]:")) {
                        rText = rText.removePrefix("[VIEW_ONCE]:")
                    }
                    val rMarker = rText.lastIndexOf("::REPLY::")
                    replyToText = if (rMarker != -1) rText.substring(0, rMarker) else rText
                    replyToAuthorIsMe = repliedEntity.senderId == myId
                }
            }
            UiMessage(
                id = entity.id,
                text = textContent,
                isMine = entity.senderId == myId,
                status = if (entity.senderId != myId) "read" else statusString,
                createdAt = entity.createdAt,
                replyToId = replyToId,
                replyToText = replyToText,
                replyToAuthorIsMe = replyToAuthorIsMe,
                isViewOnce = isViewOnce
            )
        }.reversed()
    }
    
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val isAtBottom by remember { 
        derivedStateOf { 
            lazyListState.firstVisibleItemIndex <= 1 
        } 
    }
    
    var unreadBadgeCount by remember { mutableStateOf(0) }
    
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (isAtBottom) {
                lazyListState.animateScrollToItem(0)
                unreadBadgeCount = 0
            } else {
                val newestMessage = messages.first()
                if (!newestMessage.isMine) {
                    unreadBadgeCount++
                }
            }
        }
    }
    var firstUnreadMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(localMessages) {
        if (firstUnreadMessageId == null && localMessages.isNotEmpty()) {
            firstUnreadMessageId = localMessages.firstOrNull { it.senderId != myId && it.status == 1 }?.id
        }
    }
    val sharedPrefs = context.getSharedPreferences("chat_drafts", android.content.Context.MODE_PRIVATE)
    var text by remember { mutableStateOf(sharedPrefs.getString(chatId, "") ?: "") }
    
    LaunchedEffect(text) {
        if (text.isNotBlank()) {
            sharedPrefs.edit().putString(chatId, text).apply()
        } else {
            sharedPrefs.edit().remove(chatId).apply()
        }
    }
    
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var replyingToMessage by remember { mutableStateOf<UiMessage?>(null) }
    var fullScreenMediaUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var fullScreenMessage by remember { mutableStateOf<UiMessage?>(null) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var viewOnceMessage by remember { mutableStateOf<UiMessage?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDeleteMessagesDialog by remember { mutableStateOf(false) }
    var showCustomizationSheet by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showForwardSheet by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var activeLockScreenMode by remember { mutableStateOf<LockMode?>(null) }
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Set Contact Name") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setCustomName(renameInput.ifBlank { null })
                        showRenameDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    // Recording state
    var recordingMode by remember { mutableStateOf<com.neveroff.ui.components.RecordMode?>(null) }
    val mediaRecorderHelper = remember { com.neveroff.media.MediaRecorderHelper(context) }
    val cameraRecorderHelper = remember { com.neveroff.media.CameraRecorderHelper(context) }
    var remoteProfile by remember { mutableStateOf<Profile?>(null) }
    LaunchedEffect(chatDetails?.otherUserId) {
        val otherId = chatDetails?.otherUserId
        if (otherId != null) {
            try {
                remoteProfile = SupabaseClient.client.postgrest["profiles"]
                    .select { filter { eq("id", otherId) } }
                    .decodeSingle<Profile>()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    val defaultMyBubbleColor = MaterialTheme.colorScheme.primary
    val defaultOtherBubbleColor = MaterialTheme.colorScheme.secondaryContainer
    val globalMyBubbleColor = chatDetails?.myBubbleColor?.let { Color(it) } ?: defaultMyBubbleColor
    val globalOtherBubbleColor = chatDetails?.otherBubbleColor?.let { Color(it) } ?: defaultOtherBubbleColor
    val registryOwner = androidx.activity.compose.LocalActivityResultRegistryOwner.current
    val currentOnResult by rememberUpdatedState { uri: Uri? ->
        if (uri != null) {
            val path = ImageHelper.copyImageToInternalStorage(context, uri)
            if (path != null) {
                viewModel.updateCustomization(
                    wallpaperUri = path,
                    myBubbleColor = chatDetails?.myBubbleColor,
                    otherBubbleColor = chatDetails?.otherBubbleColor
                )
            }
        }
    }
    
    val key = remember { UUID.randomUUID().toString() }
    var pickMedia by remember { mutableStateOf<androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>?>(null) }
    
    DisposableEffect(registryOwner, key) {
        val contract = ActivityResultContracts.PickVisualMedia()
        val registered = registryOwner?.activityResultRegistry?.register(key, contract) { uri ->
            currentOnResult(uri)
        }
        pickMedia = registered
        onDispose {
            registered?.unregister()
        }
    }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    val attachmentOnMediaResult by rememberUpdatedState { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val mimeType = context.contentResolver.getType(uri) ?: ""
                val isVideo = mimeType.startsWith("video/")
                val prefix = if (isVideo) "FILE_VIDEO" else "IMAGE"
                val filePrefix = if (isVideo) "enc_vid_" else "enc_img_"
                
                val file = com.neveroff.crypto.CryptoManager.copyUriToCache(context, uri, filePrefix)
                if (file != null) {
                    viewModel.sendMediaMessage(prefix, file) {}
                }
            }
        }
    }
    val attachmentOnFileResult by rememberUpdatedState { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                // Get original filename
                var originalName = "document.file"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) {
                        originalName = cursor.getString(nameIndex)
                    }
                }
                
                val file = com.neveroff.crypto.CryptoManager.copyUriToCache(context, uri, "enc_file_")
                if (file != null) {
                    // Pass the original name in the prefix
                    viewModel.sendMediaMessage("FILE:$originalName", file) {}
                }
            }
        }
    }
    val attachMediaKey = remember { UUID.randomUUID().toString() }
    var pickAttachmentMedia by remember { mutableStateOf<androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>?>(null) }
    DisposableEffect(registryOwner, attachMediaKey) {
        val contract = ActivityResultContracts.PickVisualMedia()
        val registered = registryOwner?.activityResultRegistry?.register(attachMediaKey, contract) { uri ->
            attachmentOnMediaResult(uri)
        }
        pickAttachmentMedia = registered
        onDispose { registered?.unregister() }
    }
    val attachFileKey = remember { UUID.randomUUID().toString() }
    var pickAttachmentFile by remember { mutableStateOf<androidx.activity.result.ActivityResultLauncher<Array<String>>?>(null) }
    DisposableEffect(registryOwner, attachFileKey) {
        val contract = ActivityResultContracts.OpenDocument()
        val registered = registryOwner?.activityResultRegistry?.register(attachFileKey, contract) { uri ->
            attachmentOnFileResult(uri)
        }
        pickAttachmentFile = registered
        onDispose { registered?.unregister() }
    }
    val onlineUsersSet by com.neveroff.data.remote.PresenceManager.onlineUsers.collectAsState()
    val mediaProgressMap by viewModel.mediaProgress.collectAsState()
    val isOnline = chatDetails?.otherUserId?.let { id ->
        onlineUsersSet.contains(id)
    } ?: false
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showProfileDialog = true }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            val isSavedMessages = chatId == com.neveroff.util.Constants.SAVED_MESSAGES_ID
                            if (isSavedMessages) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF03A9F4)), // Light Blue
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Star,
                                        contentDescription = "Saved Messages",
                                        tint = Color(0xFFFFD700), // Gold
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            } else if (remoteProfile?.avatar_url != null) {
                                coil.compose.AsyncImage(
                                    model = remoteProfile?.avatar_url,
                                    contentDescription = "Avatar",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Text(
                                    text = remoteUserName.take(1).uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = displayUserName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isOnline) "в сети" else "был(а) недавно",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Обои и цвета") },
                            onClick = {
                                showMenu = false
                                showCustomizationSheet = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (chatDetails?.isLocked == true) "Отключить пароль" else "Заблокировать чат") },
                            onClick = {
                                showMenu = false
                                if (chatDetails?.isLocked == true) {
                                    activeLockScreenMode = LockMode.DISABLE
                                } else {
                                    activeLockScreenMode = LockMode.SETUP
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            onClick = {
                                showMenu = false
                                renameInput = chatDetails?.customName ?: ""
                                showRenameDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить чат", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                showDeleteDialog = true
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        activeLockScreenMode?.let { mode ->
            LockScreen(
                mode = mode,
                correctPinHash = chatDetails?.pinHash,
                onSuccess = { newPinHash ->
                    scope.launch {
                        if (mode == LockMode.SETUP && newPinHash != null) {
                            db.chatDao().updateChatLockState(chatId, true, newPinHash)
                            val existing = db.chatDao().getChatById(chatId)
                            if (existing == null) {
                                db.chatDao().insertChat(
                                    com.neveroff.data.local.ChatEntity(
                                        id = chatId,
                                        otherUserId = chatDetails?.otherUserId ?: "",
                                        otherUserName = chatDetails?.otherUserName ?: "",
                                        otherPubKey = chatDetails?.otherPubKey ?: "",
                                        lastMessage = chatDetails?.lastMessage,
                                        updatedAt = System.currentTimeMillis(),
                                        isLocked = true,
                                        pinHash = newPinHash
                                    )
                                )
                            } else {
                                db.chatDao().updateChatLockState(chatId, true, newPinHash)
                            }
                        } else if (mode == LockMode.DISABLE) {
                            db.chatDao().updateChatLockState(chatId, false, null)
                        }

colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        activeLockScreenMode?.let { mode ->
            LockScreen(
                mode = mode,
                correctPinHash = chatDetails?.pinHash,
                onSuccess = { newPinHash ->
                    scope.launch {
                        if (mode == LockMode.SETUP && newPinHash != null) {
                            db.chatDao().updateChatLockState(chatId, true, newPinHash)
                            val existing = db.chatDao().getChatById(chatId)
                            if (existing == null) {
                                db.chatDao().insertChat(
                                    com.neveroff.data.local.ChatEntity(
                                        id = chatId,
                                        otherUserId = chatDetails?.otherUserId ?: "",
                                        otherUserName = chatDetails?.otherUserName ?: "",
                                        otherPubKey = chatDetails?.otherPubKey ?: "",
                                        lastMessage = chatDetails?.lastMessage,
                                        updatedAt = System.currentTimeMillis(),
                                        isLocked = true,
                                        pinHash = newPinHash
                                    )
                                )
                            } else {
                                db.chatDao().updateChatLockState(chatId, true, newPinHash)
                            }
                        } else if (mode == LockMode.DISABLE) {
                            db.chatDao().updateChatLockState(chatId, false, null)
                        }
                    }
                    activeLockScreenMode = null
                },
                onCancel = {
                    activeLockScreenMode = null
                },
                onEraseChat = {
                    scope.launch {
                        db.messageDao().deleteMessagesByChatId(chatId)
                        db.chatDao().deleteChatById(chatId)
                        onBack()
                    }
                    activeLockScreenMode = null
                }
            )
        }
        
        Box(modifier = Modifier.fillMaxSize()) {
            // Wallpaper Background
            val wallpaperUri = chatDetails?.wallpaperUri
            if (!wallpaperUri.isNullOrEmpty()) {
                if (wallpaperUri.startsWith("gradient:")) {
                    val parts = wallpaperUri.split(":")
                    if (parts.size == 3) {
                        val startColor = Color(parts[1].toLongOrNull() ?: 0xFF000000)
                        val endColor = Color(parts[2].toLongOrNull() ?: 0xFF000000)
                        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(startColor, endColor))))
                    }
                } else {
                    val model: Any = if (wallpaperUri.startsWith("res:")) {
                        wallpaperUri.removePrefix("res:").toInt()
                    } else {
                        wallpaperUri
                    }
                    AsyncImage(
                        model = model,
                        contentDescription = "Chat Wallpaper",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // Slight dark overlay to make messages readable
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                }
            }
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (chatId != com.neveroff.util.Constants.SAVED_MESSAGES_ID && chatDetails?.customName == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Do you know him?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        TextButton(onClick = {
                            renameInput = ""
                            showRenameDialog = true
                        }) {
                            Text("Set a name")
                        }
                    }
                }
                // Realtime Debug Overlay (conditional)
                val isDebugEnabled by SettingsManager.debugPanelFlow.collectAsState()
                var showDebugLogs by remember { mutableStateOf(false) }
                val realtimeStatus by SupabaseClient.client.realtime.status.collectAsState(initial = io.github.jan.supabase.realtime.Realtime.Status.DISCONNECTED)
                var isRecordingLocked by remember { mutableStateOf(false) }
                
                val stopAndSendRecording = {
                    if (recordingMode == com.neveroff.ui.components.RecordMode.AUDIO) {
                        val file = mediaRecorderHelper.stopRecording()
                        if (file != null) {
                            viewModel.sendMediaMessage("VOICE", file) {}
                        }
                        recordingMode = null
                    } else {
                        cameraRecorderHelper.stopRecording()
                        // recordingMode = null will be set in the startRecording callback
                    }
                    isRecordingLocked = false
                }
                
                val cancelRecordingAction = {
                    if (recordingMode == com.neveroff.ui.components.RecordMode.AUDIO) {
                        mediaRecorderHelper.cancelRecording()
                    } else {
                        cameraRecorderHelper.cancelRecording()
                    }
                    recordingMode = null
                    isRecordingLocked = false
                }
                
                if (isDebugEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Realtime: $realtimeStatus",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (realtimeStatus == io.github.jan.supabase.realtime.Realtime.Status.CONNECTED) Color.Green else Color.Red
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showDebugLogs = !showDebugLogs }) {
                                    Text(if (showDebugLogs) "Скрыть логи" else "Логи")
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            com.neveroff.util.DebugLogger.log("Manual reset of Realtime...")
                                            SupabaseClient.client.realtime.disconnect()
                                            SupabaseClient.client.realtime.connect()
                                            com.neveroff.util.DebugLogger.log("Realtime reconnected manually")
                                        } catch (e: Exception) {
                                            com.neveroff.util.DebugLogger.log("Reset failed: ${e.message}")
                                        }
                                    }
                                }) {
                                    Text("Переподключить")
                                }
                            }
                        }
                        if (showDebugLogs) {
                            Box(modifier = Modifier.height(150.dp).fillMaxWidth()) {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(com.neveroff.util.DebugLogger.logs) { logLine ->
                                        Text(
                                            text = logLine,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        reverseLayout = true
                    ) {
                    items(messages, key = { it.id ?: UUID.randomUUID().toString() }) { msg ->
                        Column {
                            if (msg.id == firstUnreadMessageId) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = "Новые сообщения",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                            
                            val myBubbleColor = globalMyBubbleColor
                            val otherBubbleColor = globalOtherBubbleColor
                            val myContentColor = myBubbleColor.readableContentColor()
                            val otherContentColor = otherBubbleColor.readableContentColor()
                            val isCircleVideo = msg.text.startsWith("[VIDEO]")
                            val hasBubbleBackground = !isCircleVideo
                            val isSelected = selectedMessageIds.contains(msg.id ?: "")
                            val selectionModeActive = selectedMessageIds.isNotEmpty()
                            var offsetX by remember { mutableFloatStateOf(0f) }
                            val animatedOffsetX by androidx.compose.animation.core.animateFloatAsState(targetValue = offsetX, animationSpec = androidx.compose.animation.core.tween(150))
                            Box(modifier = Modifier.fillMaxWidth()) {
                                val iconAlpha = ((-offsetX) / 100f).coerceIn(0f, 1f)
                                val iconScale = 0.5f + 0.5f * iconAlpha
                                if (iconAlpha > 0.01f) {
                                    Icon(
                                        painter = painterResource(id = android.R.drawable.ic_menu_revert),
                                        contentDescription = "Reply",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 24.dp)
                                            .scale(iconScale)
                                            .alpha(iconAlpha)
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset { androidx.compose.ui.unit.IntOffset(animatedOffsetX.toInt(), 0) }
                                        .pointerInput(Unit) {
                                            detectHorizontalDragGestures(
                                                onDragEnd = {
                                                    if (offsetX < -120f) {
                                                        replyingToMessage = msg
                                                    }
                                                    offsetX = 0f
                                                },
                                                onDragCancel = { offsetX = 0f },
                                                onHorizontalDrag = { change, dragAmount ->
                                                    if (!selectionModeActive) {
                                                        if (dragAmount < 0 || offsetX < 0) {
                                                            offsetX += dragAmount * 0.4f
                                                            if (offsetX < -200f) offsetX = -200f
                                                            if (offsetX > 0f) offsetX = 0f
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        .combinedClickable(
                                            onClick = {
                                                if (selectionModeActive && msg.id != null) {
                                                    viewModel.toggleSelection(msg.id)
                                                }
                                            },
                                            onLongClick = {
                                                if (msg.id != null) viewModel.toggleSelection(msg.id)
                                            }
                                        )
                                        .scale(if (selectionModeActive) 0.95f else 1f)
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                        .padding(vertical = 2.dp)
                                ) {
                                if (selectionModeActive && !msg.isMine) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 8.dp).size(24.dp)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .padding(end = 8.dp)
                                                .size(24.dp)
                                                .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), CircleShape)
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 280.dp)
                                        .then(
                                            if (hasBubbleBackground) {
                                                Modifier.background(
                                                    color = if (msg.isMine) myBubbleColor else otherBubbleColor,
                                                    shape = RoundedCornerShape(
                                                        topStart = 16.dp,
                                                        topEnd = 16.dp,
                                                        bottomStart = if (msg.isMine) 16.dp else 4.dp,
                                                        bottomEnd = if (msg.isMine) 4.dp else 16.dp
                                                    )
                                                ).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
                                            } else {
                                                Modifier
                                            }
                                        )
                                ) {
                                Column {
                                    if (msg.replyToText != null) {
                                        val authorText = if (msg.replyToAuthorIsMe == true) "You" else displayUserName
                                        Box(
                                            modifier = Modifier
                                                .padding(bottom = 4.dp)
                                                .background(
                                                    color = Color.Black.copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Column {
                                                Text(
                                                    text = authorText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (msg.isMine) myContentColor else otherContentColor
                                                )
                                                Text(
                                                    text = msg.replyToText.take(40).let { if(it.length==40) "$it..." else it },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (msg.isMine) myContentColor.copy(alpha = 0.9f) else otherContentColor.copy(alpha = 0.9f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    if (msg.isViewOnce) {
                                        if (msg.isMine) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = msg.text,
                                                    color = myContentColor,
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = bubbleTextSize.sp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text("🔥")
                                            }
                                        } else {
                                            androidx.compose.material3.TextButton(
                                                onClick = { viewOnceMessage = msg },
                                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = otherContentColor)
                                            ) {
                                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Tap to view (2s)")
                                            }
                                        }
                                    } else if (msg.text.startsWith("[VOICE]") || msg.text.startsWith("[VIDEO]") || msg.text.startsWith("[IMAGE]") || msg.text.startsWith("[FILE:") || msg.text.startsWith("[FILE_VIDEO]")) {
                                        com.neveroff.ui.components.MediaMessagePlayer(
                                            messageText = msg.text,
                                            textColor = if (msg.isMine) myContentColor else otherContentColor,
                                            uploadProgress = mediaProgressMap[msg.id],
                                            onClickFullScreen = { uri ->
                                                fullScreenMediaUri = uri
                                                fullScreenMessage = msg
                                            }
                                        )
                                    } else {
                                        Text(
                                            text = msg.text,
                                            color = if (msg.isMine) myContentColor else otherContentColor,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = bubbleTextSize.sp)
                                        )
                                    }
                                    
                                    if (!isCircleVideo) {
                                        Spacer(Modifier.height(2.dp))
                                        Row(
                                            modifier = Modifier.align(Alignment.End),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            val msgContentColor = if (msg.isMine) myContentColor else otherContentColor
                                            Text(
                                                text = if (msg.createdAt > 0L) formatMessageTime(msg.createdAt) else "",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = msgContentColor.copy(alpha = 0.7f)
                                            )
                                            if (msg.isMine) {
                                                val tint = if (msg.status == "read") Color(0xFF34B7F1) else msgContentColor.copy(alpha = 0.7f)
                                                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = msg.status,
                                                        modifier = Modifier.size(14.dp),
                                                        tint = tint
                                                    )
                                                    if (msg.status == "delivered" || msg.status == "read") {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = msg.status,
                                                            modifier = Modifier.size(14.dp),
                                                            tint = tint
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Spacer(Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.End)
                                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                                .padding(horizontal = 8.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(
                                                text = if (msg.createdAt > 0L) formatMessageTime(msg.createdAt) else "",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White
                                            )
                                            if (msg.isMine) {
                                                val tint = if (msg.status == "read") Color(0xFF34B7F1) else Color.White
                                                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = msg.status,
                                                        modifier = Modifier.size(14.dp),
                                                        tint = tint
                                                    )
                                                    if (msg.status == "delivered" || msg.status == "read") {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = msg.status,
                                                            modifier = Modifier.size(14.dp),
                                                            tint = tint
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                                if (selectionModeActive && msg.isMine) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 8.dp).size(24.dp)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .size(24.dp)
                                                .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), CircleShape)
                                        )
                                }
                                }
                            }
                                } // End Box
                            } // End Column (msg)
                        } // End items
                    } // End LazyColumn
                    if (recordingMode == com.neveroff.ui.components.RecordMode.VIDEO) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            com.neveroff.ui.components.CircularCameraPreview(
                                cameraRecorderHelper = cameraRecorderHelper
                            )
                        }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = recordingMode == com.neveroff.ui.components.RecordMode.AUDIO,
                    enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut()
                ) {
                    com.neveroff.ui.components.AudioRecordingPanel()
                }
                val selectionModeActive = selectedMessageIds.isNotEmpty()
                if (selectionModeActive) {
                    // Selection Action Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { 
                            showDeleteMessagesDialog = true
                        }, enabled = !isDeleting) {
                            if (isDeleting) {
                                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        
                        Text(
                            text = "${selectedMessageIds.size} selected",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge
                        )
                        
                        Row {
                            val selectedMsgs = localMessages.filter { selectedMessageIds.contains(it.id) }
                            if (selectedMsgs.size == 1 && selectedMsgs.first().senderId == myId) {
                                IconButton(onClick = {
                                    val msgToEdit = selectedMsgs.first()
                                    editingMessageId = msgToEdit.id
                                    text = msgToEdit.content
                                    viewModel.clearSelection()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(onClick = { showForwardSheet = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Forward",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = {
                                val textToCopy = selectedMsgs.joinToString("\n") { it.content }
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(textToCopy))
                                viewModel.clearSelection()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Copy",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (isRecordingLocked) {
                    // Locked Toolbar
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = cancelRecordingAction) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Cancel Recording",
                                tint = Color.Red
                            )
                        }
                        
                        if (recordingMode == com.neveroff.ui.components.RecordMode.VIDEO) {
                            var duration by remember { mutableStateOf(0L) }
                            LaunchedEffect(Unit) {
                                while(true) {
                                    delay(1000)
                                    duration++
                                    if (duration >= 60) {
                                        stopAndSendRecording()
                                    }
                                }
                            }
                            val mins = duration / 60
                            val secs = duration % 60
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Red)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = String.format("%02d:%02d / 01:00", mins, secs),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text("Recording locked", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        
                        IconButton(
                            onClick = stopAndSendRecording,
                            modifier = Modifier.background(globalMyBubbleColor, androidx.compose.foundation.shape.CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                } else {
                    if (replyingToMessage != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Reply", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Replying to", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    text = replyingToMessage!!.text.take(50),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { replyingToMessage = null }) {
                                Icon(Icons.Default.Clear, contentDescription = "Cancel reply", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val mixedColor = androidx.compose.ui.graphics.lerp(globalMyBubbleColor, globalOtherBubbleColor, 0.5f)
                        val mixedContentColor = mixedColor.readableContentColor()
                        
                        Box {
                            IconButton(
                                onClick = { showAttachmentMenu = true },
                                modifier = Modifier.background(mixedColor.copy(alpha = 0.9f), CircleShape)
                            ) {
                                Icon(
                                    painter = painterResource(id = android.R.drawable.ic_menu_upload),
                                    contentDescription = "Вложения",
                                    tint = mixedContentColor
                                )
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = showAttachmentMenu,
                                onDismissRequest = { showAttachmentMenu = false }
                            ) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Галерея") },
                                    onClick = {
                                        showAttachmentMenu = false
                                        pickAttachmentMedia?.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Файл") },
                                    onClick = {
                                        showAttachmentMenu = false
                                        pickAttachmentFile?.launch(arrayOf("*/*"))
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        val shape = RoundedCornerShape(20.dp)
                        TextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                            placeholder = { Text(if (editingMessageId != null) "Edit message..." else "Message...") },
                            shape = shape,
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = mixedContentColor,
                                unfocusedTextColor = mixedContentColor,
                                focusedPlaceholderColor = mixedContentColor.copy(alpha = 0.7f),
                                unfocusedPlaceholderColor = mixedContentColor.copy(alpha = 0.7f),
                                cursorColor = mixedContentColor,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = mixedColor.copy(alpha = 0.9f),
                                unfocusedContainerColor = mixedColor.copy(alpha = 0.9f)
                            ),
                            maxLines = 4
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (editingMessageId != null) {
                            IconButton(
                                onClick = {
                                    if (text.isNotBlank()) {
                                        val msgText = text
                                        text = ""
                                        val replyId = replyingToMessage?.id
                                        replyingToMessage = null
                                        viewModel.editMessage(editingMessageId!!, msgText) { }
                                        editingMessageId = null
                                    }
                                },
                                modifier = Modifier.background(globalMyBubbleColor, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Confirm Edit",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        } else {
                            Box {
                                var showViewOnceMenu by remember { mutableStateOf(false) }
                                com.neveroff.ui.components.RecordButton(
                                    isTextBlank = text.isBlank(),
                                    isLocked = isRecordingLocked,
                                    buttonColor = globalMyBubbleColor,
                                    onSendText = {
                                        if (text.isNotBlank()) {
                                            val currentText = text
                                            text = ""
                                            val replyId = replyingToMessage?.id
                                            replyingToMessage = null
                                            viewModel.sendMessage(currentText, replyId) { success -> }
                                        }
                                    },
                                    onSendTextLongPress = {
                                        showViewOnceMenu = true
                                    },
                                    onStartRecording = { mode ->
                                recordingMode = mode
                                if (mode == com.neveroff.ui.components.RecordMode.AUDIO) {
                                    mediaRecorderHelper.startRecording()
                                } else {
                                    cameraRecorderHelper.startRecording { file ->
                                        recordingMode = null
                                        if (file != null) {
                                            viewModel.sendMediaMessage("VIDEO", file) {}
                                        }
                                    }
                                }
                            },
                            onLockRecording = {
                                isRecordingLocked = true
                            },
                            onStopRecording = stopAndSendRecording,
                            onCancelRecording = cancelRecordingAction
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = showViewOnceMenu,
                            onDismissRequest = { showViewOnceMenu = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Delete as seen (2s)") },
                                onClick = {
                                    showViewOnceMenu = false
                                    if (text.isNotBlank()) {
                                        val currentText = "[VIEW_ONCE]:$text"
                                        text = ""
                                        val replyId = replyingToMessage?.id
                                        replyingToMessage = null
                                        viewModel.sendMessage(currentText, replyId) { success -> }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null)
                                }
                            )
                        }
                        }
                    }
                }
            }
        }
    }
    
    if (showEmojiPicker) {
        ModalBottomSheet(onDismissRequest = { showEmojiPicker = false }) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(48.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(EMOJI_LIST) { emoji ->
                    Box(
                        modifier = Modifier.size(48.dp).clickable { 
                            text += emoji 
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    if (fullScreenMediaUri != null && fullScreenMessage != null) {
        androidx.activity.compose.BackHandler {
            fullScreenMediaUri = null
            fullScreenMessage = null
        }
        com.neveroff.ui.components.FullScreenMediaViewer(
            uri = fullScreenMediaUri!!,
            message = fullScreenMessage!!,
            onDismiss = { fullScreenMediaUri = null; fullScreenMessage = null },
            onReply = {
                replyingToMessage = fullScreenMessage
                fullScreenMediaUri = null
                fullScreenMessage = null
            },
            onDelete = {
                val id = fullScreenMessage!!.id
                if (id != null) {
                    viewModel.toggleSelection(id)
                    viewModel.deleteSelectedMessages { }
                }
                fullScreenMediaUri = null
                fullScreenMessage = null
            },
            onForward = {
                android.widget.Toast.makeText(context, "Forward not implemented yet", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }
    if (showForwardSheet) {
        val allChats by db.chatDao().getAllChats().collectAsState(initial = emptyList())
        var selectedChatsForForwarding by remember { mutableStateOf(setOf<String>()) }
        ModalBottomSheet(onDismissRequest = { showForwardSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Переслать сообщения", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    val savedMessagesChat = com.neveroff.data.local.ChatEntity(
                        id = com.neveroff.util.Constants.SAVED_MESSAGES_ID,
                        otherUserId = myId,
                        otherUserName = "Избранное",
                        otherPubKey = "",
                        lastMessage = "",
                        updatedAt = System.currentTimeMillis()
                    )
                    
                    val listToShow = listOf(savedMessagesChat) + allChats.filter { it.id != com.neveroff.util.Constants.SAVED_MESSAGES_ID }
                    
                    items(listToShow, key = { it.id }) { chat ->
                        val isSelected = selectedChatsForForwarding.contains(chat.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedChatsForForwarding = if (isSelected) {
                                        selectedChatsForForwarding - chat.id
                                    } else {
                                        selectedChatsForForwarding + chat.id
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(chat.customName ?: chat.otherUserName, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val messagesToForward = localMessages.filter { selectedMessageIds.contains(it.id) }.map { UiMessage(id = it.id, text = it.content, isMine = it.senderId == myId) }
                        viewModel.forwardMessages(messagesToForward, selectedChatsForForwarding.toList())
                        showForwardSheet = false
                        viewModel.clearSelection()
                        android.widget.Toast.makeText(context, "Переслано", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedChatsForForwarding.isNotEmpty()
                ) {
                    Text("Переслать")
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    if (showCustomizationSheet) {
        ModalBottomSheet(onDismissRequest = { showCustomizationSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                // Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    // Draw wallpaper
                    val wpUri = chatDetails?.wallpaperUri
                    if (wpUri != null) {
                        if (wpUri.startsWith("gradient:")) {
                            val parts = wpUri.split(":")
                            if (parts.size == 3) {
                                val sColor = Color(parts[1].toLongOrNull() ?: 0xFF000000)
                                val eColor = Color(parts[2].toLongOrNull() ?: 0xFF000000)
                                Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(sColor, eColor))))
                            }
                        } else {
                            val model: Any = if (wpUri.startsWith("res:")) {
                                wpUri.removePrefix("res:").toInt()
                            } else {
                                wpUri
                            }
                            AsyncImage(
                                model = model,
                                contentDescription = "Preview Wallpaper",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)))
                    }
                    
                    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    val previewMessages = listOf(
                        UiMessage("1", "Привет!", isMine = false),
                        UiMessage("2", "Привет! Как дела?", isMine = true),
                        UiMessage("3", "Всё отлично, спасибо!", isMine = false),
                        UiMessage("4", "Отлично, делаю настройки.", isMine = true),
                        UiMessage("5", "Круто! Удачи!", isMine = false)
                    )
                    
                    val pMyColor = chatDetails?.myBubbleColor?.let { Color(it) } ?: defaultMyBubbleColor
                    val pOtherColor = chatDetails?.otherBubbleColor?.let { Color(it) } ?: defaultOtherBubbleColor
                    
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(previewMessages) { msg ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start
                            ) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 200.dp)
                                        .background(
                                            color = if (msg.isMine) pMyColor else pOtherColor,
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = msg.text,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = bubbleTextSize.sp),
                                        color = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Размер текста: ${bubbleTextSize.toInt()}", style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.Slider(
                    value = bubbleTextSize,
                    onValueChange = { SettingsManager.setBubbleTextSize(it) },
                    valueRange = 12f..24f,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Цвет твоих сообщений", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BUBBLE_COLORS) { colorValue ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    color = colorValue?.let { Color(it) } ?: defaultMyBubbleColor,
                                    shape = CircleShape
                                )
                                .border(
                                    width = if (chatDetails?.myBubbleColor == colorValue) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    shape = CircleShape
                                )
                                .clickable {
                                    viewModel.updateCustomization(
                                        wallpaperUri = chatDetails?.wallpaperUri,
                                        myBubbleColor = colorValue,
                                        otherBubbleColor = chatDetails?.otherBubbleColor
                                    )
                                }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text("Цвет сообщений собеседника", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BUBBLE_COLORS) { colorValue ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    color = colorValue?.let { Color(it) } ?: defaultOtherBubbleColor,
                                    shape = CircleShape
                                )
                                .border(
                                    width = if (chatDetails?.otherBubbleColor == colorValue) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    shape = CircleShape
                                )
                                .clickable {
                                    viewModel.updateCustomization(
                                        wallpaperUri = chatDetails?.wallpaperUri,
                                        myBubbleColor = chatDetails?.myBubbleColor,
                                        otherBubbleColor = colorValue
                                    )
                                }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                Text("Темы чата", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(com.neveroff.ui.theme.ChatThemes.predefinedThemes) { theme ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp, 80.dp)
                                    .background(
                                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                            listOf(Color(theme.gradientStart), Color(theme.gradientEnd))
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        width = if (chatDetails?.myBubbleColor == theme.myBubbleColor) 2.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        viewModel.updateCustomization(
                                            wallpaperUri = "gradient:${theme.gradientStart}:${theme.gradientEnd}",
                                            myBubbleColor = theme.myBubbleColor,
                                            otherBubbleColor = theme.otherBubbleColor
                                        )
                                    }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(theme.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Удалить чат?") },
            text = { Text("Все сообщения будут удалены для обоих пользователей. Это действие нельзя отменить.") },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleting = true
                        viewModel.deleteChat { success ->
                            isDeleting = false
                            if (success) {
                                showDeleteDialog = false
                                onBack()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "Ошибка удаления",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !isDeleting
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onError,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Удалить")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    enabled = !isDeleting
                ) { Text("Отмена") }
            }
        )
    }
    if (showDeleteMessagesDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteMessagesDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.title_delete_messages)) },
            text = { Text(stringResource(R.string.confirm_delete_messages)) },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleting = true
                        viewModel.deleteSelectedMessages { success ->
                            isDeleting = false
                            if (success) {
                                showDeleteMessagesDialog = false
                            } else {
                                scope.launch {
                                    android.widget.Toast.makeText(context, "Failed to delete", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !isDeleting
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onError,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.action_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteMessagesDialog = false },
                    enabled = !isDeleting
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    var selectedAvatarUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val avatarLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val myId = SupabaseClient.client.auth.currentUserOrNull()?.id
            if (myId != null) {
                scope.launch {
                    try {
                        android.widget.Toast.makeText(context, "Начинаем загрузку...", android.widget.Toast.LENGTH_SHORT).show()
                        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { 
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } 
                        }
                        if (bytes != null) {
                            val fileName = "${java.util.UUID.randomUUID()}.jpg"
                            val bucket = SupabaseClient.client.storage["avatars"]
                            bucket.upload(fileName, bytes, upsert = true)
                            val publicUrl = bucket.publicUrl(fileName)
                            
                            SupabaseClient.client.postgrest["profiles"].update(
                                { set("avatar_url", publicUrl as String) }
                            ) {
                                filter { eq("id", myId) }
                            }
                            
                            android.widget.Toast.makeText(context, "Аватар обновлен!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        android.widget.Toast.makeText(context, "Ошибка: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    if (viewOnceMessage != null) {
        val activity = LocalContext.current as? android.app.Activity
        DisposableEffect(Unit) {
            activity?.window?.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        LaunchedEffect(viewOnceMessage) {
            kotlinx.coroutines.delay(2000L)
            viewOnceMessage?.id?.let { msgId ->
                viewModel.deleteMessageForEveryone(msgId)
            }
            viewOnceMessage = null
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = viewOnceMessage!!.text,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
    ProfileDialog(
        isVisible = showProfileDialog && chatDetails?.otherUserId != null,
        profileId = chatDetails?.otherUserId ?: "",
        onDismiss = { showProfileDialog = false },
        onPickAvatar = {
            avatarLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        selectedAvatarUri = selectedAvatarUri,
        onAvatarUploaded = {
            selectedAvatarUri = null
        }
    )
}
}