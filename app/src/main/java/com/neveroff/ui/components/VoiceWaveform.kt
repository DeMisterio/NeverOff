package com.neveroff.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * What the waveform is currently doing to its host bubble.
 *
 * [magnify] (0..1) ramps up while the finger moves slowly and deliberately, so the bubble can
 * grow and give the user a bigger target to aim at. [stretch] is signed rubber-band overscroll
 * in pixels once the finger travels past either end of the track.
 */
data class VoiceScrubState(
    val active: Boolean = false,
    val magnify: Float = 0f,
    val stretch: Float = 0f
)

/** Finger speed (px/ms) at or above which we treat the drag as a coarse seek, not fine-tuning. */
private const val COARSE_SPEED = 1.4f

/** Seek gain when fully magnified — a given finger travel covers ~a third of the timeline. */
private const val FINE_GAIN = 0.35f

@Composable
fun VoiceWaveform(
    bars: FloatArray,
    progress: Float,
    playedColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    barHeight: androidx.compose.ui.unit.Dp = 28.dp,
    onSeek: (Float) -> Unit,
    onScrubStateChange: (VoiceScrubState) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isScrubbing by remember { mutableStateOf(false) }
    // While the finger is down this drives the UI; otherwise playback position does.
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val magnify = remember { Animatable(0f) }
    val stretch = remember { Animatable(0f) }

    val shownFraction = if (isScrubbing) scrubFraction else progress.coerceIn(0f, 1f)

    // Report upward as a single object so the bubble only re-reads one value. Driven by
    // snapshotFlow rather than keyed LaunchedEffect: these values change every animation
    // frame during a scrub, which would otherwise cancel and relaunch a coroutine per frame.
    val currentReport by rememberUpdatedState(onScrubStateChange)
    LaunchedEffect(Unit) {
        snapshotFlow { VoiceScrubState(isScrubbing, magnify.value, stretch.value) }
            .collect { currentReport(it) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight + 16.dp)
            .pointerInput(bars) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)

                    isScrubbing = true
                    // A plain tap jumps straight to that moment.
                    scrubFraction = (down.position.x / width).coerceIn(0f, 1f)
                    onSeek(scrubFraction)

                    var lastX = down.position.x
                    var lastTime = down.uptimeMillis
                    // Travel past an end accumulates here rather than being clamped away.
                    var overscroll = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        // Claim the gesture so the bubble's swipe-to-reply doesn't also fire.
                        change.consume()

                        val x = change.position.x
                        val dx = x - lastX
                        val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                        val speed = abs(dx) / dt
                        lastX = x
                        lastTime = change.uptimeMillis

                        // Slow, deliberate movement => magnify and scrub finely.
                        val target = (1f - speed / COARSE_SPEED).coerceIn(0f, 1f)
                        scope.launch {
                            magnify.animateTo(target, tween(durationMillis = 140))
                        }

                        val gain = 1f - (1f - FINE_GAIN) * magnify.value
                        val next = scrubFraction + (dx / width) * gain

                        when {
                            next < 0f -> overscroll += next * width
                            next > 1f -> overscroll += (next - 1f) * width
                            else -> {
                                // Coming back inside the track unwinds the rubber band first.
                                if (overscroll != 0f) {
                                    val unwound = overscroll + dx
                                    overscroll =
                                        if (overscroll.sign != unwound.sign) 0f else unwound
                                }
                            }
                        }
                        scrubFraction = next.coerceIn(0f, 1f)
                        onSeek(scrubFraction)

                        scope.launch { stretch.snapTo(rubberBand(overscroll)) }
                    }

                    isScrubbing = false
                    scope.launch {
                        magnify.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                    }
                    scope.launch {
                        stretch.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(barHeight + 16.dp)) {
            if (bars.isEmpty()) return@Canvas

            val barW = with(density) { 2.5.dp.toPx() }
            val gap = with(density) { 1.5.dp.toPx() }
            val step = barW + gap
            val drawable = (size.width / step).toInt().coerceAtLeast(1)
            val centerY = size.height / 2f

            // Bars grow a little while magnified so the target is easier to hit. The bubble
            // itself barely scales (it would overlap neighbours), so most of the "zoom in to
            // aim" feedback is carried here, inside the bubble's own bounds.
            val heightScale = 1f + magnify.value * 0.45f
            val maxH = with(density) { barHeight.toPx() } * heightScale

            for (i in 0 until drawable) {
                // Map the drawable slot onto the source bar array.
                val amp = bars[(i.toLong() * bars.size / drawable).toInt().coerceIn(0, bars.size - 1)]
                val h = (amp * maxH).coerceAtLeast(with(density) { 2.dp.toPx() })
                val x = i * step + barW / 2f
                val played = (i + 0.5f) / drawable <= shownFraction

                drawLine(
                    color = if (played) playedColor else trackColor,
                    start = Offset(x, centerY - h / 2f),
                    end = Offset(x, centerY + h / 2f),
                    strokeWidth = barW,
                    cap = StrokeCap.Round
                )
            }

            // Playhead: only while scrubbing, so the resting bubble stays clean.
            if (magnify.value > 0.01f || isScrubbing) {
                val px = shownFraction * size.width
                drawLine(
                    color = playedColor,
                    start = Offset(px, centerY - maxH / 2f - 4.dp.toPx()),
                    end = Offset(px, centerY + maxH / 2f + 4.dp.toPx()),
                    strokeWidth = with(density) { 1.5.dp.toPx() },
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = playedColor,
                    radius = with(density) { (3.5.dp.toPx()) } * (1f + magnify.value * 0.5f),
                    center = Offset(px, centerY)
                )
            }
        }
    }
}

/** Sub-linear so the bubble resists further the harder you pull, and never runs away. */
private fun rubberBand(overscrollPx: Float): Float =
    overscrollPx.sign * abs(overscrollPx).pow(0.6f).coerceAtMost(48f)
