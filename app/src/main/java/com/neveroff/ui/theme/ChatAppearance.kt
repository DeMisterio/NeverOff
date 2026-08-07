package com.neveroff.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Colour maths for user-chosen chat themes.
 *
 * The point of this file is that a user can pick any colour at all, including
 * ones that make text unreadable on them. Nothing here trusts the picked colour
 * to be sensible: text colour is computed from the background it lands on
 * rather than stored alongside it, so there is no way to save a combination
 * that renders as invisible text.
 */
object ChatAppearance {

    /**
     * Black or white, whichever is legible on [background].
     *
     * Uses WCAG contrast ratio rather than a luminance threshold. The naive
     * "luminance > 0.5 means use black" rule misjudges saturated mid-tones —
     * a strong blue and a strong yellow can share a luminance while needing
     * opposite text colours — so both candidates are measured and the better
     * one wins.
     */
    fun textColorOn(background: Color): Color {
        val onBlack = contrastRatio(background, Color.Black)
        val onWhite = contrastRatio(background, Color.White)
        return if (onBlack >= onWhite) Color.Black else Color.White
    }

    /**
     * A dimmer version of [textColorOn] for timestamps and delivery ticks,
     * still holding at least the WCAG AA large-text ratio of 3:1.
     *
     * Secondary text is usually drawn at some alpha of the primary colour, which
     * is exactly the operation that can quietly push it under the readable
     * threshold. This backs off the alpha until the contrast holds.
     */
    fun secondaryTextColorOn(background: Color): Color {
        val base = textColorOn(background)
        var alpha = 0.7f
        while (alpha < 1f) {
            val blended = blend(base.copy(alpha = alpha), background)
            if (contrastRatio(background, blended) >= AA_LARGE) return base.copy(alpha = alpha)
            alpha += 0.05f
        }
        return base
    }

    /**
     * WCAG 2.1 relative-contrast ratio between two opaque colours, 1.0 to 21.0.
     */
    fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    /** Flattens a translucent [foreground] onto an opaque [background]. */
    fun blend(foreground: Color, background: Color): Color {
        val a = foreground.alpha
        return Color(
            red = foreground.red * a + background.red * (1 - a),
            green = foreground.green * a + background.green * (1 - a),
            blue = foreground.blue * a + background.blue * (1 - a),
            alpha = 1f
        )
    }

    /**
     * Builds a full chat palette from one accent colour.
     *
     * The outgoing bubble is the accent itself. The incoming bubble is a
     * strongly desaturated, tone-shifted cousin rather than a second hue: two
     * saturated colours in one conversation compete, and the incoming side
     * should read as neutral so the accent stays meaningful.
     *
     * The shift goes away from the accent's own lightness so the two bubbles
     * stay distinguishable whether the accent is dark or light.
     */
    fun paletteFromAccent(accent: Color, darkTheme: Boolean): ChatPalette {
        val hsl = accent.toHsl()
        val incomingLightness = if (darkTheme) {
            (hsl.lightness * 0.35f + 0.14f).coerceIn(0.12f, 0.30f)
        } else {
            (hsl.lightness * 0.25f + 0.80f).coerceIn(0.78f, 0.94f)
        }
        val incoming = Hsl(hsl.hue, hsl.saturation * 0.22f, incomingLightness).toColor()

        // A wallpaper that is nearly the bubble colour makes bubbles vanish into
        // it, so the backdrop is pushed well away from the accent's lightness.
        val backdropLightness = if (darkTheme) 0.08f else 0.97f
        val backdrop = Hsl(hsl.hue, hsl.saturation * 0.18f, backdropLightness).toColor()

        return ChatPalette(
            outgoing = accent,
            incoming = incoming,
            backdrop = backdrop,
            onOutgoing = textColorOn(accent),
            onIncoming = textColorOn(incoming)
        )
    }

    /**
     * Nudges [bubble] until text on it is readable, keeping its hue.
     *
     * Used for colours the user picked by hand rather than derived from an
     * accent. A mid-grey is the one case where neither black nor white text is
     * comfortable, so it gets pushed towards whichever end is closer.
     */
    fun ensureReadable(bubble: Color): Color {
        val best = max(contrastRatio(bubble, Color.Black), contrastRatio(bubble, Color.White))
        if (best >= AA_LARGE) return bubble

        val hsl = bubble.toHsl()
        val goingDark = hsl.lightness < 0.5f
        var lightness = hsl.lightness
        repeat(20) {
            lightness = if (goingDark) lightness - 0.03f else lightness + 0.03f
            val candidate = Hsl(hsl.hue, hsl.saturation, lightness.coerceIn(0f, 1f)).toColor()
            val ratio = max(
                contrastRatio(candidate, Color.Black),
                contrastRatio(candidate, Color.White)
            )
            if (ratio >= AA_LARGE) return candidate
        }
        return Hsl(hsl.hue, hsl.saturation, if (goingDark) 0.12f else 0.9f).toColor()
    }

    /** WCAG AA for large text. Bubble text is 16sp and bold-ish, so this is the bar. */
    const val AA_LARGE = 3.0f
}

/** An outgoing/incoming pair plus the text colours that stay readable on them. */
data class ChatPalette(
    val outgoing: Color,
    val incoming: Color,
    val backdrop: Color,
    val onOutgoing: Color,
    val onIncoming: Color
)

/** HSL, because lightness is the axis worth moving along when fixing contrast. */
data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    val delta = maxC - minC
    val lightness = (maxC + minC) / 2f

    if (delta == 0f) return Hsl(0f, 0f, lightness)

    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val hue = when (maxC) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return Hsl(if (hue < 0) hue + 360f else hue, saturation.coerceIn(0f, 1f), lightness)
}

fun Hsl.toColor(): Color {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = lightness - c / 2f
    val (r, g, b) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        (r + m).coerceIn(0f, 1f),
        (g + m).coerceIn(0f, 1f),
        (b + m).coerceIn(0f, 1f),
        1f
    )
}
