package com.appdevper.mediaplayer.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens in the style of ChatGPT: near-monochrome surfaces, a light grey sidebar,
 * black/white primary actions, hairline borders and one green status accent.
 */
@Immutable
data class AppColors(
    val background: Color,
    val sidebar: Color,
    val surface: Color,
    val surfaceSecondary: Color,
    val hover: Color,
    val selected: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val onPrimary: Color,
    val success: Color,
    val danger: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val LightAppColors = AppColors(
    background = Color(0xFFFFFFFF),
    sidebar = Color(0xFFF9F9F9),
    surface = Color(0xFFFFFFFF),
    surfaceSecondary = Color(0xFFF4F4F4),
    hover = Color(0xFFF0F0F0),
    selected = Color(0xFFE8E8E8),
    border = Color(0x1A0D0D0D),
    textPrimary = Color(0xFF0D0D0D),
    textSecondary = Color(0xFF5D5D5D),
    textTertiary = Color(0xFF8F8F8F),
    primary = Color(0xFF0D0D0D),
    onPrimary = Color(0xFFFFFFFF),
    success = Color(0xFF10A37F),
    danger = Color(0xFFE02E2A),
    scrim = Color(0x66000000),
    isDark = false,
)

val DarkAppColors = AppColors(
    background = Color(0xFF212121),
    sidebar = Color(0xFF171717),
    surface = Color(0xFF2F2F2F),
    surfaceSecondary = Color(0xFF303030),
    hover = Color(0xFF2A2A2A),
    selected = Color(0xFF2F2F2F),
    border = Color(0x1AFFFFFF),
    textPrimary = Color(0xFFECECEC),
    textSecondary = Color(0xFFB4B4B4),
    textTertiary = Color(0xFF8E8E8E),
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF0D0D0D),
    success = Color(0xFF10A37F),
    danger = Color(0xFFF87171),
    scrim = Color(0x99000000),
    isDark = true,
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

/** Corner radii used across the app. */
object Radius {
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val xLarge = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(percent = 50)
}

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) DarkAppColors else LightAppColors
    // Material components (sliders, switches, menus, text fields) follow the same monochrome palette.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary,
            secondary = c.textSecondary, onSecondary = c.background,
            background = c.background, onBackground = c.textPrimary,
            surface = c.background, onSurface = c.textPrimary,
            surfaceVariant = c.surfaceSecondary, onSurfaceVariant = c.textSecondary,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surfaceSecondary,
            surfaceContainerLow = c.sidebar, surfaceContainerLowest = c.background,
            outline = c.border, outlineVariant = c.border, error = c.danger,
            secondaryContainer = c.selected, onSecondaryContainer = c.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary,
            secondary = c.textSecondary, onSecondary = c.background,
            background = c.background, onBackground = c.textPrimary,
            surface = c.background, onSurface = c.textPrimary,
            surfaceVariant = c.surfaceSecondary, onSurfaceVariant = c.textSecondary,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surfaceSecondary,
            surfaceContainerLow = c.sidebar, surfaceContainerLowest = c.background,
            outline = Color(0xFFD9D9D9), outlineVariant = Color(0xFFE5E5E5), error = c.danger,
            secondaryContainer = c.selected, onSecondaryContainer = c.textPrimary,
        )
    }
    CompositionLocalProvider(LocalAppColors provides c) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = Shapes(small = Radius.small, medium = Radius.medium, large = Radius.large, extraLarge = Radius.xLarge),
            content = content,
        )
    }
}
