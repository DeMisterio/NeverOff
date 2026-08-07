package com.neveroff.ui.screens

import com.neveroff.R
import com.neveroff.util.toUserMessage

import androidx.compose.ui.res.stringResource

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neveroff.data.model.Channel
import com.neveroff.data.model.ChannelLinkScheme
import com.neveroff.data.model.SocialPlatform
import com.neveroff.data.model.isValidChannelUsername
import com.neveroff.data.model.sanitizeChannelUsername
import com.neveroff.data.repository.ChannelRepository
import com.neveroff.ui.components.ChannelAvatar
import com.neveroff.ui.components.ImageEditorDialog
import com.neveroff.ui.components.SocialPlatformIcon
import com.neveroff.ui.components.brandColor
import com.neveroff.util.ImageHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One editable social link row in the creation form. */
private data class LinkDraft(
    val id: Long,
    val platform: SocialPlatform,
    val url: String = ""
)

private enum class UsernameState { EMPTY, TOO_SHORT, CHECKING, AVAILABLE, TAKEN }

/**
 * Two-step channel creation: details first (name, picture, description, social
 * links), then settings (visibility and link). The steps are separate because
 * the second one asks a question — public or private — that changes what the
 * first one's answers mean.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChannelScreen(
    repository: ChannelRepository,
    onBack: () -> Unit,
    onCreated: (channelId: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var step by rememberSaveable { mutableIntStateOf(0) }

    // Step 1 state
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingAvatarUri by remember { mutableStateOf<Uri?>(null) }
    var nextLinkId by rememberSaveable { mutableLongStateOf(1L) }
    val linkDrafts = remember { mutableStateListOf<LinkDraft>() }
    var showPlatformPicker by remember { mutableStateOf(false) }

    // Step 2 state
    var isPublic by rememberSaveable { mutableStateOf(true) }
    var username by rememberSaveable { mutableStateOf("") }
    var usernameState by remember { mutableStateOf(UsernameState.EMPTY) }
    var isCreating by remember { mutableStateOf(false) }

    val avatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pendingAvatarUri = uri }

    // Debounced availability check so the field doesn't hammer the server on
    // every keystroke, and never reports on a value the user has moved past.
    LaunchedEffect(username, isPublic) {
        if (!isPublic) {
            usernameState = UsernameState.EMPTY
            return@LaunchedEffect
        }
        when {
            username.isEmpty() -> usernameState = UsernameState.EMPTY
            !isValidChannelUsername(username) -> usernameState = UsernameState.TOO_SHORT
            else -> {
                usernameState = UsernameState.CHECKING
                delay(450)
                val available = repository.isUsernameAvailable(username)
                usernameState = if (available) UsernameState.AVAILABLE else UsernameState.TAKEN
            }
        }
    }

    val canContinue = name.trim().length >= 2
    val canCreate = canContinue && !isCreating &&
        (!isPublic || usernameState == UsernameState.AVAILABLE)

    pendingAvatarUri?.let { uri ->
        ImageEditorDialog(
            imageUri = uri,
            // Square crop: channel pictures render as rounded squares everywhere.
            circularCrop = false,
            onCancel = { pendingAvatarUri = null },
            onConfirm = { bitmap ->
                avatarBitmap = bitmap
                pendingAvatarUri = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (step == 0) "New channel" else "Channel settings",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (step == 0) onBack() else step = 0 }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            StepIndicator(step = step, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(280)) { -it / 3 } + fadeOut(tween(180)))
                    } else {
                        (slideInHorizontally(tween(280)) { -it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(180)))
                    }
                },
                label = "createChannelStep",
                modifier = Modifier.weight(1f)
            ) { currentStep ->
                if (currentStep == 0) {
                    ChannelDetailsStep(
                        name = name,
                        onNameChange = { if (it.length <= 64) name = it },
                        description = description,
                        onDescriptionChange = { if (it.length <= 500) description = it },
                        avatarBitmap = avatarBitmap,
                        onPickAvatar = {
                            avatarLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        linkDrafts = linkDrafts,
                        onLinkChanged = { id, url ->
                            val index = linkDrafts.indexOfFirst { it.id == id }
                            if (index >= 0) linkDrafts[index] = linkDrafts[index].copy(url = url)
                        },
                        onRemoveLink = { id -> linkDrafts.removeAll { it.id == id } },
                        onAddLink = { showPlatformPicker = true }
                    )
                } else {
                    ChannelSettingsStep(
                        isPublic = isPublic,
                        onVisibilityChange = { isPublic = it },
                        username = username,
                        onUsernameChange = { username = sanitizeChannelUsername(it) },
                        usernameState = usernameState
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        if (step == 0) {
                            step = 1
                        } else {
                            isCreating = true
                            scope.launch {
                                val avatarBytes = avatarBitmap?.let { ImageHelper.compressBitmap(it) }
                                val result = repository.createChannel(
                                    name = name,
                                    description = description,
                                    username = username.takeIf { isPublic },
                                    isPublic = isPublic,
                                    avatarBytes = avatarBytes,
                                    socialLinks = linkDrafts
                                        .filter { it.url.isNotBlank() }
                                        .map { it.platform to it.url }
                                )
                                isCreating = false
                                result
                                    .onSuccess { channel ->
                                        com.neveroff.ui.components.AppToast.success("Channel created")
                                        onCreated(channel.id)
                                    }
                                    .onFailure { error ->
                                        com.neveroff.ui.components.AppToast.error(
                                            error.toUserMessage(
                                                context,
                                                R.string.error_create_channel,
                                                "CreateChannel"
                                            )
                                        )
                                    }
                            }
                        }
                    },
                    enabled = if (step == 0) canContinue else canCreate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .heightIn(min = 54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            text = if (step == 0) "Next" else "Create channel",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showPlatformPicker) {
        AlertDialog(
            onDismissRequest = { showPlatformPicker = false },
            title = { Text(stringResource(R.string.add_a_link)) },
            text = {
                Column {
                    SocialPlatform.entries.forEach { platform ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    linkDrafts.add(LinkDraft(id = nextLinkId, platform = platform))
                                    nextLinkId += 1
                                    showPlatformPicker = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SocialPlatformIcon(platform, size = 22.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(platform.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlatformPicker = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun StepIndicator(step: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(2) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (index <= step) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}

@Composable
private fun ChannelDetailsStep(
    name: String,
    onNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    avatarBitmap: Bitmap?,
    onPickAvatar: () -> Unit,
    linkDrafts: List<LinkDraft>,
    onLinkChanged: (Long, String) -> Unit,
    onRemoveLink: (Long) -> Unit,
    onAddLink: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        // Picture + name on one row: the two things that identify the channel.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .clickable { onPickAvatar() },
                contentAlignment = Alignment.Center
            ) {
                if (avatarBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = avatarBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.channel_picture),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = stringResource(R.string.add_a_channel_picture),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.channel_name)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }

        Spacer(Modifier.height(24.dp))

        SectionLabel("Description")
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.what_is_this_channel_about)) },
            minLines = 3,
            maxLines = 6,
            shape = RoundedCornerShape(14.dp),
            supportingText = {
                Text(
                    text = "${description.length}/500",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Links", modifier = Modifier.weight(1f))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.clickable { onAddLink() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_a_social_link),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.add),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        if (linkDrafts.isEmpty()) {
            Text(
                text = stringResource(R.string.add_your_instagram_snapchat_tiktok_or),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }

        linkDrafts.forEach { draft ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(draft.platform.brandColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    SocialPlatformIcon(draft.platform, size = 22.dp)
                }
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = draft.url,
                    onValueChange = { onLinkChanged(draft.id, it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(draft.platform.hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                IconButton(onClick = { onRemoveLink(draft.id) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove ${draft.platform.label} link",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChannelSettingsStep(
    isPublic: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    usernameState: UsernameState
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        SectionLabel("Who can find this channel")

        VisibilityOption(
            selected = isPublic,
            icon = Icons.Default.Public,
            title = "Public",
            body = "Anyone can find this channel by searching for it, and open it from its link.",
            onClick = { onVisibilityChange(true) }
        )
        Spacer(Modifier.height(10.dp))
        VisibilityOption(
            selected = !isPublic,
            icon = Icons.Default.Lock,
            title = "Private",
            body = "Hidden from search. Only people you send the link to can open it.",
            onClick = { onVisibilityChange(false) }
        )

        Spacer(Modifier.height(28.dp))

        if (isPublic) {
            SectionLabel("Channel link")
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                modifier = Modifier.fillMaxWidth(),
                prefix = {
                    Text(
                        text = ChannelLinkScheme.PREFIX,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                },
                placeholder = { Text(stringResource(R.string.my_channel)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                isError = usernameState == UsernameState.TAKEN,
                trailingIcon = {
                    when (usernameState) {
                        UsernameState.CHECKING -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        UsernameState.AVAILABLE -> Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.link_is_available),
                            tint = Color(0xFF4CAF50)
                        )
                        UsernameState.TAKEN -> Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.link_is_taken),
                            tint = MaterialTheme.colorScheme.error
                        )
                        else -> {}
                    }
                },
                supportingText = {
                    Text(
                        text = when (usernameState) {
                            UsernameState.TAKEN -> "That link is already taken."
                            UsernameState.TOO_SHORT -> "3–32 characters. Letters, numbers and _ only."
                            UsernameState.AVAILABLE -> "${ChannelLinkScheme.PREFIX}$username is free."
                            else -> "Lowercase letters, numbers and _ only — no spaces."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (usernameState) {
                            UsernameState.TAKEN -> MaterialTheme.colorScheme.error
                            UsernameState.AVAILABLE -> Color(0xFF4CAF50)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        } else {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.private_invite_link),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.a_private_channel_gets_an_unguessable) +
                            "You'll find it on the channel page once it's created — share it with " +
                            "whoever should be able to read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.posts_in_a_channel_are_visible) +
                "end-to-end encrypted the way your chats are.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun VisibilityOption(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) androidx.compose.foundation.BorderStroke(
            1.5.dp, MaterialTheme.colorScheme.primary
        ) else null,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(bottom = 8.dp)
    )
}
