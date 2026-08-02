package com.neveroff.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sign

/**
 * Circular video message with a perimeter timeline: the ring starts at 12 o'clock and sweeps
 * clockwise, one full turn being the whole clip. Dragging around the rim scrubs.
 *
 * Winding past either end doesn't clamp dead — the surplus rotation spins the whole circle in
 * the direction of travel and springs back when the finger lifts, so overshooting reads as
 * elastic rather than broken.
 */
@Composable
fun CircleVideoScrubber(
    progress: Float,
    ringColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 3.dp,
    onSeek: (Float) -> Unit,
    onScrubbingChange: (Boolean) -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubProgress by remember { mutableFloatStateOf(0f) }
    /** Surplus rotation beyond a single turn, in degrees, damped. Drives the body spin. */
    val spin = remember { Animatable(0f) }

    val shown = if (isScrubbing) scrubProgress else progress.coerceIn(0f, 1f)

    val report by rememberUpdatedState(onScrubbingChange)
    LaunchedEffect(isScrubbing) { report(isScrubbing) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width / 2f, size.height / 2f)

                    isScrubbing = true
                    var lastAngle = angleFromTop(down.position - center)
                    // Continue from wherever playback is, so the grab doesn't jump the video.
                    var wound = shown * 360f
                    scrubProgress = shown

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()

                        val angle = angleFromTop(change.position - center)
                        // Unwrap across the 359->0 seam so a continuous drag accumulates.
                        var delta = angle - lastAngle
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        lastAngle = angle
                        wound += delta

                        scrubProgress = (wound / 360f).coerceIn(0f, 1f)
                        onSeek(scrubProgress)

                        val surplus = when {
                            wound < 0f -> wound
                            wound > 360f -> wound - 360f
                            else -> 0f
                        }
                        scope.launch { spin.snapTo(dampenSpin(surplus)) }
                    }

                    isScrubbing = false
                    scope.launch {
                        spin.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = spin.value },
            content = content
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = with(density) { ringWidth.toPx() }
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * shown,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke)
            )

            // Knob at the head of the arc, so it's obvious which point on the rim is selected.
            // Grows while dragging since that's when the finger is covering it.
            val headRad = Math.toRadians((360.0 * shown) - 90.0)
            val radius = (size.minDimension - stroke) / 2f
            val head = Offset(
                x = size.width / 2f + (radius * kotlin.math.cos(headRad)).toFloat(),
                y = size.height / 2f + (radius * kotlin.math.sin(headRad)).toFloat()
            )
            val knob = stroke * (if (isScrubbing) 2.6f else 1.8f)
            // Outline keeps the knob readable against a bright frame of video behind it.
            drawCircle(color = trackColor, radius = knob + stroke * 0.5f, center = head)
            drawCircle(color = ringColor, radius = knob, center = head)
        }
    }
}

/** Degrees clockwise from 12 o'clock. Screen y grows downward, hence the +90 rotation. */
private fun angleFromTop(v: Offset): Float =
    ((Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat() + 90f) + 360f) % 360f

/** Sub-linear and capped, so winding hard doesn't send the circle spinning away. */
private fun dampenSpin(surplusDegrees: Float): Float =
    surplusDegrees.sign * abs(surplusDegrees).pow(0.7f).coerceAtMost(40f)
