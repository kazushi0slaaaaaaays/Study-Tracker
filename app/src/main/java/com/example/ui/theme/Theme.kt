package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Immutable
data class StudyColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val dangerSurface: Color,
    val isDark: Boolean,
)

val LocalStudyColors = staticCompositionLocalOf {
    StudyColors(
        bg = DarkBg,
        surface = DarkSurface,
        surface2 = DarkSurface2,
        border = DarkBorder,
        text = DarkText,
        textDim = DarkTextDim,
        accent = DefaultAccent,
        success = SuccessGreen,
        warning = WarningAmber,
        danger = DangerRed,
        dangerSurface = Color(0xFF1F1216),
        isDark = true,
    )
}

// Radii from spec: 14dp (chips/inputs), 20dp (cards), 28dp (sheets/big buttons)
val StudyShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun StudyTrackerTheme(
    themeMode: String = "dark",
    accentHex: String = "#6C7BFF",
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val effectiveMode = when (themeMode) {
        "dark", "amoled", "light" -> themeMode
        "system" -> if (systemDark) "dark" else "light"
        else -> "dark"
    }
    val accent = parseHexColor(accentHex, DefaultAccent)

    val studyColors = when (effectiveMode) {
        "amoled" -> StudyColors(
            bg = AmoledBg,
            surface = AmoledSurface,
            surface2 = AmoledSurface2,
            border = AmoledBorder,
            text = AmoledText,
            textDim = AmoledTextDim,
            accent = accent,
            success = SuccessGreen,
            warning = WarningAmber,
            danger = DangerRed,
            dangerSurface = Color(0xFF16090C),
            isDark = true,
        )
        "light" -> StudyColors(
            bg = LightBg,
            surface = LightSurface,
            surface2 = LightSurface2,
            border = LightBorder,
            text = LightText,
            textDim = LightTextDim,
            accent = accent,
            success = Color(0xFF159A62),
            warning = Color(0xFFC87D0B),
            danger = Color(0xFFD93043),
            dangerSurface = Color(0xFFFFF1F2),
            isDark = false,
        )
        else -> StudyColors(
            bg = DarkBg,
            surface = DarkSurface,
            surface2 = DarkSurface2,
            border = DarkBorder,
            text = DarkText,
            textDim = DarkTextDim,
            accent = accent,
            success = SuccessGreen,
            warning = WarningAmber,
            danger = DangerRed,
            dangerSurface = Color(0xFF1E1318),
            isDark = true,
        )
    }

    val m3Scheme = if (studyColors.isDark) {
        darkColorScheme(
            primary = studyColors.accent,
            onPrimary = Color.White,
            primaryContainer = studyColors.accent.copy(alpha = 0.18f),
            onPrimaryContainer = studyColors.text,
            secondary = studyColors.success,
            onSecondary = Color(0xFF061B12),
            tertiary = studyColors.warning,
            background = studyColors.bg,
            onBackground = studyColors.text,
            surface = studyColors.surface,
            onSurface = studyColors.text,
            surfaceVariant = studyColors.surface2,
            onSurfaceVariant = studyColors.textDim,
            outline = studyColors.border,
            outlineVariant = studyColors.border,
            error = studyColors.danger,
            onError = Color.White,
            errorContainer = studyColors.dangerSurface,
            onErrorContainer = studyColors.danger,
        )
    } else {
        lightColorScheme(
            primary = studyColors.accent,
            onPrimary = Color.White,
            primaryContainer = studyColors.accent.copy(alpha = 0.14f),
            onPrimaryContainer = studyColors.text,
            secondary = studyColors.success,
            onSecondary = Color.White,
            tertiary = studyColors.warning,
            background = studyColors.bg,
            onBackground = studyColors.text,
            surface = studyColors.surface,
            onSurface = studyColors.text,
            surfaceVariant = studyColors.surface2,
            onSurfaceVariant = studyColors.textDim,
            outline = studyColors.border,
            outlineVariant = studyColors.border,
            error = studyColors.danger,
            onError = Color.White,
            errorContainer = studyColors.dangerSurface,
            onErrorContainer = studyColors.danger,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !studyColors.isDark
                isAppearanceLightNavigationBars = !studyColors.isDark
            }
        }
    }

    CompositionLocalProvider(LocalStudyColors provides studyColors) {
        MaterialTheme(
            colorScheme = m3Scheme,
            typography = AppTypography,
            shapes = StudyShapes,
            content = content,
        )
    }
}
