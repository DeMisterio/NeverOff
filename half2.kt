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