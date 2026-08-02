import os

content = """package com.neveroff.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neveroff.data.cache.ProfileCache
import com.neveroff.ui.components.MediaMessagePlayer
import com.neveroff.ui.viewmodels.ChatViewModel
import com.neveroff.ui.viewmodels.ChatViewModelFactory
import com.neveroff.util.BounceIconButton
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class UiMessage(
    val id: String,
    val text: String,
    val isMine: Boolean,
    val timestamp: Long,
    val status: Int,
    val replyToId: String? = null,
    val isTemp: Boolean = false,
    val hasPassword: Boolean = false,
    val isMedia: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: String,
    remotePublicKey: String,
    remoteUserName: String,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = com.neveroff.data.local.AppDatabase.getDatabase(context)
    val authKey = com.neveroff.crypto.CryptoManager.deriveSharedSecret(remotePublicKey)
    val myId = com.neveroff.data.remote.SupabaseClient.client.auth.currentUserOrNull()?.id ?: ""
    val repository = remember(chatId, myId, remotePublicKey) {
        com.neveroff.data.repository.ChatRepository(db, myId, authKey)
    }
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModelFactory(repository, chatId, remotePublicKey))
    val messages by viewModel.messages.collectAsState()
    val chatDetails by viewModel.chatDetails.collectAsState()
    
    val remoteProfile by produceState<Profile?>(initialValue = null, chatDetails?.otherUserId) {
        chatDetails?.otherUserId?.let { id -> value = ProfileCache.getProfile(id) }
    }
    val finalRemoteUserName = chatDetails?.otherUserName ?: remoteUserName

    var inputText by remember { mutableStateOf("") }
    var isTempMode by remember { mutableStateOf(false) }
    var isLockedMode by remember { mutableStateOf(false) }
    var replyToId by remember { mutableStateOf<String?>(null) }
    
    // UI mapping
    val uiMessages = messages.map { entity ->
        var rawText = entity.content
        val rId = if (rawText.contains("::REPLY::")) rawText.substringAfter("::REPLY::") else null
        if (rId != null) rawText = rawText.substringBefore("::REPLY::")

        val temp = rawText.startsWith("[TEMP]")
        if (temp) rawText = rawText.removePrefix("[TEMP]")

        var pwd = false
        if (rawText.startsWith("[PWD:")) {
            pwd = true
            rawText = rawText.substringAfter("]")
        }

        val media = rawText.startsWith("[IMAGE]") || rawText.startsWith("[VOICE]") || rawText.startsWith("[VIDEO]") || rawText.startsWith("IMG_") || rawText.startsWith("VOICE_")

        UiMessage(
            id = entity.id,
            text = rawText,
            isMine = entity.senderId == myId,
            timestamp = entity.createdAt,
            status = entity.status,
            replyToId = rId,
            isTemp = temp,
            hasPassword = pwd,
            isMedia = media
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!remoteProfile?.avatar_url.isNullOrEmpty()) {
                                coil.compose.SubcomposeAsyncImage(
                                    model = remoteProfile?.avatar_url,
                                    contentDescription = "Avatar",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    error = { Text(finalRemoteUserName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                                )
                            } else {
                                Text(finalRemoteUserName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = finalRemoteUserName, style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = { BounceIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        bottomBar = {
            Column {
                if (replyToId != null) {
                    val replyMsg = uiMessages.find { it.id == replyToId }
                    if (replyMsg != null) {
                        Row(modifier = Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Reply to ${if (replyMsg.isMine) "You" else finalRemoteUserName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(if (replyMsg.isMedia) "Media message" else if (replyMsg.hasPassword) "Locked message" else replyMsg.text, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { replyToId = null }) { Icon(Icons.Default.Close, "Cancel Reply") }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(percent = 50)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BounceIconButton(onClick = { /* Media Attachment TODO */ }) { Icon(Icons.Default.Add, "Attach") }
                    BounceIconButton(onClick = { isLockedMode = !isLockedMode }) { Icon(Icons.Default.Lock, "Lock", tint = if (isLockedMode) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    BounceIconButton(onClick = { isTempMode = !isTempMode }) { Icon(Icons.Default.Timer, "Temp", tint = if (isTempMode) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        placeholder = { Text("Message") }
                    )
                    if (inputText.isNotBlank()) {
                        BounceIconButton(onClick = {
                            var finalMsg = inputText
                            if (isLockedMode) finalMsg = "[PWD:0000]$finalMsg" // Stub for password dialog, will implement later
                            if (isTempMode) finalMsg = "[TEMP]$finalMsg"
                            if (replyToId != null) finalMsg = "$finalMsg::REPLY::$replyToId"
                            
                            viewModel.sendMessage(finalMsg) { if (it) { inputText = ""; replyToId = null } }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            reverseLayout = true
        ) {
            items(uiMessages.reversed(), key = { it.id }) { msg ->
                MessageBubble(msg, uiMessages, finalRemoteUserName, onReply = { replyToId = it.id }, onDelete = { viewModel.deleteMessageForEveryone(it) })
            }
        }
    }
}

@Composable
fun MessageBubble(
    msg: UiMessage,
    allMessages: List<UiMessage>,
    remoteName: String,
    onReply: () -> Unit,
    onDelete: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isPasswordUnlocked by remember { mutableStateOf(false) }
    var passwordDialogOpen by remember { mutableStateOf(false) }
    var pwdInput by remember { mutableStateOf("") }
    
    // Liquid Animation
    var appeared by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.5f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )
    val alpha by animateFloatAsState(targetValue = if (appeared) 1f else 0f, animationSpec = tween(300))
    
    LaunchedEffect(Unit) { appeared = true }

    // Disappearing logic
    var isBlinking by remember { mutableStateOf(false) }
    val blinkAlpha by animateFloatAsState(targetValue = if (isBlinking) 0f else 1f, animationSpec = tween(200))
    
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

    var offsetX by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).scale(scale).alpha(alpha),
        contentAlignment = if (msg.isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp)
                .offset(x = offsetX.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (offsetX > 50f) onReply(); offsetX = 0f },
                        onDragCancel = { offsetX = 0f }
                    ) { change, dragAmount ->
                        if (!msg.isMine && dragAmount > 0) offsetX = (offsetX + dragAmount).coerceAtMost(100f)
                    }
                }
                .clip(RoundedCornerShape(percent = 50))
                .background(if (msg.isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .alpha(if (msg.isTemp && !msg.isMine) blinkAlpha else 1f)
        ) {
            // Reply UI
            if (msg.replyToId != null) {
                val replyMsg = allMessages.find { it.id == msg.replyToId }
                if (replyMsg != null) {
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(Color.Black.copy(alpha = 0.2f)).padding(8.dp)) {
                        Text(replyMsg.text, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            if (msg.hasPassword && !isPasswordUnlocked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, "Locked", tint = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Locked Message", color = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
                }
                TextButton(onClick = { passwordDialogOpen = true }) { Text("Unlock", color = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary) }
            } else if (msg.isMedia) {
                MediaMessagePlayer(
                    messageText = msg.text,
                    textColor = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    onClickFullScreen = {}
                )
            } else {
                Text(msg.text, color = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }

    if (passwordDialogOpen) {
        Dialog(onDismissRequest = { passwordDialogOpen = false }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Enter Password")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = pwdInput, onValueChange = { pwdInput = it }, singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { isPasswordUnlocked = true; passwordDialogOpen = false }) { Text("Unlock") }
                }
            }
        }
    }
}
"""

with open("/Users/denilarsanov/Documents/NeverOff/app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
