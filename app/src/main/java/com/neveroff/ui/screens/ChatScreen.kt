package com.neveroff.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.neveroff.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neveroff.data.cache.ProfileCache
import com.neveroff.media.CameraRecorderHelper
import com.neveroff.media.MediaRecorderHelper
import com.neveroff.ui.components.AudioRecordingPanel
import com.neveroff.ui.components.CircularCameraPreview
import com.neveroff.ui.components.MediaMessagePlayer
import com.neveroff.ui.components.RecordButton
import com.neveroff.ui.components.RecordMode
import com.neveroff.ui.viewmodels.ChatViewModel
import com.neveroff.ui.viewmodels.ChatViewModelFactory
import com.neveroff.util.BounceIconButton
import com.neveroff.util.SettingsManager
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.pow

fun isBubbleColorDark(color: Color): Boolean = (color.red * 0.299 + color.green * 0.587 + color.blue * 0.114) < 0.6

data class ChatTheme(
    val name: String,
    val backgroundGradient: List<Color>,
    val myBubbleColor: Color,
    val otherBubbleColor: Color,
    val inputBarColor: Color,
    val iconTint: Color
)

val AvailableChatThemes = listOf(
    ChatTheme(
        name = "Default",
        backgroundGradient = listOf(Color.Transparent, Color.Transparent),
        myBubbleColor = Color(0xFF3F51B5),
        otherBubbleColor = Color(0xFF009688),
        inputBarColor = Color.Transparent,
        iconTint = Color.Gray
    ),
    ChatTheme(
        name = "Vintage",
        backgroundGradient = listOf(Color(0xFFEFEBE9), Color(0xFFD7CCC8)),
        myBubbleColor = Color(0xFF8D6E63),
        otherBubbleColor = Color(0xFFA1887F),
        inputBarColor = Color(0x80D7CCC8),
        iconTint = Color(0xFF5D4037)
    ),
    ChatTheme(
        name = "Modernism",
        backgroundGradient = listOf(Color(0xFF232526), Color(0xFF414345)),
        myBubbleColor = Color(0xFF1E88E5),
        otherBubbleColor = Color(0xFF424242),
        inputBarColor = Color(0x80232526),
        iconTint = Color(0xFF90CAF9)
    ),
    ChatTheme(
        name = "Love",
        backgroundGradient = listOf(Color(0xFFFF99CC), Color(0xFFFF6699)),
        myBubbleColor = Color(0xFFD81B60),
        otherBubbleColor = Color(0xFFF06292),
        inputBarColor = Color(0x80FF6699),
        iconTint = Color(0xFF880E4F)
    ),
    ChatTheme(
        name = "Ocean",
        backgroundGradient = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)),
        myBubbleColor = Color(0xFF0277BD),
        otherBubbleColor = Color(0xFF00ACC1),
        inputBarColor = Color(0x800F2027),
        iconTint = Color(0xFF4FC3F7)
    ),
    ChatTheme(
        name = "Forest",
        backgroundGradient = listOf(Color(0xFF134E5E), Color(0xFF71B280)),
        myBubbleColor = Color(0xFF2E7D32),
        otherBubbleColor = Color(0xFF558B2F),
        inputBarColor = Color(0x80134E5E),
        iconTint = Color(0xFFAED581)
    )
)

data class UiMessage(
    val id: String,
    val text: String,
    val isMine: Boolean,
    val timestamp: Long,
    val status: Int,
    val replyToId: String? = null,
    val isTemp: Boolean = false,
    val hasPassword: Boolean = false,
    val isMedia: Boolean = false,
    val isForwarded: Boolean = false,
    /**
     * Set when the whole message is a single `n.me/<username>` link, in which
     * case the bubble renders a channel card instead of the raw text.
     */
    val channelLinkUsername: String? = null,
    /**
     * Set when this message is a channel post someone forwarded into the chat.
     *
     * Distinct from [channelLinkUsername], which means "this message is nothing
     * but a link". A forwarded post keeps its own text and gains the channel as
     * an attribution footer, so both the content and its source are visible.
     */
    val fromChannel: String? = null
)

/**
 * Marker appended to a forwarded channel post, naming the channel it came from.
 *
 * Carried inside the message body rather than in a column because the body is
 * the only part of a message the server cannot read — adding a `from_channel`
 * column would publish, in plaintext, which channels a user reads and forwards.
 * That is precisely the metadata end-to-end encryption exists to withhold.
 */
const val FORWARDED_CHANNEL_MARKER = "::FWDCH::"

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    chatId: String,
    remotePublicKey: String,
    remoteUserName: String,
    onBack: () -> Unit,
    onOpenChannel: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val db = com.neveroff.data.local.AppDatabase.getDatabase(context)
    val authKey = com.neveroff.crypto.CryptoManager.deriveSharedSecret(remotePublicKey)
    // Falls back to the cached profile id: with no network the auth session may not resolve,
    // and a blank id makes every message compare as "not mine" (whole chat looks incoming).
    val myId = com.neveroff.util.CurrentUser.id(context)
    val repository = remember(chatId, myId, remotePublicKey) {
        com.neveroff.data.repository.ChatRepository(db, myId, authKey)
    }
    // Shared channel links pasted into this chat resolve through here.
    val channelRepository = remember(myId) {
        com.neveroff.data.repository.ChannelRepository(db, myId)
    }
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModelFactory(repository, chatId, remotePublicKey))
    val messages by viewModel.messages.collectAsState()
    val chatDetails by viewModel.chatDetails.collectAsState()
    val mediaProgress by viewModel.mediaProgress.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

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
    
    val remoteProfile by produceState<Profile?>(initialValue = null, chatDetails?.otherUserId) {
        chatDetails?.otherUserId?.let { id -> value = ProfileCache.getProfile(id) }
    }
    val finalRemoteUserName = chatDetails?.otherUserName ?: remoteUserName

    var showMediaGalleryPicker by rememberSaveable { mutableStateOf(false) }

    var inputText by rememberSaveable { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var isTempMode by rememberSaveable { mutableStateOf(false) }
    var showSendOptionsMenu by remember { mutableStateOf(false) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val listScope = rememberCoroutineScope()
    var isRecordingLocked by remember { mutableStateOf(false) }
    var fullScreenMessage by remember { mutableStateOf<UiMessage?>(null) }
    var fullScreenMediaUri by remember { mutableStateOf<Uri?>(null) }
    val audioHelper = remember { MediaRecorderHelper(context) }
    val cameraHelper = remember { CameraRecorderHelper(context) }

    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAttachMenu by rememberSaveable { mutableStateOf(false) }

    // Restrictions issued from the moderation console. A mute arrives as
    // blocks_sending rather than as a feature list, and EnforcementState folds
    // both into the same answer, so the composer has one flag to consult rather
    // than two that have to be kept in agreement.
    val sendBlocked = com.neveroff.ui.components.isFeatureRestricted(
        com.neveroff.data.remote.EnforcementState.Feature.SEND_MESSAGE
    )
    val voiceBlocked = sendBlocked || com.neveroff.ui.components.isFeatureRestricted(
        com.neveroff.data.remote.EnforcementState.Feature.SEND_VOICE_NOTE
    )
    val videoBlocked = sendBlocked || com.neveroff.ui.components.isFeatureRestricted(
        com.neveroff.data.remote.EnforcementState.Feature.SEND_VIDEO
    )
    val imageBlocked = sendBlocked || com.neveroff.ui.components.isFeatureRestricted(
        com.neveroff.data.remote.EnforcementState.Feature.SEND_IMAGE
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                val path = com.neveroff.util.ImageHelper.copyImageToInternalStorage(context, uri, "chat_files")
                if (path != null) {
                    viewModel.sendMediaMessage("IMAGE", File(path)) {}
                }
            }
        }
    )
    var activeLockMode by remember { mutableStateOf<LockMode?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    
    val textSize by SettingsManager.bubbleTextSizeFlow.collectAsState()
    val chatTheme by SettingsManager.chatThemeFlow.collectAsState()
    val wallpaperBrightness by SettingsManager.wallpaperBrightnessFlow.collectAsState()
    val bouncinessFactor by SettingsManager.bouncinessFlow.collectAsState()
    val stretchinessEnabled by SettingsManager.stretchinessFlow.collectAsState()
    val allAnimationsEnabled by SettingsManager.allAnimationsFlow.collectAsState()
    
    val currentTheme = AvailableChatThemes.find { it.name == chatTheme } ?: AvailableChatThemes.first()

    var isLockedMode by rememberSaveable { mutableStateOf(false) }
    var replyToId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedMessages = remember { mutableStateListOf<String>() }
    val isSelectionMode = selectedMessages.isNotEmpty()

    // §6.1 reporting. All null when no report is in progress. The evidence
    // field carries the *decrypted* message text: this device is the only
    // party that can produce it, and the dialog asks before it is shared.
    var reportTargetType by remember {
        mutableStateOf<com.neveroff.data.remote.ReportRepository.TargetType?>(null)
    }
    var reportTargetId by remember { mutableStateOf("") }
    var reportTargetLabel by remember { mutableStateOf("") }
    var reportEvidence by remember { mutableStateOf<String?>(null) }

    // Dialogs & Forwarding
    var showForwardDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    
    // UI mapping
    val uiMessages = messages.map { entity ->
        var rawText = entity.content
        var isFwd = false
        if (rawText.startsWith("[FWD]")) {
            isFwd = true
            rawText = rawText.removePrefix("[FWD]")
        }

        // Extracted before ::REPLY:: so a forwarded post that is also a reply
        // has its channel stripped from the tail first, rather than the reply
        // id swallowing the marker.
        val fromChannel = if (rawText.contains(FORWARDED_CHANNEL_MARKER)) {
            rawText.substringAfterLast(FORWARDED_CHANNEL_MARKER).takeIf { it.isNotBlank() }
        } else null
        if (fromChannel != null) rawText = rawText.substringBeforeLast(FORWARDED_CHANNEL_MARKER)

        val rId = if (rawText.contains("::REPLY::")) rawText.substringAfterLast("::REPLY::") else null
        if (rId != null) rawText = rawText.substringBeforeLast("::REPLY::")

        val temp = rawText.startsWith("[TEMP]")
        if (temp) rawText = rawText.removePrefix("[TEMP]")

        var pwd = false
        if (rawText.startsWith("[PWD:")) {
            pwd = true
            rawText = rawText.substringAfter("]")
        }

        val media = rawText.startsWith("[IMAGE]") || rawText.startsWith("[VOICE]") || rawText.startsWith("[VIDEO]") || rawText.startsWith("IMG_") || rawText.startsWith("VOICE_")

        // Only a message that is *nothing but* a channel link becomes a card —
        // a link mentioned mid-sentence stays part of the sentence.
        val channelUsername = if (media || pwd) null else {
            com.neveroff.data.model.ChannelLinkScheme.soleUsernameIn(rawText)
        }

        UiMessage(
            id = entity.id,
            text = rawText,
            isMine = entity.senderId == myId,
            timestamp = entity.createdAt,
            status = entity.status,
            replyToId = rId,
            isTemp = temp,
            hasPassword = pwd,
            isMedia = media,
            isForwarded = isFwd,
            channelLinkUsername = channelUsername,
            fromChannel = fromChannel
        )
    }

    // Screenshots are blocked only while a temporary message from the other
    // person is on screen — those are meant to disappear, and a screenshot
    // defeats that. Own temporary messages do not count: you can always
    // screenshot what you yourself sent. SecureScreen clears the flag when this
    // goes false and again on dispose, so the restriction cannot leak into the
    // rest of the app.
    val hasIncomingTemporaryMessage = uiMessages.any { it.isTemp && !it.isMine }
    com.neveroff.ui.components.SecureScreen(active = hasIncomingTemporaryMessage)

    val onlineUsersFromPresence by com.neveroff.data.remote.PresenceManager.onlineUsers.collectAsState()

    LaunchedEffect(uiMessages.size) {
        if (uiMessages.isNotEmpty()) {
            listState.animateScrollToItem(0)
            viewModel.markAsRead()
        }
    }

    Scaffold(
        topBar = {
          Column {
            if (isSelectionMode) {
                // Stage 1 Selection & Forwarding Bar
                TopAppBar(
                    title = { Text("${selectedMessages.size} selected", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { selectedMessages.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_selection))
                        }
                    },
                    actions = {
                        // Reporting is per-message: a case is about one piece of
                        // content, and bundling five unrelated messages into one
                        // report would make it unactionable.
                        if (selectedMessages.size == 1) {
                            IconButton(onClick = {
                                val id = selectedMessages.first()
                                val message = uiMessages.firstOrNull { it.id == id }
                                if (message != null && !message.isMine) {
                                    reportTargetType =
                                        com.neveroff.data.remote.ReportRepository.TargetType.MESSAGE
                                    reportTargetId = id
                                    reportTargetLabel = "Message from $finalRemoteUserName"
                                    reportEvidence = message.text
                                    selectedMessages.clear()
                                } else {
                                    com.neveroff.ui.components.AppToast.show(
                                        "You can only report messages from other people"
                                    )
                                }
                            }) {
                                Icon(Icons.Default.Flag, contentDescription = stringResource(R.string.report_message))
                            }
                        }
                        IconButton(onClick = { showForwardDialog = true }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.action_forward))
                        }
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_range))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        val isOnline = onlineUsersFromPresence.contains(chatDetails?.otherUserId)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showProfileId = chatDetails?.otherUserId }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .then(
                                        if (isOnline) {
                                            Modifier.border(2.dp, Color(0xFF4CAF50).copy(alpha = 0.6f), CircleShape)
                                        } else {
                                            Modifier
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!remoteProfile?.avatar_url.isNullOrEmpty()) {
                                    coil.compose.SubcomposeAsyncImage(
                                        model = remoteProfile?.avatar_url,
                                        contentDescription = stringResource(R.string.avatar),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        error = { Text(finalRemoteUserName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                                    )
                                } else {
                                    Text(finalRemoteUserName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = finalRemoteUserName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isOnline) "Online" else "Offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    },
                    navigationIcon = { BounceIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.theme_settings)) },
                                    onClick = {
                                        showMenu = false
                                        showSettingsSheet = true
                                    }
                                )
                                val isLocked = chatDetails?.isLocked == true
                                DropdownMenuItem(
                                    text = { Text(if (isLocked) "Disable Chat Lock" else "Enable Chat Lock") },
                                    onClick = {
                                        showMenu = false
                                        activeLockMode = if (isLocked) LockMode.DISABLE else LockMode.SETUP
                                    }
                                )
                                // Reporting the person rather than one message:
                                // for a pattern of behaviour across a
                                // conversation, no single message is the case.
                                chatDetails?.otherUserId?.let { otherId ->
                                    DropdownMenuItem(
                                        text = { Text("Report $finalRemoteUserName") },
                                        onClick = {
                                            showMenu = false
                                            reportTargetType = com.neveroff.data.remote
                                                .ReportRepository.TargetType.USER
                                            reportTargetId = otherId
                                            reportTargetLabel = finalRemoteUserName
                                            // No single message to volunteer here.
                                            reportEvidence = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
            // Sits directly under the header so an auto-download stays visible (and stoppable)
            // without leaving the conversation.
            com.neveroff.ui.components.UpdateProgressStrip()
          }
        },
        bottomBar = {
            Column(modifier = Modifier.imePadding()) {
                if (replyToId != null) {
                    val replyMsg = uiMessages.find { it.id == replyToId }
                    if (replyMsg != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = if (currentTheme.name == "Default") MaterialTheme.colorScheme.surfaceVariant else currentTheme.inputBarColor,
                            tonalElevation = 1.dp,
                            shadowElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Reply to ${if (replyMsg.isMine) "You" else finalRemoteUserName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Text(if (replyMsg.isMedia) "Media message" else if (replyMsg.hasPassword) "Locked message" else replyMsg.text, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = { replyToId = null }) { Icon(Icons.Default.Close, "Cancel Reply") }
                            }
                        }
                    }
                }
                // Standing condition, so a banner rather than a dialog — a dialog
                // would have to be dismissed every time the chat is reopened.
                if (sendBlocked) {
                    com.neveroff.ui.components.SendingRestrictedBanner(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                        .shadow(4.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
                        .background(
                            color = if (currentTheme.name == "Default") MaterialTheme.colorScheme.surfaceVariant else currentTheme.inputBarColor,
                            shape = RoundedCornerShape(24.dp)
                        ).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        BounceIconButton(
                            onClick = {
                                if (imageBlocked && videoBlocked) {
                                    com.neveroff.ui.components.AppToast.show(
                                        context.getString(
                                            R.string.restriction_blocked_toast,
                                            context.getString(R.string.feature_send_image)
                                        )
                                    )
                                } else {
                                    showAttachMenu = true
                                }
                            },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .alpha(if (imageBlocked && videoBlocked) 0.38f else 1f)
                        ) { Icon(Icons.Default.Add, "Attach") }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.gallery)) },
                                leadingIcon = { Icon(Icons.Default.AccountBox, contentDescription = null) },
                                onClick = {
                                    showAttachMenu = false
                                    showMediaGalleryPicker = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files)) },
                                leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) },
                                onClick = {
                                    showAttachMenu = false
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    }

                    if (isRecordingAudio) {
                        AudioRecordingPanel(modifier = Modifier.weight(1f))
                    } else if (isRecordingVideo) {
                        Text(stringResource(R.string.recording_video), modifier = Modifier.weight(1f).padding(16.dp))
                    } else {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { if (it.length <= 4000) inputText = it },
                            modifier = Modifier.weight(1f),
                            shape = CircleShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            placeholder = {
                                Text(
                                    if (sendBlocked) {
                                        stringResource(R.string.restriction_sending_banner)
                                    } else "Message"
                                )
                            },
                            // Material greys a disabled field itself, which is
                            // exactly the affordance wanted: visibly present,
                            // visibly unusable, no focus, no keyboard.
                            enabled = !sendBlocked,
                            singleLine = false,
                            maxLines = 4
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Box {
                        RecordButton(
                            isTextBlank = inputText.isBlank(),
                            isLocked = isLockedMode,
                            sendEnabled = !isSending && !sendBlocked,
                            buttonColor = if (isTempMode) Color(0xFFFF5722) else if (currentTheme.name == "Default") MaterialTheme.colorScheme.primary else currentTheme.iconTint,
                            onSendText = {
                                var finalMsg = inputText
                                if (replyToId != null) finalMsg = "$finalMsg::REPLY::$replyToId"
                                if (isTempMode) finalMsg = "[TEMP]$finalMsg"
                                if (isLockedMode) finalMsg = "[PWD:0000]$finalMsg"
                                inputText = ""
                                replyToId = null
                                isSending = true
                                viewModel.sendMessage(finalMsg) { isSending = false }
                            },
                            onSendTextLongPress = {
                                showSendOptionsMenu = true
                            },
                            onStartRecording = { mode ->
                                // Checked here as well as on the button: a long
                                // press can begin a recording without the send
                                // path being touched, so gating sendEnabled
                                // alone would leave voice notes wide open.
                                val blockedFeature = when {
                                    mode == RecordMode.AUDIO && voiceBlocked ->
                                        com.neveroff.data.remote.EnforcementState.Feature.SEND_VOICE_NOTE
                                    mode != RecordMode.AUDIO && videoBlocked ->
                                        com.neveroff.data.remote.EnforcementState.Feature.SEND_VIDEO
                                    else -> null
                                }
                                if (blockedFeature != null) {
                                    com.neveroff.ui.components.AppToast.show(
                                        context.getString(
                                            R.string.restriction_blocked_toast,
                                            context.getString(
                                                if (blockedFeature == com.neveroff.data.remote.EnforcementState.Feature.SEND_VOICE_NOTE)
                                                    R.string.feature_send_voice_note
                                                else R.string.feature_send_video
                                            )
                                        )
                                    )
                                } else if (mode == RecordMode.AUDIO) {
                                    if (audioHelper.startRecording() != null) {
                                        isRecordingAudio = true
                                    } else {
                                        com.neveroff.ui.components.AppToast.error("Could not start recording")
                                    }
                                } else {
                                    isRecordingVideo = true
                                    cameraHelper.startRecording { file ->
                                        if (file != null) {
                                            viewModel.sendMediaMessage("VIDEO", file) {}
                                        } else {
                                            com.neveroff.ui.components.AppToast.error("Recording too short")
                                        }
                                        isRecordingVideo = false
                                    }
                                }
                            },
                            onStopRecording = {
                                if (isRecordingAudio) {
                                    isRecordingAudio = false
                                    val file = audioHelper.stopRecording()
                                    if (file != null) {
                                        viewModel.sendMediaMessage("VOICE", file) {}
                                    } else {
                                        com.neveroff.ui.components.AppToast.error("Hold to record a voice message")
                                    }
                                }
                                if (isRecordingVideo) {
                                    cameraHelper.stopRecording()
                                }
                                isRecordingLocked = false
                            },
                            onCancelRecording = {
                                if (isRecordingAudio) {
                                    isRecordingAudio = false
                                    audioHelper.cancelRecording()
                                }
                                if (isRecordingVideo) {
                                    isRecordingVideo = false
                                    cameraHelper.cancelRecording()
                                }
                                isRecordingLocked = false
                            },
                            onLockRecording = {
                                isRecordingLocked = true
                            }
                        )

                        DropdownMenu(
                            expanded = showSendOptionsMenu,
                            onDismissRequest = { showSendOptionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.send_normally)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                                onClick = {
                                    showSendOptionsMenu = false
                                    var finalMsg = inputText
                                    if (replyToId != null) finalMsg = "$finalMsg::REPLY::$replyToId"
                                    if (isLockedMode) finalMsg = "[PWD:0000]$finalMsg"
                                    inputText = ""
                                    replyToId = null
                                    isSending = true
                                    viewModel.sendMessage(finalMsg) { isSending = false }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.send_as_temporary_disappearing)) },
                                leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null, tint = Color(0xFFFF5722)) },
                                onClick = {
                                    showSendOptionsMenu = false
                                    var finalMsg = inputText
                                    if (replyToId != null) finalMsg = "$finalMsg::REPLY::$replyToId"
                                    finalMsg = "[TEMP]$finalMsg"
                                    if (isLockedMode) finalMsg = "[PWD:0000]$finalMsg"
                                    inputText = ""
                                    replyToId = null
                                    isSending = true
                                    viewModel.sendMessage(finalMsg) { isSending = false }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isTempMode) "Disable Temporary Mode" else "Enable Temporary Mode") },
                                leadingIcon = { Icon(Icons.Default.HourglassTop, contentDescription = null) },
                                onClick = {
                                    showSendOptionsMenu = false
                                    isTempMode = !isTempMode
                                    com.neveroff.ui.components.AppToast.show(
                                        if (isTempMode) "Temporary mode enabled ⏱️" else "Temporary mode disabled"
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        val backgroundBrush = if (currentTheme.backgroundGradient.size >= 2 && currentTheme.backgroundGradient[0] != Color.Transparent) {
            androidx.compose.ui.graphics.Brush.verticalGradient(currentTheme.backgroundGradient)
        } else {
            androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.background)
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Wallpaper layer with adjustable brightness
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBrush)
                    .graphicsLayer { alpha = wallpaperBrightness }
            )
            if (isRecordingVideo) {
                // Centred and large: while recording, the viewfinder is the whole point of the
                // screen, so it sits where the user is already looking rather than tucked into
                // a corner at thumbnail size.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularCameraPreview(
                        cameraRecorderHelper = cameraHelper,
                        // Matches the record button so the placeholder reads as that button grown.
                        seedColor = if (currentTheme.name == "Default") MaterialTheme.colorScheme.primary else currentTheme.iconTint
                    )
                }
            }
            if (uiMessages.isEmpty() && isSyncing) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(com.neveroff.R.string.no_messages),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                reverseLayout = true
            ) {
                // Reversed data plus reverseLayout: index 0 is drawn at the
                // bottom, so the message visually ABOVE index i is index i+1 —
                // the older one. That is what the day pill compares against.
                // Getting this backwards puts the separator under the last
                // message of a day instead of over the first.
                val orderedForDisplay = uiMessages.reversed()
                itemsIndexed(orderedForDisplay, key = { _, m -> m.id }) { index, msg ->
                    Box(modifier = Modifier.animateItemPlacement()) {
                      Column {
                        if (com.neveroff.ui.components.startsNewDay(
                                msg.timestamp,
                                orderedForDisplay.getOrNull(index + 1)?.timestamp
                            )
                        ) {
                            com.neveroff.ui.components.DaySeparator(msg.timestamp)
                        }
                        MessageBubble(
                            msg = msg, 
                            allMessages = uiMessages, 
                            remoteName = finalRemoteUserName, 
                            onReply = { replyToId = msg.id },
                            onReplyClick = { rId ->
                                val idx = uiMessages.reversed().indexOfFirst { it.id == rId }
                                if (idx >= 0) {
                                    listScope.launch { listState.animateScrollToItem(idx) }
                                }
                            },
                            onDelete = { viewModel.deleteMessageForEveryone(msg.id ?: "") },
                            onFullScreen = { m, uri -> fullScreenMessage = m; fullScreenMediaUri = uri },
                            onRetry = { id -> viewModel.retryMessage(id) },
                            uploadProgress = msg.id?.let { mediaProgress[it] },
                            isSelected = selectedMessages.contains(msg.id),
                            isSelectionMode = isSelectionMode,
                            theme = currentTheme,
                            textSize = textSize,
                            bouncinessFactor = bouncinessFactor,
                            stretchinessEnabled = stretchinessEnabled,
                            allAnimationsEnabled = allAnimationsEnabled,
                            onSelect = {
                                if (selectedMessages.contains(msg.id)) selectedMessages.remove(msg.id)
                                else selectedMessages.add(msg.id)
                            },
                            channelRepository = channelRepository,
                            onOpenChannel = onOpenChannel
                        )
                      }
                    }
                }
            }
            }
        }
    }

    // Stage 1 Range Delete Dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.delete_messages)) },
            // "Delete for me" was removed: deleting only your own copy leaves the
            // message on the other device, so the person doing the deleting
            // believes it is gone when it is not. On a messenger people reach
            // for delete precisely when that belief matters, and a delete that
            // quietly does less than it appears to is worse than no delete.
            text = {
                Text(
                    "Delete ${selectedMessages.size} message(s) for everyone in this chat? " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = selectedMessages.toList()
                    selectedMessages.clear()
                    showDeleteConfirmDialog = false
                    listScope.launch {
                        val anyFailed = toDelete.map { id -> repository.deleteMessageForEveryone(id) }.any { !it }
                        if (anyFailed) {
                            com.neveroff.ui.components.AppToast.error("Some messages couldn't be deleted for everyone")
                        }
                    }
                }) {
                    Text(stringResource(R.string.delete_for_everyone), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Stage 1 Forward Dialog
    if (showForwardDialog) {
        val chatListState by viewModel.chatDetails.collectAsState()
        var availableChats by remember { mutableStateOf<List<com.neveroff.data.local.ChatEntity>>(emptyList()) }
        LaunchedEffect(Unit) {
            db.chatDao().getAllChats().collect { availableChats = it }
        }
        val selectedForwardTargets = remember { mutableStateListOf<String>() }

        AlertDialog(
            onDismissRequest = { showForwardDialog = false },
            title = { Text(stringResource(R.string.forward_to_2)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    availableChats.forEach { chat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectedForwardTargets.contains(chat.id)) selectedForwardTargets.remove(chat.id)
                                    else selectedForwardTargets.add(chat.id)
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Checkbox(
                                checked = selectedForwardTargets.contains(chat.id),
                                onCheckedChange = { checked ->
                                    if (checked) selectedForwardTargets.add(chat.id)
                                    else selectedForwardTargets.remove(chat.id)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(chat.otherUserName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedForwardTargets.isNotEmpty(),
                    onClick = {
                        val messagesToFwd = uiMessages.filter { selectedMessages.contains(it.id) }
                        selectedMessages.clear()
                        showForwardDialog = false
                        viewModel.forwardMessages(messagesToFwd, selectedForwardTargets.toList())
                    }
                ) {
                    Text("Forward (${selectedForwardTargets.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForwardDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (fullScreenMessage != null && fullScreenMediaUri != null) {
        val msg = fullScreenMessage!!
        com.neveroff.ui.components.FullScreenMediaViewer(
            uri = fullScreenMediaUri!!,
            message = msg,
            onDismiss = { fullScreenMessage = null },
            onReply = { replyToId = msg.id; fullScreenMessage = null },
            onDelete = {
                viewModel.deleteMessageForEveryone(msg.id) { success ->
                    if (!success) {
                        com.neveroff.ui.components.AppToast.error("Couldn't delete for everyone — try again")
                    }
                }
                fullScreenMessage = null
            },
            onForward = {}
        )
    }

    com.neveroff.ui.screens.ProfileDialog(
        isVisible = showProfileId != null,
        profileId = showProfileId ?: "",
        onDismiss = { showProfileId = null },
        onPickAvatar = null,
        selectedAvatarUri = null,
        onAvatarUploaded = {}
    )

    // §6.1 report flow. Rendered here rather than inside the menus so it
    // survives the menu closing underneath it.
    reportTargetType?.let { type ->
        com.neveroff.ui.components.ReportDialog(
            targetType = type,
            targetId = reportTargetId,
            targetLabel = reportTargetLabel,
            availableEvidence = reportEvidence,
            onDismiss = {
                reportTargetType = null
                reportTargetId = ""
                reportTargetLabel = ""
                reportEvidence = null
            }
        )
    }

    if (showMediaGalleryPicker) {
        com.neveroff.ui.components.MediaGalleryPickerDialog(
            onDismiss = { showMediaGalleryPicker = false },
            onSend = { items ->
                showMediaGalleryPicker = false
                listScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    for (item in items) {
                        try {
                            when {
                                item.editedBitmap != null -> {
                                    val bytes = com.neveroff.util.ImageHelper.compressBitmap(item.editedBitmap)
                                    val file = File(context.cacheDir, "${java.util.UUID.randomUUID()}.jpg")
                                    file.writeBytes(bytes)
                                    viewModel.sendMediaMessage("IMAGE", file) {}
                                }
                                item.source.isVideo -> {
                                    val path = com.neveroff.util.ImageHelper.copyImageToInternalStorage(context, item.source.uri, "chat_media")
                                    if (path != null) viewModel.sendMediaMessage("VIDEO", File(path)) {}
                                }
                                else -> {
                                    val path = com.neveroff.util.ImageHelper.copyImageToInternalStorage(context, item.source.uri, "chat_media")
                                    if (path != null) viewModel.sendMediaMessage("IMAGE", File(path)) {}
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        )
    }

    if (activeLockMode != null) {
        LockScreen(
            mode = activeLockMode!!,
            correctPinHash = chatDetails?.pinHash,
            correctPinSalt = chatDetails?.pinSalt,
            onSuccess = { newPinHash, newPinSalt ->
                val isLocked = activeLockMode == LockMode.SETUP
                listScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    db.chatDao().updateChatLockState(chatId, isLocked, newPinHash, newPinSalt)
                }
                activeLockMode = null
            },
            onCancel = { activeLockMode = null },
            onEraseChat = {
                listScope.launch {
                    db.messageDao().deleteMessagesByChatId(chatId)
                    db.chatDao().deleteChatById(chatId)
                    onBack()
                }
                activeLockMode = null
            }
        )
    }

    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).verticalScroll(rememberScrollState())
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(
                        if (currentTheme.backgroundGradient.size >= 2 && currentTheme.backgroundGradient[0] != Color.Transparent) androidx.compose.ui.graphics.Brush.verticalGradient(currentTheme.backgroundGradient)
                        else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceVariant)
                    ).padding(16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.align(Alignment.Start).clip(RoundedCornerShape(20.dp)).background(currentTheme.otherBubbleColor).padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(stringResource(R.string.hello), color = if (isBubbleColorDark(currentTheme.otherBubbleColor)) Color.White else Color.Black, fontSize = textSize.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(20.dp)).background(currentTheme.myBubbleColor).padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(stringResource(R.string.hey_how_are_you), color = if (isBubbleColorDark(currentTheme.myBubbleColor)) Color.White else Color.Black, fontSize = textSize.sp)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                Text("Text Size: ${textSize.toInt()}", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = textSize,
                    onValueChange = { SettingsManager.setBubbleTextSize(it) },
                    valueRange = 10f..30f
                )

                Spacer(modifier = Modifier.height(24.dp))
                Text(stringResource(R.string.chat_themes), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.for_this_chat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(AvailableChatThemes.size) { index ->
                        val t = AvailableChatThemes[index]
                        val brush = if (t.backgroundGradient.size >= 2 && t.backgroundGradient[0] != Color.Transparent) {
                            androidx.compose.ui.graphics.Brush.verticalGradient(t.backgroundGradient)
                        } else {
                            androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceVariant)
                        }
                        
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { SettingsManager.setChatTheme(t.name) }) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp, 80.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(brush)
                                    .border(
                                        width = if (currentTheme.name == t.name) 2.dp else 0.dp,
                                        color = if (currentTheme.name == t.name) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                            ) {
                                Box(modifier = Modifier.padding(4.dp).align(Alignment.CenterStart).size(30.dp, 12.dp).clip(RoundedCornerShape(4.dp)).background(t.otherBubbleColor))
                                Box(modifier = Modifier.padding(4.dp).align(Alignment.CenterEnd).size(30.dp, 12.dp).clip(RoundedCornerShape(4.dp)).background(t.myBubbleColor))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(t.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(stringResource(R.string.wallpaper_brightness), style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = wallpaperBrightness,
                    onValueChange = { SettingsManager.setWallpaperBrightness(it) },
                    valueRange = 0.2f..1.0f
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    msg: UiMessage,
    allMessages: List<UiMessage>,
    remoteName: String,
    onReply: () -> Unit,
    onReplyClick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onFullScreen: (UiMessage, Uri) -> Unit,
    onRetry: (String) -> Unit = {},
    uploadProgress: Float? = null,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onSelect: () -> Unit = {},
    theme: ChatTheme,
    textSize: Float,
    bouncinessFactor: Float = 0.5f,
    stretchinessEnabled: Boolean = true,
    allAnimationsEnabled: Boolean = true,
    speedMultiplier: Float = 1f,
    demoDragTarget: Float? = null,
    onUserDragActive: ((Boolean) -> Unit)? = null,
    /** Supplied only where channel links can appear; the previews leave it null. */
    channelRepository: com.neveroff.data.repository.ChannelRepository? = null,
    onOpenChannel: (String) -> Unit = {}
) {
    var isPasswordUnlocked by remember { mutableStateOf(false) }
    var passwordDialogOpen by remember { mutableStateOf(false) }
    var pwdInput by remember { mutableStateOf("") }

    // Liquid Animation with customizable bounciness
    var appeared by remember { mutableStateOf(false) }
    val springDamping = if (allAnimationsEnabled) (0.8f - (bouncinessFactor * 0.4f)).coerceIn(0.3f, 0.9f) else 1.0f

    val scaleX by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.6f,
        animationSpec = spring(dampingRatio = springDamping, stiffness = 220f * speedMultiplier),
        label = "scaleX"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.6f,
        animationSpec = spring(dampingRatio = springDamping, stiffness = 160f * speedMultiplier),
        label = "scaleY"
    )
    val translationY by animateFloatAsState(
        targetValue = if (appeared) 0f else 250f,
        animationSpec = spring(dampingRatio = springDamping, stiffness = 180f * speedMultiplier),
        label = "transY"
    )
    val startX = if (msg.isMine) 120f else -120f
    val translationX by animateFloatAsState(
        targetValue = if (appeared) 0f else startX,
        animationSpec = spring(dampingRatio = springDamping, stiffness = 110f * speedMultiplier),
        label = "transX"
    )
    val bubbleAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh * speedMultiplier),
        label = "alpha"
    )

    LaunchedEffect(Unit) { appeared = true }

    // Disappearing logic
    var isBlinking by remember { mutableStateOf(false) }
    val blinkAlpha by animateFloatAsState(targetValue = if (isBlinking) 0f else 1f, animationSpec = tween((200 / speedMultiplier).toInt()), label = "blink")
    
    if (msg.isTemp && !msg.isMine) {
        LaunchedEffect(Unit) {
            delay(2000)
            isBlinking = true
            delay(200)
            isBlinking = false
            delay(200)
            isBlinking = true
            delay(200)
            onDelete(msg.id)
        }
    }

    // Stage 2 Non-linear Physics Drag
    var rawDragX by remember { mutableFloatStateOf(0f) }
    var isUserDragging by remember { mutableStateOf(false) }
    if (demoDragTarget != null && !isUserDragging) {
        val demoOffset by animateFloatAsState(
            targetValue = demoDragTarget,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = 90f * speedMultiplier),
            label = "demoDrag"
        )
        rawDragX = demoOffset
    }
    val dampedOffsetX = (rawDragX.sign * (rawDragX.absoluteValue.toDouble().pow(0.75)).toFloat()).coerceIn(-100f, 100f)

    // Calculate background luminance for contrast color
    val bg = if (msg.isMine) theme.myBubbleColor else theme.otherBubbleColor
    val isBgDark = (bg.red * 0.299 + bg.green * 0.587 + bg.blue * 0.114) < 0.6
    val contentTextColor = if (isBgDark) Color.White else Color.Black
    val labelColor = contentTextColor.copy(alpha = 0.65f)

    // Subtle top-left highlight so the bubble reads as a soft, lit surface rather than a flat fill
    val bubbleBrush = remember(bg, isBgDark) {
        val highlight = androidx.compose.ui.graphics.lerp(bg, Color.White, if (isBgDark) 0.14f else 0.35f)
        androidx.compose.ui.graphics.Brush.linearGradient(listOf(highlight, bg))
    }

    // Reply icon alpha based on drag
    val replyIconAlpha by animateFloatAsState(
        targetValue = (dampedOffsetX / 40f).coerceIn(0f, 1f),
        animationSpec = tween((100 / speedMultiplier).toInt()),
        label = "replyIcon"
    )

    // Animated stretch during drag (silicone squeeze)
    val stretchScaleX by animateFloatAsState(
        targetValue = if (stretchinessEnabled && allAnimationsEnabled && rawDragX > 0) 1f + (dampedOffsetX / 500f).coerceIn(0f, 0.08f) else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 300f * speedMultiplier),
        label = "stretchX"
    )
    val stretchScaleY by animateFloatAsState(
        targetValue = if (stretchinessEnabled && allAnimationsEnabled && rawDragX > 0) 1f - (dampedOffsetX / 600f).coerceIn(0f, 0.06f) else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 300f * speedMultiplier),
        label = "stretchY"
    )

    /** Round video note — rendered bare (ring + pill) rather than inside a rectangular bubble. */
    val isCircleNote = msg.text.startsWith("[VIDEO]")

    // Voice-note scrubbing: aiming slowly grows the bubble so the timeline is easier to hit,
    // and dragging past either end rubber-bands it sideways.
    var scrub by remember { mutableStateOf(com.neveroff.ui.components.VoiceScrubState()) }
    // Kept small deliberately: the bubble grows in place, so anything larger starts overlapping
    // the messages above it instead of just being easier to aim at.
    val scrubMagnify by animateFloatAsState(
        targetValue = if (allAnimationsEnabled) 1f + scrub.magnify * 0.07f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 260f * speedMultiplier),
        label = "scrubMagnify"
    )
    val scrubStretchX by animateFloatAsState(
        targetValue = if (stretchinessEnabled && allAnimationsEnabled) {
            1f + (kotlin.math.abs(scrub.stretch) / 260f).coerceIn(0f, 0.14f)
        } else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 300f * speedMultiplier),
        label = "scrubStretchX"
    )
    val scrubTranslationX by animateFloatAsState(
        targetValue = if (allAnimationsEnabled) scrub.stretch * 0.6f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f * speedMultiplier),
        label = "scrubTranslationX"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .combinedClickable(
                onClick = { if (isSelectionMode) onSelect() },
                onLongClick = { onSelect() }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection mode: round checkbox on the left
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect() },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Bubble content area
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = if (msg.isMine) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            // Reply icon visible during swipe
            if (dampedOffsetX > 5f && !isSelectionMode) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.reply),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = replyIconAlpha),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .size(20.dp)
                        .alpha(replyIconAlpha)
                )
            }

            Column(
                modifier = Modifier
                    .widthIn(max = if (isSelectionMode) 260.dp else 290.dp)
                    .offset(x = dampedOffsetX.dp)
                    .pointerInput(isSelectionMode) {
                        if (!isSelectionMode) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    isUserDragging = true
                                    onUserDragActive?.invoke(true)
                                },
                                onDragEnd = {
                                    // Recompute from the live rawDragX state here rather than
                                    // closing over dampedOffsetX: this pointerInput coroutine
                                    // is launched once and never restarts while the keys are
                                    // unchanged, so a captured local val would stay frozen at
                                    // whatever it was on the first composition (0f) and the
                                    // threshold check would never pass.
                                    val finalOffset = (rawDragX.sign * (rawDragX.absoluteValue.toDouble().pow(0.75)).toFloat()).coerceIn(-100f, 100f)
                                    if (finalOffset > 40f) onReply()
                                    rawDragX = 0f
                                    isUserDragging = false
                                    onUserDragActive?.invoke(false)
                                },
                                onDragCancel = {
                                    rawDragX = 0f
                                    isUserDragging = false
                                    onUserDragActive?.invoke(false)
                                }
                            ) { _, dragAmount ->
                                // Rightward only. Only a right drag past the
                                // threshold triggers a reply, but the bubble
                                // used to follow a leftward drag just as far
                                // and then spring back having done nothing —
                                // an affordance that moves and means nothing,
                                // which reads as a broken app rather than as a
                                // gesture that does not exist.
                                rawDragX = (rawDragX + dragAmount * 0.5f).coerceAtLeast(0f)
                            }
                        }
                    }
                    .graphicsLayer(
                        scaleX = if (allAnimationsEnabled) scaleX * stretchScaleX * scrubMagnify * scrubStretchX else 1.0f,
                        scaleY = if (allAnimationsEnabled) scaleY * stretchScaleY * scrubMagnify else 1.0f,
                        translationX = if (allAnimationsEnabled) translationX + scrubTranslationX else 0f,
                        translationY = if (allAnimationsEnabled) translationY else 0f,
                        alpha = bubbleAlpha * (if (msg.isTemp && !msg.isMine) blinkAlpha else 1f),
                        transformOrigin = TransformOrigin(if (msg.isMine) 0.9f else 0.1f, 1f)
                    )
                    // A round video note is its own shape — a square bubble behind it just boxes
                    // in a circle. It gets a thin ring instead, and its metadata moves to a pill
                    // underneath (see below), so nothing needs a rectangular backdrop.
                    .then(
                        if (isCircleNote) Modifier
                        else Modifier
                            .shadow(3.dp, shape = RoundedCornerShape(18.dp), ambientColor = bg.copy(alpha = 0.4f), spotColor = bg.copy(alpha = 0.4f))
                            .clip(RoundedCornerShape(18.dp))
                            .background(bubbleBrush)
                            .padding(12.dp)
                    ),
                horizontalAlignment = if (msg.isMine) Alignment.End else Alignment.Start
            ) {
                // Temporary Message Badge
                if (msg.isTemp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = stringResource(R.string.temporary_message),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.temporary),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }

                // Stage 1 Forwarded Badge
                if (msg.isForwarded) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(R.string.forward_sent),
                            tint = labelColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.forward_sent),
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }

                // Stage 2 Square Reply Box
                if (msg.replyToId != null) {
                    val replyMsg = allMessages.find { it.id == msg.replyToId }
                    if (replyMsg != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable { onReplyClick(msg.replyToId) },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.15f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = if (replyMsg.isMine) "You" else remoteName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = contentTextColor
                                )
                                Text(
                                    text = replyMsg.text,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = contentTextColor.copy(alpha = 0.8f)
                                )
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = stringResource(R.string.original_message),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = contentTextColor.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }

                if (msg.hasPassword && !isPasswordUnlocked) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, "Locked", tint = contentTextColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.locked_message), color = contentTextColor)
                    }
                    TextButton(onClick = { passwordDialogOpen = true }) { Text(stringResource(R.string.unlock), color = contentTextColor) }
                } else if (isCircleNote) {
                    // Thin ring hugging the note, standing in for the bubble outline.
                    Box(
                        modifier = Modifier
                            .border(1.5.dp, bg.copy(alpha = 0.9f), CircleShape)
                            .padding(3.dp)
                    ) {
                        MediaMessagePlayer(
                            messageText = msg.text,
                            textColor = contentTextColor,
                            uploadProgress = uploadProgress,
                            onClickFullScreen = { uri -> onFullScreen(msg, uri) },
                            onScrubStateChange = { scrub = it }
                        )
                    }
                } else if (msg.isMedia) {
                    MediaMessagePlayer(
                        messageText = msg.text,
                        textColor = contentTextColor,
                        uploadProgress = uploadProgress,
                        onClickFullScreen = { uri -> onFullScreen(msg, uri) },
                        onScrubStateChange = { scrub = it }
                    )
                } else if (msg.channelLinkUsername != null && channelRepository != null) {
                    com.neveroff.ui.components.ChannelLinkCard(
                        username = msg.channelLinkUsername,
                        repository = channelRepository,
                        contentColor = contentTextColor,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onOpenChannel = onOpenChannel
                    )
                } else {
                    // Inline time/status: if text is short enough, put time on same row
                    val timeStr = formatTime(msg.timestamp)
                    val isShortText = msg.fromChannel == null &&
                        msg.text.length < 20 && !msg.text.contains("\n")

                    if (isShortText) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(msg.text, color = contentTextColor, fontSize = textSize.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (msg.isForwarded) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = stringResource(R.string.forward_sent),
                                        tint = labelColor,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                }
                                Text(
                                    text = timeStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = labelColor,
                                    fontSize = 9.sp
                                )
                                if (msg.isMine) {
                                    Spacer(modifier = Modifier.width(3.dp))
                                    StatusTicks(
                                        status = msg.status,
                                        color = labelColor,
                                        modifier = if (msg.status < 0 && !msg.isMedia) Modifier.clickable { onRetry(msg.id ?: "") } else Modifier
                                    )
                                }
                            }
                        }
                    } else {
                        Text(msg.text, color = contentTextColor, fontSize = textSize.sp)
                    }
                }

                // A forwarded channel post carries its source at the foot of the
                // bubble, rendered with the same card an attached channel link
                // gets. Attribution belongs with the content: a post that
                // travels into a private chat without saying where it came from
                // is indistinguishable from something the sender wrote.
                if (msg.fromChannel != null && channelRepository != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    com.neveroff.ui.components.ChannelLinkCard(
                        username = msg.fromChannel,
                        repository = channelRepository,
                        contentColor = contentTextColor,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onOpenChannel = onOpenChannel
                    )
                }

                // Separate time row for long messages / media / locked. Round notes get the same
                // information, but as a floating pill under the circle since they have no bubble
                // for it to sit inside.
                // A channel card is never "short text", even though its link is:
                // it needs the standalone time row underneath, not an inline one.
                // A forwarded post is never "short text" either: the card below
                // it needs the standalone time row, not one tucked inline.
                val isShortTextMsg = !msg.isMedia && !msg.hasPassword &&
                    msg.channelLinkUsername == null && msg.fromChannel == null &&
                    msg.text.length < 20 && !msg.text.contains("\n")
                if (!isShortTextMsg || msg.isMedia || (msg.hasPassword && !isPasswordUnlocked)) {
                    Row(
                        modifier = Modifier
                            .align(if (msg.isMine) Alignment.End else Alignment.Start)
                            .padding(top = if (isCircleNote) 6.dp else 4.dp)
                            .then(
                                if (isCircleNote) Modifier
                                    .clip(CircleShape)
                                    .background(bg.copy(alpha = 0.85f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                else Modifier
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (msg.isForwarded) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = labelColor,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                        }
                        Text(
                            text = formatTime(msg.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor,
                            fontSize = 9.sp
                        )
                        if (msg.isMine) {
                            Spacer(modifier = Modifier.width(4.dp))
                            StatusTicks(
                                status = msg.status,
                                color = labelColor,
                                modifier = if (msg.status < 0 && !msg.isMedia) Modifier.clickable { onRetry(msg.id ?: "") } else Modifier
                            )
                        }
                    }
                }
            }
        }
    }

    if (passwordDialogOpen) {
        Dialog(onDismissRequest = { passwordDialogOpen = false }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.enter_password))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = pwdInput, onValueChange = { pwdInput = it }, singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { isPasswordUnlocked = true; passwordDialogOpen = false }) { Text(stringResource(R.string.unlock)) }
                }
            }
        }
    }
}

val Float.sign: Float get() = if (this < 0) -1f else 1f
val Float.absoluteValue: Float get() = if (this < 0) -this else this
