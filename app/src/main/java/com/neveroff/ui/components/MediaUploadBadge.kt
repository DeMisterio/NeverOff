package com.neveroff.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The state of one media message's trip to the server, as the sender sees it.
 */
sealed interface MediaSendState {
    /** Uploading, [fraction] of the way through. */
    data class Uploading(val fraction: Float) : MediaSendState
    /** The send failed and can be tried again. */
    data object Failed : MediaSendState
    /** Nothing to show. */
    data object Idle : MediaSendState
}

/**
 * Ring progress over the top-left corner of a media bubble, with a cancel
 * button inside it.
 *
 * It sits over the media rather than replacing it. The bubble used to be an
 * oval placeholder with a spinner in the middle while a photo uploaded, so the
 * sender could not see what they had just sent, and the bubble jumped to a
 * different size and shape when the upload finished. Rendering the real media
 * at its real proportions from the start means the only thing that changes on
 * completion is this badge fading out.
 */
@Composable
fun MediaUploadBadge(
    state: MediaSendState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state !is MediaSendState.Idle,
        // Fades and shrinks away rather than vanishing, so the eye is handed
        // over to the delivery tick instead of noticing something disappear.
        enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.7f),
        exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = 0.7f),
        modifier = modifier
    ) {
        val failed = state is MediaSendState.Failed
        val target = (state as? MediaSendState.Uploading)?.fraction?.coerceIn(0f, 1f) ?: 0f
        // Animated so a progress callback that jumps does not make the ring stutter.
        val fraction by animateFloatAsState(targetValue = target, label = "uploadRing")

        Box(
            modifier = Modifier
                .size(BADGE_SIZE)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable { if (failed) onRetry() else onCancel() },
            contentAlignment = Alignment.Center
        ) {
            if (!failed) {
                Canvas(modifier = Modifier.size(BADGE_SIZE)) {
                    val stroke = STROKE.toPx()
                    val inset = stroke / 2 + TRACK_INSET.toPx()
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    val topLeft = Offset(inset, inset)

                    drawArc(
                        color = Color.White.copy(alpha = 0.3f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke)
                    )
                    // From twelve o'clock, clockwise, like every other progress ring.
                    drawArc(
                        color = Color.White,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke)
                    )
                }
            }

            Icon(
                imageVector = if (failed) Icons.Default.Refresh else Icons.Default.Close,
                contentDescription = if (failed) "Try sending again" else "Cancel upload",
                tint = Color.White,
                modifier = Modifier.size(ICON_SIZE)
            )
        }
    }
}

private val BADGE_SIZE = 34.dp
private val ICON_SIZE = 16.dp
private val STROKE = 2.5.dp
private val TRACK_INSET = 1.dp
