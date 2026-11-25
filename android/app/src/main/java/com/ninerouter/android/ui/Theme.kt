package com.ninerouter.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palette transcribed from src/app/globals.css (9Router dark theme). */
object NineRouterColors {
    val Brand = Color(0xFFE56A4A)
    val BrandDark = Color(0xFFCC5236)
    val BrandOrange = Color(0xFFF97815)
    val BrandOrangeDark = Color(0xFFC2590A)

    val Bg = Color(0xFF1A1A1A)
    val BgAlt = Color(0xFF1F1F1E)
    val Surface = Color(0xFF262626)
    val Surface2 = Color(0xFF303030)
    val Surface3 = Color(0xFF3A3A3A)

    val Border = Color(0xFF333333)
    val BorderSubtle = Color(0xFF2A2A2A)

    val TextMain = Color(0xFFEDEDED)
    val TextMuted = Color(0xFF9CA3AF)
    val TextSubtle = Color(0xFF6B7280)

    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFEF4444)
    val Info = Color(0xFF60A5FA)
}

@Composable
fun NineRouterTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = NineRouterColors.Brand,
        onPrimary = Color.White,
        secondary = NineRouterColors.BrandOrange,
        onSecondary = Color.White,
        background = NineRouterColors.Bg,
        onBackground = NineRouterColors.TextMain,
        surface = NineRouterColors.Surface,
        onSurface = NineRouterColors.TextMain,
        surfaceVariant = NineRouterColors.Surface2,
        onSurfaceVariant = NineRouterColors.TextMuted,
        outline = NineRouterColors.Border,
        error = NineRouterColors.Danger,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content,
    )
}
