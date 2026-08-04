package com.neveroff.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Black Theme (AMOLED)
//
// A true black rather than a very dark grey, because that is the whole point:
// on an OLED panel a #000000 pixel is switched off, so the background costs no
// power and reads as genuinely black next to the bezel. Anything above zero,
// including the "dark" theme's navy, lights every pixel and looks washed out
// in a dark room — which is what people mean when they ask for a black theme
// and are given a dark blue one.
//
// Surfaces step up in small increments instead of staying black: a card that
// is exactly as black as the page behind it has no edge, and elevation stops
// meaning anything.
val BlackBackground = Color(0xFF000000)
val BlackSurface = Color(0xFF0A0A0A)
val BlackSurfaceVariant = Color(0xFF1A1A1A)
val BlackSurfaceElevated = Color(0xFF141414)
val BlackText = Color(0xFFEDEDED)

// Dark Theme (Dark Blue)
val DarkBackground = Color(0xFF051024)
val DarkSurface = Color(0xFF0A1C38)
val DarkSurfaceVariant = Color(0xFF16325C)
val DarkSurfaceElevated = Color(0xFF122A4E)
val DarkText = Color(0xFFE7ECF5)

// Light Theme (Rose White / Cream)
val LightBackground = Color(0xFFFFF5F5)
val LightSurface = Color(0xFFFFFAFA)
val LightSurfaceVariant = Color(0xFFFDE8E8)
val LightSurfaceElevated = Color(0xFFFFFFFF)
val LightText = Color(0xFF2C2525)

// Common
val AccentPurple = Color(0xFF9D7BFF)
val AccentPurpleLight = Color(0xFFC3AFFF)
val AccentPurpleDeep = Color(0xFF6C4FD1)

/** Soft glow/gradient pair used behind logos, avatars, and hero surfaces. */
val AccentGradient = listOf(AccentPurpleLight, AccentPurple, AccentPurpleDeep)

fun Color.readableContentColor(): Color {
    return if (this.luminance() > 0.5f) Color(0xFF222222) else Color(0xFFEEEEEE)
}
