package com.neveroff.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculatePan
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
    var lastTapAt by remember { mutableStateOf(0L) }
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
            // ONE gesture handler, not three.
            //
            // Previously this stacked detectTapGestures, detectTransformGestures
            // and detectDragGestures. detectTransformGestures fires on a single
            // finger too — it treats a one-pointer drag as a pan — so it
            // swallowed every stroke and panned the canvas instead. Drawing
            // never worked, and no amount of Supabase configuration would have
            // helped, because nothing was ever sent.
            //
            // Counting pointers is the only reliable way to tell the two apart:
            // one finger draws, two or more transform.
            .pointerInput(color, strokeWidth) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        var id: String? = UUID.randomUUID().toString()
                        var pts = listOf(down.position.normalise(size))
                        var transforming = false
                        var moved = false

                        liveId = id
                        livePoints = pts

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {
                                // A second finger turns this into a transform.
                                // Whatever was drawn so far is discarded, so a
                                // pinch never leaves a stray mark behind.
                                if (!transforming) {
                                    transforming = true
                                    id = null
                                    pts = emptyList()
                                    liveId = null
                                    livePoints = emptyList()
                                }
                                val zoom = event.calculateZoom()
                                val rotation = event.calculateRotation()
                                val pan = event.calculatePan()
                                if (zoom != 1f || rotation != 0f || pan != Offset.Zero) {
                                    onTransform(
                                        (canvasScale * zoom).coerceIn(0.5f, 5f),
                                        canvasRotation + rotation,
                                        canvasOffset + pan
                                    )
                                }
                                event.changes.forEach { it.consume() }
                            } else if (!transforming) {
                                val change = pressed[0]
                                if (change.position != change.previousPosition) moved = true
                                pts = pts + change.position.normalise(size)
                                livePoints = pts

                                // Batch: one broadcast per pointer sample would
                                // be a hundred a second. Every 60 ms keeps the
                                // line visibly live without flooding the socket.
                                val now = System.currentTimeMillis()
                                val strokeId = id
                                if (strokeId != null && now - lastSentAt > 60) {
                                    lastSentAt = now
                                    onStroke(strokeId, pts, false)
                                }
                                change.consume()
                            }
                        }

                        val strokeId = id
                        if (!transforming && strokeId != null && pts.size > 1) {
                            onStroke(strokeId, pts, true)
                        } else if (!transforming && !moved &&
                            System.currentTimeMillis() - downTime < 260
                        ) {
                            // A tap that never moved. Two in quick succession
                            // reset the view — handled here rather than by a
                            // separate tap detector, because a second detector
                            // would compete for the same events all over again.
                            val sinceLastTap = System.currentTimeMillis() - lastTapAt
                            if (sinceLastTap < 320) {
                                onResetTransform()
                                lastTapAt = 0L
                            } else {
                                lastTapAt = System.currentTimeMillis()
                            }
                        }

                        liveId = null
                        livePoints = emptyList()
                    }
                }
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
