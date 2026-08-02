package com.neveroff.ui.components

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import com.neveroff.media.CameraRecorderHelper

/**
 * Diameter of the recording viewfinder. Larger than a sent round note (240dp): while recording
 * this is centred and is the main subject of the screen, not an inline message.
 */
private val CircleSize = 300.dp

/** Roughly the record button's 48dp against [CircleSize] — the clone starts life its size. */
private const val SeedScale = 0.16f

/**
 * Round camera viewfinder that opens as if the record button itself grew into it.
 *
 * CameraX takes a beat to bind and push its first frame, and dropping a black hole on screen for
 * that beat is what made starting a circle message feel broken. So a tinted clone of the button
 * springs open at full circle size immediately, then cross-fades to the live preview the moment
 * the stream actually starts — the wait is covered rather than shown.
 */
@Composable
fun CircularCameraPreview(
    cameraRecorderHelper: CameraRecorderHelper,
    modifier: Modifier = Modifier,
    seedColor: Color = Color.DarkGray
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    var isStreaming by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }

    LaunchedEffect(cameraRecorderHelper, lifecycleOwner) {
        cameraRecorderHelper.bindCamera(lifecycleOwner, previewView)
    }

    // Kick the grow-open animation on the first frame this composable is on screen.
    LaunchedEffect(Unit) { opened = true }

    // PreviewView reports STREAMING only once real frames are arriving, which is exactly when
    // the placeholder has something to hand over to.
    DisposableEffect(previewView, lifecycleOwner) {
        val observer = Observer<PreviewView.StreamState> {
            isStreaming = it == PreviewView.StreamState.STREAMING
        }
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        onDispose { previewView.previewStreamState.removeObserver(observer) }
    }

    val scale by animateFloatAsState(
        targetValue = if (opened) 1f else SeedScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "cloneGrow"
    )
    val previewAlpha by animateFloatAsState(
        targetValue = if (isStreaming) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "previewFade"
    )

    Box(
        modifier = modifier
            .size(CircleSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (isStreaming) Color.Black else seedColor),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = previewAlpha }
        )
    }
}
