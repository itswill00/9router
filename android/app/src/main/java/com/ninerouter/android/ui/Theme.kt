package com.ninerouter.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninerouter.android.R

/**
 * Carbon & Ember palette.
 *
 * The dashboard keeps the upstream orange signal, but the canvas is deeper and
 * the surfaces are quieter so status colors and provider art carry the page.
 */
object NineRouterColors {
    val Brand = Color(0xFFFF7A3D)
    val BrandDark = Color(0xFFE45E22)
    val BrandOrange = Color(0xFFFF7A3D)
    val BrandOrangeDark = Color(0xFFD9541C)
    val BrandAmber = Color(0xFFFFB454)

    val Bg = Color(0xFF0B0B0E)
    val BgAlt = Color(0xFF101014)
    val Surface = Color(0xFF16161C)
    val Surface2 = Color(0xFF1D1D24)
    val Surface3 = Color(0xFF26262F)
    val SurfaceElevated = Color(0xFF1B1B22)
    val SurfaceSunken = Color(0xFF08080A)

    val Border = Color(0xFF2A2A34)
    val BorderSubtle = Color(0xFF202028)
    val BorderStrong = Color(0xFF3A3A46)

    val TextMain = Color(0xFFF4F4F6)
    val TextMuted = Color(0xFFA6A6B2)
    val TextSubtle = Color(0xFF7E7E8C)
    val TextFaint = Color(0xFF5E5E6B)

    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFC857)
    val Danger = Color(0xFFFF6B6B)
    val Info = Color(0xFF6BA8FF)

    val EmberStart = Color(0xFFFF8A4C)
    val EmberEnd = Color(0xFFFFB454)
    val Glow = Color(0x33FF7A3D)
}

object NineRouterFonts {
    val Ui: FontFamily = FontFamily(
        Font(R.font.manrope_regular, FontWeight.Normal),
        Font(R.font.manrope_medium, FontWeight.Medium),
        Font(R.font.manrope_semibold, FontWeight.SemiBold),
        Font(R.font.manrope_bold, FontWeight.Bold),
    )

    val Mono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    )
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
    val xxxl = 32.dp
}

object NineRouterShapes {
    val small = RoundedCornerShape(12.dp)
    val medium = RoundedCornerShape(16.dp)
    val large = RoundedCornerShape(22.dp)
    val pill = RoundedCornerShape(999.dp)
}

private val NineRouterTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.7).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = NineRouterFonts.Ui,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.1.sp,
    ),
)

@Composable
fun NineRouterTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = NineRouterColors.BrandOrange,
        onPrimary = Color(0xFF1A0B04),
        secondary = NineRouterColors.BrandAmber,
        onSecondary = Color(0xFF1A0B04),
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
    ) {
        // Compose defaults LocalContentColor to black; on our dark surfaces that
        // paints dark ripples/focus overlays. Keep interaction feedback light.
        CompositionLocalProvider(LocalContentColor provides NineRouterColors.TextMain) {
            I18nProvider {
                content()
            }
        }
    }
}
