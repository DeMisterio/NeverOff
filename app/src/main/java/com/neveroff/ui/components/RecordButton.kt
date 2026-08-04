package com.neveroff.ui.components

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.neveroff.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

import androidx.compose.animation.animateColor
import com.neveroff.ui.theme.readableContentColor

enum class RecordMode { AUDIO, VIDEO }

@Composable
fun RecordButton(
    isTextBlank: Boolean,
    isLocked: Boolean,
    sendEnabled: Boolean = true,
    buttonColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    onSendText: () -> Unit,
    onSendTextLongPress: () -> Unit = {},
    onStartRecording: (RecordMode) -> Unit,
    onLockRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit
) {
    var mode by remember { mutableStateOf(RecordMode.AUDIO) }
    var isRecording by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Read live inside the gesture without being a pointerInput key (see below), so an in-flight
    // send can't tear down a hold the user is in the middle of.
    val currentSendEnabled by rememberUpdatedState(sendEnabled)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Just request permissions. The user will have to hold the button again
        // to actually start recording. This prevents the button from getting stuck
        // in a recording state with no touch event tracking it.
    }

    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
    val blinkColor by infiniteTransition.animateColor(
        initialValue = androidx.compose.ui.graphics.Color.Yellow,
        targetValue = androidx.compose.ui.graphics.Color(0xFFFFA500), // Orange-yellow
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        )
    )

    val bgColor = if (isRecording) blinkColor else buttonColor

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bgColor)
            // Deliberately NOT keyed on sendEnabled: that flips while a message is in flight,
            // which restarts this pointerInput and kills any hold already in progress — one of
            // the ways starting a recording would just silently do nothing.
            .pointerInput(isTextBlank, isLocked) {
                if (isLocked) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    if (!isTextBlank) {
                        if (!currentSendEnabled) {
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        val upBeforeTimeout = withTimeoutOrNull(500L) {
                            waitForUpOrCancellation()
                        }
                        if (upBeforeTimeout == null) {
                            // Long press
                            onSendTextLongPress()
                            waitForUpOrCancellation()
                        } else {
                            onSendText()
                        }
                        return@awaitEachGesture
                    }

                    // It's blank, so we wait 300ms to see if it's a tap or hold
                    val upBeforeTimeout = withTimeoutOrNull(300L) {
                        waitForUpOrCancellation()
                    }
                    val isLongPress = upBeforeTimeout == null

                    if (!isLongPress) {
                        // Short tap
                        if (isRecording) {
                            isRecording = false
                            onStopRecording()
                        } else {
                            // toggle mode
                            mode = if (mode == RecordMode.AUDIO) RecordMode.VIDEO else RecordMode.AUDIO
                        }
                    } else {
                        // Long press: Check permissions and start recording
                        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        
                        if (mode == RecordMode.AUDIO && !hasAudio) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            return@awaitEachGesture
                        } else if (mode == RecordMode.VIDEO && (!hasAudio || !hasCamera)) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                            return@awaitEachGesture
                        }
                        
                        isRecording = true
                        onStartRecording(mode)

                        // Track pointer movement
                        var locked = false
                        var cancelled = false
                        val startY = down.position.y
                        val startX = down.position.x

                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id }

                            if (pointer == null || !pointer.pressed) {
                                // Released!
                                break
                            }

                            // Swipe left far enough to throw the take away. Checked before the
                            // lock so a diagonal drag resolves as a cancel rather than locking
                            // a recording the user was trying to discard.
                            if (startX - pointer.position.x > 120f) {
                                cancelled = true
                                onCancelRecording()
                                break
                            }

                            if (startY - pointer.position.y > 100f) {
                                // Swiped up! Lock!
                                locked = true
                                onLockRecording()
                                break
                            }
                        }

                        if (cancelled) {
                            isRecording = false
                            // Swallow the rest of the gesture so releasing doesn't also send.
                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id }
                                if (pointer == null || !pointer.pressed) break
                            }
                        } else if (!locked) {
                            isRecording = false
                            onStopRecording()
                        } else {
                            // If locked, we leave isRecording = true visually? 
                            // Actually, the button will be hidden by ChatScreen if we replace it with a toolbar,
                            // or it will just be locked. We'll set isRecording = false locally because
                            // ChatScreen will handle the Locked state UI.
                            isRecording = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = if (!isTextBlank) "SEND" else if (mode == RecordMode.AUDIO) "MIC" else "CAM",
            transitionSpec = {
                scaleIn(animationSpec = tween(200)) togetherWith scaleOut(animationSpec = tween(200))
            },
            label = "button_icon"
        ) { state ->
            val iconColor = buttonColor.readableContentColor()
            when (state) {
                "SEND" -> Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send),
                    tint = iconColor,
                    modifier = Modifier.padding(start = 4.dp)
                )
                "MIC" -> Icon(
                    painter = painterResource(id = android.R.drawable.ic_btn_speak_now),
                    contentDescription = stringResource(R.string.mic),
                    tint = iconColor
                )
                "CAM" -> Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_camera),
                    contentDescription = stringResource(R.string.camera),
                    tint = iconColor
                )
            }
        }
    }
}
