package com.neveroff.ui.components

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.jan.supabase.gotrue.auth


import coil.compose.AsyncImage
import com.neveroff.crypto.CryptoManager
import com.neveroff.data.model.MediaEnvelope
import com.neveroff.data.model.MediaKind
import com.neveroff.data.remote.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

import io.github.jan.supabase.storage.storage

@OptIn(UnstableApi::class)
@Composable
fun MediaMessagePlayer(
    messageText: String,
    modifier: Modifier = Modifier,
    textColor: Color,
    uploadProgress: Float? = null,
    onClickFullScreen: ((Uri) -> Unit)? = null,
    /** Voice notes report waveform scrubbing here so the bubble can magnify/stretch. */
    onScrubStateChange: (VoiceScrubState) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val envelope = remember(messageText) { MediaEnvelope.parse(messageText) }
    val isVoice = envelope?.kind == MediaKind.VOICE
    // "isVideo" here has always meant the round note specifically.
    val isVideo = envelope?.kind == MediaKind.VIDEO_NOTE
    val isFileVideo = envelope?.kind == MediaKind.VIDEO

    var localFileUri by remember { mutableStateOf<Uri?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var isDownloading by remember { mutableStateOf(true) }
    var retryTrigger by remember { mutableStateOf(0) }

    val exoPlayer = remember {
        com.neveroff.media.ExoPlayerPool.acquire(context).apply {
            repeatMode = if (isVideo || isFileVideo) ExoPlayer.REPEAT_MODE_ALL else ExoPlayer.REPEAT_MODE_OFF
            // Both video kinds preview silently. This is a list of bubbles, and
            // a rectangular video used to start playing out loud as soon as it
            // scrolled into view. Sound belongs to the full-screen player, and
            // tapping a round note still unmutes it in place.
            if (isVideo || isFileVideo) volume = 0f
        }
    }

    LaunchedEffect(messageText, retryTrigger) {
        // Every media kind downloads through here. The old guard listed prefixes
        // by hand and omitted [FILE_VIDEO], so a video sent through the file
        // picker sat on a spinner that could never finish.
        val media = envelope ?: return@LaunchedEffect
        run {
            val remoteName = media.remoteName
            val fileKey = media.fileKey

            withContext(Dispatchers.IO) {
                try {
                    // The extension comes from the original filename where there
                    // is one, so the decrypted copy can be opened by whatever
                    // handles that type.
                    val decryptedFile = com.neveroff.util.MediaCache
                        .decryptedFile(remoteName, media.extension)

                    if (!decryptedFile.exists()) {
                        val url = "${com.neveroff.BuildConfig.SUPABASE_URL}/storage/v1/object/chat_media/$remoteName"
                        val encryptedFile = com.neveroff.util.MediaCache.encryptedFile(remoteName)

                        // Download with progress using HttpURLConnection
                        val urlObj = java.net.URL(url)
                        val connection = urlObj.openConnection() as java.net.HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("Authorization", "Bearer ${SupabaseClient.client.auth.currentAccessTokenOrNull() ?: ""}")
                        connection.setRequestProperty("apikey", com.neveroff.BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                        connection.connect()
                        
                        val contentLength = connection.contentLength
                        connection.inputStream.use { input ->
                            encryptedFile.outputStream().use { output ->
                                val buffer = ByteArray(8 * 1024)
                                var bytesCopied = 0L
                                var bytes = input.read(buffer)
                                while (bytes >= 0) {
                                    output.write(buffer, 0, bytes)
                                    bytesCopied += bytes
                                    if (contentLength > 0) {
                                        downloadProgress = bytesCopied.toFloat() / contentLength.toFloat()
                                    }
                                    bytes = input.read(buffer)
                                }
                            }
                        }
                        
                        CryptoManager.decryptFile(encryptedFile, decryptedFile, fileKey)
                        encryptedFile.delete()
                        connection.disconnect()
                    }
                    
                    withContext(Dispatchers.Main) {
                        localFileUri = Uri.fromFile(decryptedFile)
                        isDownloading = false
                        
                        val mediaItem = MediaItem.fromUri(Uri.fromFile(decryptedFile))
                        exoPlayer.setMediaItem(mediaItem)
                        exoPlayer.prepare()
                        if (isVideo || isFileVideo) {
                            exoPlayer.play()
                        }
                    }
                } catch (e: Exception) {
                    com.neveroff.util.ErrorReporter.report("MediaMessagePlayer", e)
                    withContext(Dispatchers.Main) {
                        isDownloading = false
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            com.neveroff.media.ExoPlayerPool.release(exoPlayer)
        }
    }

    if (uploadProgress != null) {
        Box(modifier = modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = uploadProgress,
                    color = textColor,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text("Отправка ${(uploadProgress * 100).toInt()}%", color = textColor, style = MaterialTheme.typography.labelSmall)
            }
        }
        return
    }

    if (isDownloading) {
        Box(modifier = modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = downloadProgress,
                    color = textColor,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text("${(downloadProgress * 100).toInt()}%", color = textColor, style = MaterialTheme.typography.labelSmall)
            }
        }
        return
    }

    if (localFileUri == null) {
        Box(modifier = modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.load_failed), color = textColor)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(onClick = { 
                    isDownloading = true
                    downloadProgress = 0f
                    retryTrigger++ 
                }) {
                    Text(stringResource(R.string.action_retry), color = textColor)
                }
            }
        }
        return
    }

    if (isVoice) {
        VoiceMessageContent(
            file = localFileUri!!,
            exoPlayer = exoPlayer,
            textColor = textColor,
            modifier = modifier,
            isPlaying = isPlaying,
            onTogglePlay = {
                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                isPlaying = !isPlaying
            },
            onScrubStateChange = onScrubStateChange
        )
    } else if (isVideo || isFileVideo) {
        val shape = if (isVideo) CircleShape else RoundedCornerShape(8.dp)
        val viewModifier = if (isVideo) modifier.size(240.dp) else modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp)

        val surface = @Composable {
            AndroidView(
                factory = { ctx ->
                    android.view.TextureView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        // Setting a SurfaceTextureListener to handle resizing if needed, but exoPlayer handles it mostly
                        exoPlayer.setVideoTextureView(this)
                        exoPlayer.videoScalingMode = android.media.MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isVideo) {
            // Round video note: the perimeter doubles as its timeline.
            var positionMs by remember { mutableStateOf(0L) }
            var durationMs by remember { mutableStateOf(0L) }
            var isScrubbing by remember { mutableStateOf(false) }

            LaunchedEffect(localFileUri, isScrubbing) {
                while (true) {
                    val d = exoPlayer.duration
                    if (d > 0) durationMs = d
                    if (!isScrubbing) positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    kotlinx.coroutines.delay(50)
                }
            }

            CircleVideoScrubber(
                progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                ringColor = textColor,
                trackColor = textColor.copy(alpha = 0.3f),
                modifier = viewModifier,
                onSeek = { fraction ->
                    if (durationMs > 0) {
                        positionMs = (fraction * durationMs).toLong()
                        exoPlayer.seekTo(positionMs)
                    }
                },
                onScrubbingChange = { isScrubbing = it }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(Color.Black)
                        .clickable {
                            if (exoPlayer.volume == 0f) exoPlayer.volume = 1f else exoPlayer.volume = 0f
                        }
                ) { surface() }
            }
        } else {
            // Rectangular video: silent looping preview, duration in the corner,
            // tap for the full-screen player with real controls.
            var durationMs by remember { mutableStateOf(0L) }
            LaunchedEffect(localFileUri) {
                while (durationMs <= 0L) {
                    val d = exoPlayer.duration
                    if (d > 0) durationMs = d
                    kotlinx.coroutines.delay(100)
                }
            }

            Box(
                modifier = viewModifier
                    .clip(shape)
                    .background(Color.Black)
                    .clickable {
                        if (onClickFullScreen != null && localFileUri != null) {
                            onClickFullScreen(localFileUri!!)
                        }
                    }
            ) {
                surface()

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = formatDuration(durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    } else if (envelope?.kind == MediaKind.IMAGE) {
        AsyncImage(
            model = localFileUri,
            contentDescription = stringResource(R.string.label_image),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 300.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    if (onClickFullScreen != null && localFileUri != null) {
                        onClickFullScreen(localFileUri!!)
                    }
                }
        )
    } else if (envelope?.kind == MediaKind.FILE) {
        val fileName = envelope.originalName ?: stringResource(R.string.file)
        val kind = com.neveroff.util.FileKind.of(fileName)
        val ext = envelope.extension.takeIf { it != "file" }.orEmpty()
        val sizeLabel = localFileUri?.let { uri ->
            runCatching {
                java.io.File(uri.path ?: "").takeIf { it.exists() }
                    ?.let { com.neveroff.util.FileKind.humanSize(it.length()) }
            }.getOrNull()
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .padding(4.dp)
                .clickable(enabled = localFileUri != null) {
                    localFileUri?.let { u -> onClickFullScreen?.invoke(u) }
                }
        ) {
            // The extension in a coloured tile, rather than a generic save
            // icon on everything. "PDF" tells you what arrived; a floppy disk
            // outline does not, and every file looked identical before.
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(kind.tint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                if (ext.isNotEmpty()) {
                    Text(
                        text = ext.take(4).uppercase(),
                        color = kind.tint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines = 1
                    )
                } else {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_save),
                        contentDescription = stringResource(R.string.file),
                        tint = kind.tint
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    fileName,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(kind.label, sizeLabel).joinToString(" · "),
                    color = textColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/**
 * Voice note bubble content: a real waveform the user can scrub, plus play/pause and the
 * elapsed/total time. Scrub gestures are forwarded up via [onScrubStateChange] so the
 * enclosing bubble can magnify while the user is aiming and rubber-band past the ends.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VoiceMessageContent(
    file: Uri,
    exoPlayer: ExoPlayer,
    textColor: Color,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onScrubStateChange: (VoiceScrubState) -> Unit,
    modifier: Modifier = Modifier
) {
    var bars by remember(file) { mutableStateOf(FloatArray(0)) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    LaunchedEffect(file) {
        val path = file.path ?: return@LaunchedEffect
        bars = com.neveroff.media.WaveformExtractor.extract(File(path))
    }

    // Track position while playing. Also keeps polling (slowly) when paused until the duration
    // is known: ExoPlayer reports TIME_UNSET while preparing, and without a duration the
    // waveform can't map a scrub position to a seek — so a never-played note wouldn't scrub.
    LaunchedEffect(isPlaying, file) {
        while (true) {
            val d = exoPlayer.duration
            if (d > 0) durationMs = d
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (!isPlaying && durationMs > 0) break
            kotlinx.coroutines.delay(if (isPlaying) 50L else 200L)
        }
    }

    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(textColor.copy(alpha = 0.2f))
                .clickable { onTogglePlay() },
            contentAlignment = Alignment.Center
        ) {
            if (isPlaying) {
                // Drawn inline: a Pause glyph isn't part of the core material icon set.
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .size(width = 3.dp, height = 12.dp)
                                .background(textColor)
                        )
                    }
                }
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.play),
                    tint = textColor
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            VoiceWaveform(
                bars = bars,
                progress = progress,
                playedColor = textColor,
                trackColor = textColor.copy(alpha = 0.35f),
                onSeek = { fraction ->
                    if (durationMs > 0) exoPlayer.seekTo((fraction * durationMs).toLong())
                    positionMs = (fraction * durationMs).toLong()
                },
                onScrubStateChange = onScrubStateChange
            )
            Text(
                text = formatDuration(positionMs) + " / " + formatDuration(durationMs),
                color = textColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
