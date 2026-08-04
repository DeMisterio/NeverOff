package com.neveroff.ui.screens

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.neveroff.data.local.AppDatabase
import com.neveroff.data.remote.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDialog(
    isVisible: Boolean,
    profileId: String,
    onDismiss: () -> Unit,
    onPickAvatar: (() -> Unit)? = null,
    selectedAvatarUri: android.net.Uri? = null,
    onAvatarUploaded: () -> Unit = {}
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    
    var profile by remember { mutableStateOf<Profile?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isUploading by remember { mutableStateOf(false) }
    var showFullScreenAvatar by remember { mutableStateOf(false) }

    val myId = SupabaseClient.client.auth.currentUserOrNull()?.id
    val isMyProfile = myId == profileId



    if (!isVisible) return
    
    LaunchedEffect(profileId, isVisible) {
        if (!isVisible) return@LaunchedEffect

        try {
            val fetched = SupabaseClient.client.postgrest["profiles"]
                .select { filter { eq("id", profileId) } }
                .decodeSingle<Profile>()
            profile = fetched
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@ModalBottomSheet
        }

        if (profile == null) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.profile_not_found))
            }
            return@ModalBottomSheet
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier.size(136.dp)
            ) {
                // Avatar itself
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .align(Alignment.CenterStart)
                        .clip(CircleShape)
                        .border(
                            width = 2.dp,
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(com.neveroff.ui.theme.AccentGradient),
                            shape = CircleShape
                        )
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(enabled = profile!!.avatar_url != null && !isUploading) {
                            showFullScreenAvatar = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (profile!!.avatar_url != null) {
                        AsyncImage(
                            model = profile!!.avatar_url,
                            contentDescription = stringResource(R.string.avatar),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = profile!!.display_name.take(1).uppercase(),
                            fontSize = 48.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    if (isUploading) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }

                // Edit Button (circle, overlapping at bottom-right, not clipped)
                if (isMyProfile && !isUploading && onPickAvatar != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                            .clickable { onPickAvatar() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.change_avatar),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Full Screen Avatar Dialog
            if (showFullScreenAvatar && profile!!.avatar_url != null) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showFullScreenAvatar = false },
                    properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false
                    )
                ) {
                    var offsetY by remember { mutableStateOf(0f) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = (1f - (offsetY.absoluteValue / 1000f)).coerceIn(0f, 1f)))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        offsetY += dragAmount.y
                                    },
                                    onDragEnd = {
                                        if (offsetY.absoluteValue > 300f) {
                                            showFullScreenAvatar = false
                                        }
                                        offsetY = 0f
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = profile!!.avatar_url,
                            contentDescription = stringResource(R.string.full_screen_avatar),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .offset { androidx.compose.ui.unit.IntOffset(0, offsetY.roundToInt()) }
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = profile!!.display_name,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.label_username),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                onClick = {
                    val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText(context.getString(R.string.username), "@${profile!!.username}")
                    clipboardManager.setPrimaryClip(clip)
                    com.neveroff.ui.components.AppToast.success("Юзернейм скопирован")
                }
            ) {
                Text(
                    text = "@${profile!!.username}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (!profile!!.bio.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.label_about),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = profile!!.bio!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
