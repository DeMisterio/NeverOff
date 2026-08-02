package com.neveroff.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

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
