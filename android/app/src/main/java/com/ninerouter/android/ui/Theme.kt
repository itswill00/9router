package com.ninerouter.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val SurfaceElevated = Color(0xFF2B2B2B)
    val SurfaceSunken = Color(0xFF151515)

    val Border = Color(0xFF333333)
    val BorderSubtle = Color(0xFF2A2A2A)
    val BorderStrong = Color(0xFF454545)

    val TextMain = Color(0xFFEDEDED)
    val TextMuted = Color(0xFF9CA3AF)
    val TextSubtle = Color(0xFF6B7280)
    val TextFaint = Color(0xFF52525B)

    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFEF4444)
    val Info = Color(0xFF60A5FA)
}

/** 4/8dp rhythm shared by every screen. */
object NineRouterSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

object NineRouterShapes {
    val small = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(14.dp)
    val large = RoundedCornerShape(18.dp)
    val pill = RoundedCornerShape(999.dp)
}

private val NineRouterTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.1.sp,
    ),
)

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
        surfaceContainer = NineRouterColors.SurfaceElevated,
        surfaceContainerHigh = NineRouterColors.Surface2,
        surfaceContainerHighest = NineRouterColors.Surface3,
        outline = NineRouterColors.Border,
        outlineVariant = NineRouterColors.BorderSubtle,
        error = NineRouterColors.Danger,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = NineRouterTypography,
        shapes = Shapes(
            extraSmall = NineRouterShapes.small,
            small = NineRouterShapes.small,
            medium = NineRouterShapes.medium,
            large = NineRouterShapes.large,
            extraLarge = NineRouterShapes.large,
        ),
        content = content,
    )
}
