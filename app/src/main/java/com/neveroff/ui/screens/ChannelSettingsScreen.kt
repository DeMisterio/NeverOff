package com.neveroff.ui.screens

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neveroff.data.local.decodeReactionList
import com.neveroff.data.model.Channel
import com.neveroff.data.model.ChannelLinkScheme
import com.neveroff.data.model.ReactionSetProblem
import com.neveroff.data.model.SocialPlatform
import com.neveroff.data.model.containsReaction
import com.neveroff.data.model.isValidChannelUsername
import com.neveroff.data.model.normalizeReactionSet
import com.neveroff.data.model.reactionKey
import com.neveroff.data.model.reactionSetProblem
import com.neveroff.data.model.sanitizeChannelUsername
import com.neveroff.data.repository.ChannelRepository
import com.neveroff.ui.components.ChannelAvatar
import com.neveroff.ui.components.SocialPlatformIcon
import com.neveroff.ui.components.brandColor
import com.neveroff.util.ImageHelper
import com.neveroff.util.toUserMessage
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

private data class EditableLink(val id: Long, val platform: SocialPlatform, val url: String)

/**
 * Owner-only settings for a channel: everything on the profile, plus the list of
 * reactions readers are allowed to leave.
 *
 * Deliberately absent: any "clear all posts" action. A channel's posts are its
 * content, not a conversation transcript, so there is no bulk-wipe here the way
 * there is for a private chat — individual posts can still be removed from the
 * feed itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSettingsScreen(
    channelId: String,
    repository: ChannelRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val channel by repository.observeChannel(channelId).collectAsState(initial = null)

    var name by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf<String?>(null) }
    var isPublic by remember { mutableStateOf<Boolean?>(null) }
    var allowedReactions by remember { mutableStateOf<List<String>?>(null) }
    var editableLinks by remember { mutableStateOf<List<EditableLink>?>(null) }
    var nextLinkId by remember { mutableStateOf(1L) }

    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingAvatarUri by remember { mutableStateOf<Uri?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var showPlatformPicker by remember { mutableStateOf(false) }

    // Seed the editable copies once, so typing isn't clobbered by a later
    // realtime refresh of the underlying row.
    LaunchedEffect(channel?.id) {
        val c = channel ?: return@LaunchedEffect
        if (name == null) {
            name = c.name
            description = c.description.orEmpty()
            username = c.username
            isPublic = c.isPublic
            allowedReactions = decodeReactionList(c.allowedReactions).ifEmpty { Channel.DEFAULT_REACTIONS }
        }
        if (editableLinks == null) {
            val existing = repository.fetchLinks(channelId)
            var id = 1L
            editableLinks = existing.mapNotNull { link ->
                link.socialPlatform?.let { EditableLink(id++, it, link.url) }
            }
            nextLinkId = id
        }
    }

    val avatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pendingAvatarUri = uri }

    pendingAvatarUri?.let { uri ->
        com.neveroff.ui.components.ImageEditorDialog(
            imageUri = uri,
            circularCrop = false,
            onCancel = { pendingAvatarUri = null },
            onConfirm = { bitmap ->
                avatarBitmap = bitmap
                pendingAvatarUri = null
            }
        )
    }

    val current = channel
    val currentName = name
    val currentReactions = allowedReactions
    if (current == null || currentName == null || currentReactions == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val usernameChanged = username != current.username
    val usernameValid = isPublic != true || isValidChannelUsername(username.orEmpty())

    // Checked here rather than discovered from a 400: the server enforces the
    // same range, and a rejected save used to surface as a raw Postgres
    // constraint error.
    val reactionProblem = reactionSetProblem(currentReactions)
    val reactionHint = when (reactionProblem) {
        ReactionSetProblem.EMPTY -> stringResource(R.string.reactions_need_one)
        ReactionSetProblem.TOO_MANY ->
            stringResource(R.string.reactions_too_many, Channel.MAX_REACTIONS)
        ReactionSetProblem.NONE -> null
    }

    val canSave = currentName.trim().length >= 2 &&
        usernameValid &&
        reactionProblem == ReactionSetProblem.NONE &&
        !isSaving

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.channel_settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Two boxes, not one. The badge used to live inside the
                    // clipped avatar container, so the avatar's rounded-rect clip
                    // cut the corner off it. The clip now applies only to the
                    // picture; the badge is a sibling in an outer box that does
                    // not clip, free to overhang the edge.
                    Box(
                        modifier = Modifier
                            .size(86.dp)
                            .clickable {
                                avatarLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .align(Alignment.TopStart)
                                .clip(RoundedCornerShape(24.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            val bitmap = avatarBitmap
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.channel_picture),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                ChannelAvatar(
                                    avatarUrl = current.avatarUrl,
                                    name = currentName,
                                    size = 76.dp,
                                    shape = RoundedCornerShape(24.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(28.dp)
                                .clip(CircleShape)
                                // A ring in the screen's own background colour, so
                                // the badge stays legible over a busy avatar.
                                .background(MaterialTheme.colorScheme.background)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PhotoCamera,
                                contentDescription = stringResource(R.string.change_picture),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    SettingsField(
                        value = currentName,
                        onValueChange = { if (it.length <= 64) name = it },
                        placeholder = "Channel name",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(24.dp))

                SettingsLabel("Description")
                SettingsField(
                    value = description.orEmpty(),
                    onValueChange = { if (it.length <= 500) description = it },
                    placeholder = "What is this channel about?",
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(20.dp))

                SettingsLabel("Visibility")
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isPublic == true) "Public" else "Private",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isPublic == true) {
                                    "Findable in search and by link."
                                } else {
                                    "Hidden from search — reachable only by link."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isPublic == true,
                            onCheckedChange = { isPublic = it },
                        colors = com.neveroff.ui.components.appSwitchColors()
                    )
                    }
                }

                Spacer(Modifier.height(20.dp))

                SettingsLabel("Channel link")
                SettingsField(
                    value = username.orEmpty(),
                    onValueChange = { username = sanitizeChannelUsername(it) },
                    placeholder = "my_channel",
                    prefix = ChannelLinkScheme.PREFIX,
                    isError = !usernameValid,
                    supportingText = when {
                        !usernameValid -> "3–32 characters. Letters, numbers and _ only."
                        usernameChanged -> "The old link will stop working once you save."
                        else -> "Anyone can paste this into a chat to share the channel."
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(24.dp))

                // The whole point of this screen for most owners: deciding what
                // readers may leave under a post.
                SettingsLabel("Allowed reactions")
                Text(
                    text = reactionHint
                        ?: "${currentReactions.size} of ${Channel.MAX_REACTIONS} picked. Readers can only use these.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reactionHint != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                ReactionChooser(
                    selected = currentReactions,
                    onToggle = { emoji ->
                        val list = currentReactions.toMutableList()
                        when {
                            // Compared by reactionKey, not by ==, so a stored "❤"
                            // and a picker "❤️" count as the one reaction they are.
                            list.containsReaction(emoji) -> {
                                // Never leave a post with nothing to react with.
                                if (list.size > Channel.MIN_REACTIONS) {
                                    list.removeAll { reactionKey(it) == reactionKey(emoji) }
                                } else {
                                    com.neveroff.ui.components.AppToast.show(
                                        context.getString(R.string.reactions_need_one)
                                    )
                                }
                            }
                            list.size >= Channel.MAX_REACTIONS ->
                                com.neveroff.ui.components.AppToast.show(
                                    "That's the maximum — remove one first"
                                )
                            else -> list.add(emoji)
                        }
                        allowedReactions = normalizeReactionSet(list)
                    }
                )

                Spacer(Modifier.height(24.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsLabel("Links", modifier = Modifier.weight(1f))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.clickable { showPlatformPicker = true }
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

                editableLinks.orEmpty().forEach { link ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(link.platform.brandColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            SocialPlatformIcon(link.platform, size = 22.dp)
                        }
                        Spacer(Modifier.width(10.dp))
                        SettingsField(
                            value = link.url,
                            onValueChange = { newUrl ->
                                editableLinks = editableLinks.orEmpty().map {
                                    if (it.id == link.id) it.copy(url = newUrl) else it
                                }
                            },
                            placeholder = link.platform.hint,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            editableLinks = editableLinks.orEmpty().filterNot { it.id == link.id }
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove ${link.platform.label} link",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        isSaving = true
                        scope.launch {
                            val bytes = avatarBitmap?.let { ImageHelper.compressBitmap(it) }
                            val result = repository.updateChannel(
                                channelId = channelId,
                                name = currentName,
                                description = description,
                                username = username,
                                isPublic = isPublic == true,
                                avatarBytes = bytes,
                                socialLinks = editableLinks.orEmpty()
                                    .filter { it.url.isNotBlank() }
                                    .map { it.platform to it.url },
                                allowedReactions = currentReactions
                            )
                            isSaving = false
                            result
                                .onSuccess {
                                    com.neveroff.ui.components.AppToast.success(
                                        context.getString(R.string.changes_saved)
                                    )
                                    onBack()
                                }
                                .onFailure { error ->
                                    // Never `error.message`: ktor builds it from the
                                    // whole request, so it carries the URL and the
                                    // Authorization: Bearer <JWT> header with it.
                                    com.neveroff.ui.components.AppToast.error(
                                        error.toUserMessage(
                                            context,
                                            fallback = R.string.error_save_channel,
                                            tag = "ChannelSettings"
                                        )
                                    )
                                }
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .heightIn(min = 54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.save_changes), fontWeight = FontWeight.Bold)
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
                                    editableLinks = editableLinks.orEmpty() +
                                        EditableLink(nextLinkId, platform, "")
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
private fun ReactionChooser(selected: List<String>, onToggle: (String) -> Unit) {
    Channel.AVAILABLE_REACTIONS.chunked(5).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            row.forEach { emoji ->
                val isOn = selected.containsReaction(emoji)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .then(
                            if (isOn) Modifier.border(
                                1.5.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(14.dp)
                            ) else Modifier
                        )
                        .clickable { onToggle(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 22.sp)
                    if (isOn) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.allowed),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(12.dp)
                        )
                    }
                }
            }
            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SettingsLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    // Capped so a long description grows to a readable height and then scrolls,
    // instead of pushing the rest of the form off the screen.
    maxLines: Int = INPUT_FIELD_MAX_LINES,
    prefix: String? = null,
    isError: Boolean = false,
    supportingText: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        shape = INPUT_FIELD_SHAPE,
        prefix = prefix?.let {
            {
                Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        supportingText = supportingText?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}
