package com.sportprognoz.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val bg = Color(0xFF0E1420)
    val card = Color(0xFF172030)
    val cardAlt = Color(0xFF1F2A3D)
    val text = Color(0xFFEAF0F7)
    val muted = Color(0xFF8C9AB0)
    val accent = Color(0xFF2EE59D)
    val home = Color(0xFF2EE59D)
    val draw = Color(0xFF8C9AB0)
    val away = Color(0xFF4DA3FF)
    val warn = Color(0xFFFFB648)
    val bad = Color(0xFFFF6B6B)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Palette.bg,
            secondary = Palette.away,
            background = Palette.bg,
            onBackground = Palette.text,
            surface = Palette.card,
            onSurface = Palette.text,
            surfaceVariant = Palette.cardAlt,
            onSurfaceVariant = Palette.muted,
            secondaryContainer = Palette.cardAlt,
            onSecondaryContainer = Palette.accent,
            outline = Palette.cardAlt,
        ),
        content = content,
    )
}
