package com.familyhub.app.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// iPhone-style palette: system gray backgrounds, frosted glass surfaces and iOS blue.
val IosBlue = Color(0xFF007AFF)
val IosBlueDark = Color(0xFF0A84FF)
val IosGrayBg = Color(0xFFF2F2F7)
val IosBlack = Color(0xFF000000)

/** True when the dark glass palette is active. Read by the glass helpers. */
val LocalGlassDark = staticCompositionLocalOf { false }

// Surfaces are see-through so the soft background shows behind cards (the "glass" look).
// Dialogs and menus stay almost solid so their text is always easy to read.
private val LightColors = lightColorScheme(
    primary = IosBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8FF),
    onPrimaryContainer = Color(0xFF00244D),
    secondary = Color(0xFF8E8E93),
    onSecondary = Color.White,
    secondaryContainer = Color(0x99D6E8FF),
    onSecondaryContainer = Color(0xFF00244D),
    tertiary = Color(0xFF34C759),
    background = Color.Transparent,
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xA6FFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0x80FFFFFF),
    onSurfaceVariant = Color(0xFF6C6C70),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xF5F7F7FA),
    surfaceContainer = Color(0xF7F7F7FA),
    surfaceContainerHigh = Color(0xFAF9F9FB),
    surfaceContainerHighest = Color(0xA6FFFFFF),
    outline = Color(0xFFB8B8BE),
    outlineVariant = Color(0x1F3C3C43),
    error = Color(0xFFFF3B30)
)

private val DarkColors = darkColorScheme(
    primary = IosBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0A3D7A),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0xFF98989F),
    onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = Color(0x660A84FF),
    onSecondaryContainer = Color(0xFFD6E8FF),
    tertiary = Color(0xFF30D158),
    background = Color.Transparent,
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0x1FFFFFFF),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0x14FFFFFF),
    onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceContainerLowest = Color(0xFF0B0B0D),
    surfaceContainerLow = Color(0xF21C1C1E),
    surfaceContainer = Color(0xF51C1C1E),
    surfaceContainerHigh = Color(0xF72C2C2E),
    surfaceContainerHighest = Color(0x1FFFFFFF),
    outline = Color(0xFF636366),
    outlineVariant = Color(0x29FFFFFF),
    error = Color(0xFFFF453A)
)

private val base = Typography()

// iPhone-like type: heavier titles with slightly tighter spacing.
private val GlassTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium,
    labelSmall = base.labelSmall
)

private val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun FamilyHubTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassDark provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = GlassTypography,
            shapes = GlassShapes
        ) {
            // The softly moving background sits behind every screen.
            Box(modifier = Modifier.fillMaxSize()) {
                AuroraBackground()
                content()
            }
        }
    }
}
