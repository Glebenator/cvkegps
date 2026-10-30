package com.example.cvkegps.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val LocalCvkeColors = staticCompositionLocalOf { LightTokens }

/** The design-language tokens that have no exact Material role, such as status and brand colours. */
object CvkeTheme {
    val colors: CvkeColors
        @Composable @ReadOnlyComposable get() = LocalCvkeColors.current
}

/**
 * By default, dynamic colour applies only to the neutral surfaces and lines, which the wallpaper tints a little.
 * Text, the accent, status, data, and brand colours stay fixed, so the app keeps its one electric blue.
 * The tones match the token neutrals: a light page with white cards, or a dark page with lighter cards.
 */
private fun CvkeColors.withWallpaperNeutrals(wallpaper: ColorScheme, dark: Boolean) = copy(
    bg = if (dark) wallpaper.surface else wallpaper.surfaceContainerLow,
    surface = if (dark) wallpaper.surfaceContainer else wallpaper.surfaceContainerLowest,
    surfaceSunken = if (dark) wallpaper.surfaceContainerLowest else wallpaper.surfaceContainer,
    border = wallpaper.outlineVariant,
    borderControl = wallpaper.outline,
)

/**
 * The user's choice of wallpaper colours: the accent, text, and neutrals all come from the wallpaper.
 * Status, data, and brand colours stay fixed, so they keep their meaning.
 */
private fun CvkeColors.withWallpaperColours(wallpaper: ColorScheme, dark: Boolean) = withWallpaperNeutrals(wallpaper, dark).copy(
    ink = wallpaper.onSurface,
    inkMuted = wallpaper.onSurfaceVariant,
    accent = wallpaper.primary,
    accentInk = wallpaper.onPrimary,
    accentSubtle = wallpaper.primaryContainer,
    // The wallpaper primary can be too light on its container, so the container gets its own text colour.
    accentOnSubtle = wallpaper.onPrimaryContainer,
)

/** Material roles from the design tokens. The page is `bg`; cards, bars, sheets, and menus are `surface`. */
private fun CvkeColors.scheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = accentInk, primaryContainer = accentSubtle, onPrimaryContainer = accentOnSubtle,
        inversePrimary = if (dark) LightTokens.accent else DarkTokens.accent,
        secondary = accent, onSecondary = accentInk, secondaryContainer = accentSubtle, onSecondaryContainer = accentOnSubtle,
        tertiary = accent, onTertiary = accentInk, tertiaryContainer = surfaceSunken, onTertiaryContainer = ink,
        background = bg, onBackground = ink,
        surface = bg, onSurface = ink, surfaceVariant = surfaceSunken, onSurfaceVariant = inkMuted,
        surfaceTint = bg, inverseSurface = ink, inverseOnSurface = bg,
        error = danger, onError = dangerInk, errorContainer = dangerSubtle, onErrorContainer = ink,
        outline = borderControl, outlineVariant = border, scrim = scrim,
        surfaceBright = surface, surfaceDim = surfaceSunken,
        surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = surfaceSunken,
    )
}

private fun style(size: Int, line: Int, weight: FontWeight, spacing: Float = 0f) = TextStyle(
    fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.em,
)

// Headings are weight 500. Size, not weight, sets the hierarchy. No style is below 12 sp.
private val display = style(28, 34, FontWeight.Medium, -0.01f)
private val title = style(20, 26, FontWeight.Medium)
private val heading = style(16, 22, FontWeight.Medium)
private val body = style(14, 20, FontWeight.Normal)
private val bodyStrong = style(14, 20, FontWeight.SemiBold)
private val small = style(12, 16, FontWeight.Normal)
private val label = style(12, 16, FontWeight.SemiBold, 0.01f)

private val CvkeTypography = Typography(
    displayLarge = display, displayMedium = display, displaySmall = display,
    // Material dialogs use headlineSmall for their title, so it is the title style.
    headlineLarge = display, headlineMedium = display, headlineSmall = title,
    titleLarge = title, titleMedium = heading, titleSmall = bodyStrong,
    bodyLarge = body, bodyMedium = body, bodySmall = small,
    labelLarge = bodyStrong, labelMedium = label, labelSmall = label,
)

// Touch shapes: inputs and menus 8 dp, cards and sheets 16 dp. Material buttons are already pills.
private val CvkeShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.md),
    small = RoundedCornerShape(Radius.md),
    medium = RoundedCornerShape(Radius.lg),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.lg),
)

@Composable
fun CvkeGpsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    wallpaperColours: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Android 12 and later give wallpaper colours. Earlier versions use the token colours.
    val wallpaper = remember(context, configuration, darkTheme) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null
        else if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    val tokens = remember(wallpaper, darkTheme, wallpaperColours) {
        val fixed = if (darkTheme) DarkTokens else LightTokens
        when {
            wallpaper == null -> fixed
            wallpaperColours -> fixed.withWallpaperColours(wallpaper, darkTheme)
            else -> fixed.withWallpaperNeutrals(wallpaper, darkTheme)
        }
    }
    val scheme = remember(tokens, darkTheme, wallpaper, wallpaperColours) {
        tokens.scheme(darkTheme).let { if (wallpaper != null && wallpaperColours) it.copy(inversePrimary = wallpaper.inversePrimary) else it }
    }
    CompositionLocalProvider(LocalCvkeColors provides tokens) {
        MaterialTheme(colorScheme = scheme, typography = CvkeTypography, shapes = CvkeShapes, content = content)
    }
}
