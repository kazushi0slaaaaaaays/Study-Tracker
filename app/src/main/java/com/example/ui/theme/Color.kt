package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Dark palette (default)
val DarkBg = Color(0xFF0B0C10)
val DarkSurface = Color(0xFF14161C)
val DarkSurface2 = Color(0xFF1C1F27)
val DarkBorder = Color(0xFF262A34)
val DarkText = Color(0xFFF2F4F8)
val DarkTextDim = Color(0xFF9AA3B2)

// AMOLED palette
val AmoledBg = Color(0xFF000000)
val AmoledSurface = Color(0xFF0A0A0C)
val AmoledSurface2 = Color(0xFF121316)
val AmoledBorder = Color(0xFF1A1C22)
val AmoledText = Color(0xFFF2F4F8)
val AmoledTextDim = Color(0xFF8A93A2)

// Light palette
val LightBg = Color(0xFFF5F6F8)
val LightSurface = Color(0xFFFFFFFF)
val LightSurface2 = Color(0xFFECEEF2)
val LightBorder = Color(0xFFDDE0E6)
val LightText = Color(0xFF11141A)
val LightTextDim = Color(0xFF5B6472)

// Semantic colors
val DefaultAccent = Color(0xFF6C7BFF)
val SuccessGreen = Color(0xFF3DDC97)
val WarningAmber = Color(0xFFFFB84D)
val DangerRed = Color(0xFFFF5C6C)

// 12 distinct subject hues with equal perceptual lightness
val SubjectPaletteHex = listOf(
    "#6C7BFF", // Periwinkle Indigo
    "#3DDC97", // Mint Emerald
    "#FF7B72", // Coral Red
    "#FFB84D", // Warm Amber
    "#4CC9F0", // Sky Cyan
    "#C084FC", // Soft Violet
    "#F472B6", // Rose Pink
    "#34D399", // Seafoam
    "#FB923C", // Tangerine
    "#60A5FA", // Azure Blue
    "#A3E635", // Chartreuse
    "#E879F9", // Orchid
)

val AccentSwatchesHex = listOf(
    "#6C7BFF",
    "#3DDC97",
    "#4CC9F0",
    "#C084FC",
    "#FF7B72",
    "#FFB84D",
)

fun parseHexColor(hex: String, fallback: Color = DefaultAccent): Color {
    return try {
        val cleaned = hex.trim().removePrefix("#")
        val parsed = cleaned.toLong(16)
        when (cleaned.length) {
            6 -> Color(0xFF000000 or parsed)
            8 -> Color(parsed)
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}

fun Color.subjectTint(): Color = this.copy(alpha = 0.15f)
