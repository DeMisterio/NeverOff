package com.neveroff.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.neveroff.data.remote.LiveBoardSession
import java.util.UUID

/**
 * The drawing surface.
 *
 * Two independent gesture layers, and the order matters. Transform gestures
 * (pinch, rotate) are only ever multi-touch; drawing is only ever single-touch.
 * Reading the pointer count is what keeps a two-finger zoom from also painting
 * a line down the middle of the canvas.
 */
@Composable
fun BoardCanvas(
    strokes: List<BoardStroke>,
    color: Color,
    /** Fraction of canvas width, so a line is the same thickness on both screens. */
    strokeWidth: Float,
    canvasScale: Float,
    canvasRotation: Float,
    canvasOffset: Offset,
    onTransform: (scale: Float, rotation: Float, offset: Offset) -> Unit,
    onResetTransform: () -> Unit,
    onStroke: (id: String, points: List<LiveBoardSession.Point>, done: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // The stroke currently under the finger. Kept locally and drawn immediately
    // so the line appears with no round trip; the same points are broadcast as
    // they accumulate.
    var liveId by remember { mutableStateOf<String?>(null) }
    var livePoints by remember { mutableStateOf<List<LiveBoardSession.Point>>(emptyList()) }
    var lastSentAt by remember { mutableStateOf(0L) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .graphicsLayer {
                scaleX = canvasScale
                scaleY = canvasScale
                rotationZ = canvasRotation
                translationX = canvasOffset.x
                translationY = canvasOffset.y
            }
            // Double tap returns the canvas to square on, which is the only way
            // back from an awkward rotation without fighting it by hand.
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onResetTransform() })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    onTransform(
                        (canvasScale * zoom).coerceIn(0.5f, 5f),
                        canvasRotation + rotation,
                        canvasOffset + pan
                    )
                }
            }
            .pointerInput(color, strokeWidth) {
                detectDragGestures(
                    onDragStart = { offset ->
                        liveId = UUID.randomUUID().toString()
                        livePoints = listOf(offset.normalise(size))
                    },
                    onDrag = { change, _ ->
                        val id = liveId ?: return@detectDragGestures
                        livePoints = livePoints + change.position.normalise(size)

                        // Batch: one broadcast per pointer sample would be a
                        // hundred messages a second. Every 60 ms keeps the line
                        // visibly live without flooding the socket.
                        val now = System.currentTimeMillis()
                        if (now - lastSentAt > 60) {
                            lastSentAt = now
                            onStroke(id, livePoints, false)
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        val id = liveId
                        if (id != null && livePoints.size > 1) onStroke(id, livePoints, true)
                        liveId = null
                        livePoints = emptyList()
                    },
                    // Cancelled means a second finger arrived and the transform
                    // detector took the gesture. Drop what was drawn, so a pinch
                    // never leaves a stray mark behind.
                    onDragCancel = {
                        liveId = null
                        livePoints = emptyList()
                    }
                )
            }
    ) {
        canvasSize = size
        val w = size.width
        val h = size.height

        strokes.forEach { s ->
            drawBoardStroke(s.points, Color(s.color.toULong()), s.width * w, w, h)
        }
        liveId?.let {
            drawBoardStroke(livePoints, color, strokeWidth * w, w, h)
        }
    }
}

private fun androidx.compose.ui.geometry.Offset.normalise(
    size: androidx.compose.ui.unit.IntSize
): LiveBoardSession.Point = LiveBoardSession.Point(
    x = (x / size.width.toFloat()).coerceIn(0f, 1f),
    y = (y / size.height.toFloat()).coerceIn(0f, 1f)
)

/**
 * Draws a stroke from normalised points.
 *
 * quadraticBezierTo through the midpoints rather than lineTo between samples:
 * touch input is coarse enough that straight segments read as a polygon, and a
 * drawing app is judged almost entirely on whether its lines look like lines.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBoardStroke(
    points: List<LiveBoardSession.Point>,
    color: Color,
    widthPx: Float,
    w: Float,
    h: Float
) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, radius = widthPx / 2f, center = Offset(points[0].x * w, points[0].y * h))
        return
    }
    val path = Path().apply {
        moveTo(points[0].x * w, points[0].y * h)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            val midX = (prev.x + cur.x) / 2f * w
            val midY = (prev.y + cur.y) / 2f * h
            quadraticBezierTo(prev.x * w, prev.y * h, midX, midY)
        }
        val last = points.last()
        lineTo(last.x * w, last.y * h)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}
