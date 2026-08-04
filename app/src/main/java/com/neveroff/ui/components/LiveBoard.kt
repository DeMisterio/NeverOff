package com.neveroff.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.neveroff.R
import com.neveroff.data.remote.LiveBoardSession
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A drawing board two people share in real time.
 *
 * Everything about the geometry is kept in *normalised* coordinates — 0..1 of
 * the canvas — and only converted to pixels at the moment of drawing. The two
 * people have differently sized windows, and either may have rotated or zoomed
 * theirs, so a stroke described in pixels would land somewhere else on the
 * other screen. This is the single most important thing about the file.
 */

/** The palette down the left edge. Ten, because the column has to stay reachable by thumb. */
val BOARD_COLOURS = listOf(
    Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFE53935), Color(0xFFFB8C00),
    Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF00ACC1), Color(0xFF1E88E5),
    Color(0xFF8E24AA), Color(0xFFEC407A)
)

/** Brush sizes, as a fraction of canvas width so they scale with the window. */
val BOARD_WIDTHS = listOf(0.004f, 0.008f, 0.016f, 0.028f, 0.045f)

/** Below this fraction of full size the window rolls into a bubble. */
private const val COLLAPSE_AT = 0.20f

/** Aspect ratio the window keeps while being resized. */
private const val BOARD_RATIO = 1.15f

data class BoardStroke(
    val id: String,
    val color: Long,
    val width: Float,
    val points: List<LiveBoardSession.Point>,
    val mine: Boolean
)

/**
 * The floating board.
 *
 * @param strokes every stroke on the canvas, local and remote alike.
 * @param onStroke called with each batch while drawing, and once more with
 *        done = true on lift, so the other side sees the line grow.
 */
@Composable
fun LiveBoardWindow(
    strokes: List<BoardStroke>,
    partnerJoined: Boolean,
    onStroke: (id: String, points: List<LiveBoardSession.Point>, color: Long, width: Float, done: Boolean) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // The widest the window may ever be: the screen less a 5mm margin each
    // side. Expressed in millimetres because that is what the requirement is
    // in, and because a dp margin would be a different physical size on every
    // device — which is the thing a margin is trying to control.
    val marginPx = mmToPx(5f)
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val maxWidthPx = (screenWidthPx - 2 * marginPx).coerceAtLeast(1f)

    var scale by rememberSaveable { mutableStateOf(1f) }

    // Where the window sits, in pixels from the top-left of the overlay. Drag
    // the header to move it; both the window and the collapsed bubble share
    // this, so collapsing does not teleport it back to a default corner.
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // Everything visible is animated rather than set directly, which is what
    // makes resizing read as elastic instead of as a value jumping between
    // frames. Low stiffness with slight bounce is the "settles into place"
    // feel rather than a mechanical ease.
    val animatedScale by animateFloatAsState(
        targetValue = scale,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "boardScale"
    )
    val animatedX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium),
        label = "boardX"
    )
    val animatedY by animateFloatAsState(
        targetValue = offsetY,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium),
        label = "boardY"
    )

    val collapsed = scale <= COLLAPSE_AT

    if (collapsed) {
        // Rolled into a bubble, and draggable in its own right: a bubble
        // pinned to one spot would sit over whatever happens to be there.
        // Tapping restores it — there is no way to drag it back open, because
        // at this size the resize handle would be smaller than a fingertip.
        Box(
            modifier = modifier
                .offset { IntOffset(animatedX.roundToInt(), animatedY.roundToInt()) }
                .padding(12.dp)
                .size(58.dp)
                .pointerInput(Unit) {
                    detectDragGestures { _, drag ->
                        offsetX += drag.x
                        offsetY += drag.y
                    }
                }
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().clickable { scale = 1f },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.board_title),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        return
    }

    val widthPx = maxWidthPx * animatedScale
    val widthDp = with(density) { widthPx.toDp() }
    val heightDp = widthDp * BOARD_RATIO

    var colorIndex by rememberSaveable { mutableStateOf(0) }
    var widthIndex by rememberSaveable { mutableStateOf(1) }

    // Canvas transform, separate from the window size: pinch and rotate move
    // the drawing under the frame, they do not resize the frame itself.
    var canvasScale by remember { mutableStateOf(1f) }
    var canvasRotation by remember { mutableStateOf(0f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }

    Surface(
        modifier = modifier
            .offset { IntOffset(animatedX.roundToInt(), animatedY.roundToInt()) }
            .size(width = widthDp, height = heightDp),
        shape = RoundedCornerShape(28.dp),
        // White, not the theme surface: the canvas is white, and a tinted frame
        // around a white page makes the page look like a hole rather than like
        // paper. It also keeps the board identical in light, dark and black
        // themes, so a drawing looks the same to both people whatever either
        // has chosen.
        color = Color.White,
        shadowElevation = 14.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: close on the left, as specified, so the destructive
            // control is never where a thumb rests while drawing. Dragging it
            // moves the window — the whole surface cannot be the handle,
            // because that is where the drawing happens.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag ->
                            offsetX += drag.x
                            offsetY += drag.y
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.board_close),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.board_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        // Explicit dark text: the frame is always white, so
                        // theme colours would render invisible in dark mode.
                        color = Color(0xFF1A1A1A)
                    )
                    Text(
                        stringResource(
                            if (partnerJoined) R.string.board_both_here
                            else R.string.board_waiting
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF6B6B6B)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onClear() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.board_clear),
                        tint = Color(0xFF6B6B6B),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                BoardPalette(
                    selected = colorIndex,
                    onSelect = { colorIndex = it },
                    modifier = Modifier.padding(start = 6.dp)
                )

                BoardCanvas(
                    strokes = strokes,
                    color = BOARD_COLOURS[colorIndex],
                    strokeWidth = BOARD_WIDTHS[widthIndex],
                    canvasScale = canvasScale,
                    canvasRotation = canvasRotation,
                    canvasOffset = canvasOffset,
                    onTransform = { s, r, o ->
                        canvasScale = s; canvasRotation = r; canvasOffset = o
                    },
                    onResetTransform = {
                        canvasScale = 1f; canvasRotation = 0f; canvasOffset = Offset.Zero
                    },
                    onStroke = { id, pts, done ->
                        onStroke(
                            id, pts,
                            BOARD_COLOURS[colorIndex].value.toLong(),
                            BOARD_WIDTHS[widthIndex],
                            done
                        )
                    },
                    modifier = Modifier.weight(1f).padding(4.dp)
                )

                BoardWidthPicker(
                    selected = widthIndex,
                    onSelect = { widthIndex = it },
                    tint = BOARD_COLOURS[colorIndex],
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            // Resize arc, bottom-right. Dragging out grows the window and
            // dragging in shrinks it, always along the diagonal so the ratio
            // is preserved without the user having to keep it themselves.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(30.dp)
                        .clip(RoundedCornerShape(topStart = 30.dp))
                        .background(Color(0xFFE8E8E8))
                        .pointerInput(maxWidthPx) {
                            detectDragGestures { _, drag ->
                                // Average the two axes: a diagonal drag is the
                                // gesture, and using only x would make vertical
                                // movement feel dead.
                                val delta = (drag.x + drag.y) / 2f
                                // Damped: a 1:1 mapping makes the corner feel
                                // twitchy near the collapse threshold, where
                                // the smallest movements matter most. The
                                // spring above smooths what is left.
                                scale = (scale + (delta / maxWidthPx) * 0.85f)
                                    .coerceIn(0.12f, 1f)
                            }
                        }
                )
            }
        }
    }
}

/**
 * Millimetres in pixels.
 *
 * The margin requirement is in millimetres, and a dp margin would be a
 * different physical size on every device — which is precisely what a margin
 * expressed in millimetres is trying to pin down.
 */
private fun mmToPx(mm: Float): Float = android.util.TypedValue.applyDimension(
    android.util.TypedValue.COMPLEX_UNIT_MM,
    mm,
    android.content.res.Resources.getSystem().displayMetrics
)

@Composable
private fun BoardPalette(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(30.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BOARD_COLOURS.forEachIndexed { i, c ->
            Box(
                modifier = Modifier
                    .size(if (i == selected) 24.dp else 18.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(
                        width = if (i == selected) 2.dp else 1.dp,
                        color = if (i == selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
                    .clickable { onSelect(i) }
            )
        }
    }
}

@Composable
private fun BoardWidthPicker(
    selected: Int,
    onSelect: (Int) -> Unit,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(30.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BOARD_WIDTHS.forEachIndexed { i, w ->
            // The dot is the brush at roughly the size it will draw, so the
            // choice is made by looking rather than by guessing at a label.
            val dot = (6 + w * 240).dp
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else Color.Transparent
                    )
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(dot)
                        .clip(CircleShape)
                        .background(tint)
                )
            }
        }
    }
}
