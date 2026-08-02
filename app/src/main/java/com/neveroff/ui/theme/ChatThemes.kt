package com.neveroff.ui.theme

import androidx.compose.ui.graphics.Color

data class ChatThemePalette(
    val name: String,
    val gradientStart: Long,
    val gradientEnd: Long,
    val myBubbleColor: Long,
    val otherBubbleColor: Long
)

object ChatThemes {
    val predefinedThemes = listOf(
        ChatThemePalette(
            name = "Vintage",
            gradientStart = 0xFFE2D1C3,
            gradientEnd = 0xFFFDFCFB,
            myBubbleColor = 0xFF8B5A2B,
            otherBubbleColor = 0xFFCDBA96
        ),
        ChatThemePalette(
            name = "Modernism",
            gradientStart = 0xFF1E1E1E,
            gradientEnd = 0xFF3A3A3A,
            myBubbleColor = 0xFF00ADB5,
            otherBubbleColor = 0xFF393E46
        ),
        ChatThemePalette(
            name = "Love",
            gradientStart = 0xFFFF9A9E,
            gradientEnd = 0xFFFECFEF,
            myBubbleColor = 0xFFFF5252,
            otherBubbleColor = 0xFFFF8A80
        ),
        ChatThemePalette(
            name = "Ocean",
            gradientStart = 0xFF2193b0,
            gradientEnd = 0xFF6dd5ed,
            myBubbleColor = 0xFF0288D1,
            otherBubbleColor = 0xFF81D4FA
        ),
        ChatThemePalette(
            name = "Forest",
            gradientStart = 0xFF11998e,
            gradientEnd = 0xFF38ef7d,
            myBubbleColor = 0xFF2E7D32,
            otherBubbleColor = 0xFFA5D6A7
        ),
        ChatThemePalette(
            name = "Sunset",
            gradientStart = 0xFFff7e5f,
            gradientEnd = 0xFFfeb47b,
            myBubbleColor = 0xFFD84315,
            otherBubbleColor = 0xFFFFCCBC
        ),
        ChatThemePalette(
            name = "Cyberpunk",
            gradientStart = 0xFF0F2027,
            gradientEnd = 0xFF203A43,
            myBubbleColor = 0xFFFF007F,
            otherBubbleColor = 0xFF00FFCC
        ),
        ChatThemePalette(
            name = "Lavender",
            gradientStart = 0xFFE0C3FC,
            gradientEnd = 0xFF8EC5FC,
            myBubbleColor = 0xFF7B1FA2,
            otherBubbleColor = 0xFFCE93D8
        ),
        ChatThemePalette(
            name = "Coffee",
            gradientStart = 0xFFCBB4A6,
            gradientEnd = 0xFFE8DED8,
            myBubbleColor = 0xFF4E342E,
            otherBubbleColor = 0xFFA1887F
        ),
        ChatThemePalette(
            name = "Mint",
            gradientStart = 0xFF00b09b,
            gradientEnd = 0xFF96c93d,
            myBubbleColor = 0xFF00695C,
            otherBubbleColor = 0xFF80CBC4
        ),
        ChatThemePalette(
            name = "Royal",
            gradientStart = 0xFF141E30,
            gradientEnd = 0xFF243B55,
            myBubbleColor = 0xFFFBC02D,
            otherBubbleColor = 0xFFFFF59D
        ),
        ChatThemePalette(
            name = "Sakura",
            gradientStart = 0xFFffb199,
            gradientEnd = 0xFFff0844,
            myBubbleColor = 0xFFC2185B,
            otherBubbleColor = 0xFFF48FB1
        )
    )
}
