@file:OptIn(ExperimentalFoundationApi::class)

package com.neveroff.ui.components

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryMediaItem(val id: Long, val uri: Uri, val isVideo: Boolean)

/** A selected item on its way to being sent — may carry an edited bitmap in place of the original. */
data class MediaSendItem(val source: GalleryMediaItem, val editedBitmap: Bitmap? = null)

private enum class PickerStage { GRID, PREVIEW }

private fun requiredMediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasMediaPermission(context: android.content.Context): Boolean =
    requiredMediaPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

private data class RawMediaRow(val id: Long, val uri: Uri, val isVideo: Boolean, val dateAdded: Long)

/**
 * Queries the Images and Video collections separately rather than the generic
 * MediaStore.Files provider: on API 33+ the split READ_MEDIA_IMAGES/READ_MEDIA_VIDEO
 * permissions don't reliably grant access through that generic endpoint on every OEM,
 * which silently produced an empty result set despite permission being granted.
 */
private fun queryCollection(context: android.content.Context, collection: Uri, isVideo: Boolean): List<RawMediaRow> {
    val rows = mutableListOf<RawMediaRow>()
    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
    try {
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val date = cursor.getLong(dateCol)
                rows.add(RawMediaRow(id, ContentUris.withAppendedId(collection, id), isVideo, date))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return rows
}

private fun queryDeviceMedia(context: android.content.Context, limit: Int = 400): List<GalleryMediaItem> {
    val images = queryCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
    val videos = queryCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
    return (images + videos)
        .sortedByDescending { it.dateAdded }
        .take(limit)
        .map { GalleryMediaItem(it.id, it.uri, it.isVideo) }
}

private fun loadVideoThumbnail(context: android.content.Context, uri: Uri): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.loadThumbnail(uri, android.util.Size(300, 300), null)
    } else {
        val id = ContentUris.parseId(uri)
        @Suppress("DEPRECATION")
        MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
    }
} catch (e: Exception) {
    null
}

/**
 * In-app replacement for the system gallery picker: a grid the user can multi-select from
 * (in tap order, numbered like top messengers), then a preview step per selected item before
 * actually sending — images can be routed through [ImageEditorDialog] first.
 */
@Composable
fun MediaGalleryPickerDialog(onDismiss: () -> Unit, onSend: (List<MediaSendItem>) -> Unit) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf(PickerStage.GRID) }
    var isLoading by remember { mutableStateOf(true) }
    var permissionDenied by remember { mutableStateOf(false) }
    var allMedia by remember { mutableStateOf<List<GalleryMediaItem>>(emptyList()) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<GalleryMediaItem>() }
    var previewItems by remember { mutableStateOf<List<MediaSendItem>>(emptyList()) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            isLoading = true
        } else {
            permissionDenied = true
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        if (hasMediaPermission(context)) {
            isLoading = true
        } else {
            permissionLauncher.launch(requiredMediaPermissions())
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading) return@LaunchedEffect
        if (!hasMediaPermission(context)) {
            isLoading = false
            return@LaunchedEffect
        }
        allMedia = withContext(Dispatchers.IO) { queryDeviceMedia(context) }
        isLoading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            when (stage) {
                PickerStage.GRID -> GridStage(
                    isLoading = isLoading,
                    permissionDenied = permissionDenied,
                    media = allMedia,
                    selected = selected,
                    onDismiss = onDismiss,
                    onNext = {
                        previewItems = selected.map { MediaSendItem(it) }
                        stage = PickerStage.PREVIEW
                    }
                )
                PickerStage.PREVIEW -> PreviewStage(
                    items = previewItems,
                    onBack = { stage = PickerStage.GRID },
                    onRemove = { index ->
                        val removedItem = previewItems.getOrNull(index)?.source
                        previewItems = previewItems.filterIndexed { i, _ -> i != index }
                        if (removedItem != null) selected.remove(removedItem)
                        if (previewItems.isEmpty()) stage = PickerStage.GRID
                    },
                    onEdit = { index -> editingIndex = index },
                    onSend = { onSend(previewItems) }
                )
            }
        }
    }

    val editIdx = editingIndex
    if (editIdx != null) {
        val item = previewItems.getOrNull(editIdx)
        if (item != null && !item.source.isVideo) {
            ImageEditorDialog(
                imageUri = item.source.uri,
                circularCrop = false,
                onCancel = { editingIndex = null },
                onConfirm = { bitmap ->
                    previewItems = previewItems.toMutableList().also { it[editIdx] = item.copy(editedBitmap = bitmap) }
                    editingIndex = null
                }
            )
        } else {
            editingIndex = null
        }
    }
}

@Composable
private fun GridStage(
    isLoading: Boolean,
    permissionDenied: Boolean,
    media: List<GalleryMediaItem>,
    selected: androidx.compose.runtime.snapshots.SnapshotStateList<GalleryMediaItem>,
    onDismiss: () -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
            }
            Text(
                text = if (selected.isEmpty()) "Select media" else "${selected.size} selected",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.size(48.dp))
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                isLoading -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
                permissionDenied -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("NeverOff needs access to your photos and videos to show them here.", color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("You can grant this in system settings.", color = Color.White.copy(alpha = 0.7f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                media.isEmpty() -> Text("No photos or videos found", color = Color.White, modifier = Modifier.align(Alignment.Center))
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(media, key = { it.id }) { item ->
                        val order = selected.indexOf(item)
                        val isSelected = order >= 0
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .padding(1.dp)
                                .clickable {
                                    if (isSelected) selected.remove(item) else selected.add(item)
                                }
                        ) {
                            if (item.isVideo) {
                                VideoThumbnail(uri = item.uri, modifier = Modifier.fillMaxSize())
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Video",
                                    tint = Color.White,
                                    modifier = Modifier.align(Alignment.Center).size(28.dp)
                                )
                            } else {
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (isSelected) {
                                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = (order + 1).toString(),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selected.isNotEmpty()) {
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Text("Next (${selected.size})")
            }
        }
    }
}

@Composable
private fun VideoThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { loadVideoThumbnail(context, uri) }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier.background(Color.DarkGray))
    }
}

@Composable
private fun PreviewStage(
    items: List<MediaSendItem>,
    onBack: () -> Unit,
    onRemove: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onSend: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { items.size })

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = "Back to selection", tint = Color.White)
            }
            Text("${items.size} item${if (items.size == 1) "" else "s"}", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.size(48.dp))
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (items.isEmpty()) return@Box
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page]
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val bmp = item.editedBitmap
                    when {
                        bmp != null -> Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(16.dp)
                        )
                        item.source.isVideo -> VideoThumbnail(uri = item.source.uri, modifier = Modifier.fillMaxSize().padding(16.dp))
                        else -> AsyncImage(
                            model = item.source.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(16.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            val currentIndex = pagerState.currentPage
            val currentItem = items.getOrNull(currentIndex)
            if (currentItem != null && !currentItem.source.isVideo) {
                IconButton(onClick = { onEdit(currentIndex) }) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(24.dp))
            }
            IconButton(onClick = { onRemove(currentIndex) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.White)
            }
        }

        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Send")
        }
    }
}
