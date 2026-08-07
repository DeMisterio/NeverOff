package com.neveroff.ui.components

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlin.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.neveroff.ui.screens.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.neveroff.data.model.MediaEnvelope
import com.neveroff.media.AudioFocusEffect
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun FullScreenMediaViewer(
    uri: Uri,
    message: UiMessage,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onForward: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    BackHandler { onDismiss() }

    // Any video, however it was sent — a clip picked from Files is still a
    // video, and used to fall through to the image branch here.
    val envelope = remember(message.text) { MediaEnvelope.parse(message.text) }
    val isVideo = envelope?.isVideo == true
    
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isVideo) {
            val exoPlayer = remember {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                    playWhenReady = true
                    repeatMode = ExoPlayer.REPEAT_MODE_ALL
                }
            }

            // This one plays out loud, so it holds audio focus for as long as it
            // is playing and gives it back on pause, on stop, and on leaving the
            // screen — the last of those by virtue of leaving composition.
            var isPlaying by remember { mutableStateOf(true) }
            DisposableEffect(exoPlayer) {
                val listener = object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }
                }
                exoPlayer.addListener(listener)
                onDispose { exoPlayer.removeListener(listener) }
            }
            AudioFocusEffect(active = isPlaying) { exoPlayer.pause() }

            DisposableEffect(Unit) {
                onDispose {
                    exoPlayer.release()
                }
            }
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = uri,
                contentDescription = stringResource(R.string.full_screen_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
        }

        // Top Bar
        TopAppBar(
            title = { Text("") },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
            },
            actions = {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu), tint = Color.White)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.reply)) }, onClick = { showMenu = false; onReply() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { showMenu = false; showDeleteConfirm = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_to_gallery)) }, onClick = {
                        showMenu = false
                        scope.launch {
                            val success = saveMediaToGallery(
                                context = context,
                                sourceUri = uri,
                                isVideo = isVideo,
                                displayName = envelope?.saveAsName(System.currentTimeMillis())
                                    ?: "NeverOff_${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}",
                                mimeType = envelope?.mimeType
                                    ?: if (isVideo) "video/mp4" else "image/jpeg"
                            )
                            if (success) {
                                AppToast.success("Saved to Gallery")
                            } else {
                                AppToast.error("Failed to save")
                            }
                        }
                    })
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.5f)
            )
        )

        // Bottom Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = Color.White)
            }
            IconButton(onClick = onForward) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_forward), tint = Color.White)
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(stringResource(R.string.delete_message)) },
                text = { Text(stringResource(R.string.this_will_delete_the_message_for)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

/**
 * Copies a decrypted file into the gallery.
 *
 * [displayName] and [mimeType] come from the message envelope, so a file keeps
 * the name it was sent under and lands as the type it actually is. Both used to
 * be invented here from a boolean, which renamed everything to NeverOff_<millis>
 * and declared every video mp4 regardless of what it was.
 */
suspend fun saveMediaToGallery(
    context: Context,
    sourceUri: Uri,
    isVideo: Boolean,
    displayName: String,
    mimeType: String
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/NeverOff" else "Pictures/NeverOff")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (isVideo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val destUri = contentResolver.insert(collection, contentValues) ?: return@withContext false

            contentResolver.openInputStream(sourceUri)?.use { input: InputStream ->
                contentResolver.openOutputStream(destUri)?.use { output: OutputStream ->
                    input.copyTo(output)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(destUri, contentValues, null, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
