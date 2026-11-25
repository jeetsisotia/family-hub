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

// Gray glass with an olive accent.
val Olive = Color(0xFF5C6B2E)
val OliveLight = Color(0xFFBFD08A)
val OliveDeep = Color(0xFF3F4B1C)
val Sage = Color(0xFF9AA27A)
val WarmGray = Color(0xFFEEEFEA)
val Graphite = Color(0xFF121311)

/** True when the dark glass palette is active. Read by the glass helpers. */
val LocalGlassDark = staticCompositionLocalOf { false }

// Surfaces are see-through so the soft background shows behind cards (the "glass" look).
// Dialogs and menus stay almost solid so their text is always easy to read.
private val LightColors = lightColorScheme(
    primary = Olive,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5C2),
    onPrimaryContainer = Color(0xFF1C2408),
    secondary = Color(0xFF6B6F63),
    onSecondary = Color.White,
    secondaryContainer = Color(0x99D5DCBC),
    onSecondaryContainer = Color(0xFF242A12),
    tertiary = Color(0xFF8A6F3E),
    background = Color.Transparent,
    onBackground = Color(0xFF1D1F1A),
    surface = Color(0x9EFFFFFF),
    onSurface = Color(0xFF1D1F1A),
    surfaceVariant = Color(0x73FFFFFF),
    onSurfaceVariant = Color(0xFF4A4D45),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xF2F5F5F1),
    surfaceContainer = Color(0xF5F4F4F0),
    surfaceContainerHigh = Color(0xF7F7F7F3),
    surfaceContainerHighest = Color(0x9EFFFFFF),
    outline = Color(0xFF8E9286),
    outlineVariant = Color(0x1F000000),
    error = Color(0xFFC0392B)
)

private val DarkColors = darkColorScheme(
    primary = OliveLight,
    onPrimary = Color(0xFF253009),
    primaryContainer = Color(0xFF3E4A1E),
    onPrimaryContainer = Color(0xFFDDE8B5),
    secondary = Color(0xFFC5C8BB),
    onSecondary = Color(0xFF2E3128),
    secondaryContainer = Color(0x663E4A1E),
    onSecondaryContainer = Color(0xFFDDE8B5),
    tertiary = Color(0xFFD9BE8A),
    background = Color.Transparent,
    onBackground = Color(0xFFECEDE6),
    surface = Color(0x1AFFFFFF),
    onSurface = Color(0xFFECEDE6),
    surfaceVariant = Color(0x14FFFFFF),
    onSurfaceVariant = Color(0xFFC5C8BB),
    surfaceContainerLowest = Color(0xFF1A1B18),
    surfaceContainerLow = Color(0xF0222420),
    surfaceContainer = Color(0xF52A2C27),
    surfaceContainerHigh = Color(0xF7313330),
    surfaceContainerHighest = Color(0x1FFFFFFF),
    outline = Color(0xFF8F9387),
    outlineVariant = Color(0x26FFFFFF),
    error = Color(0xFFFF8A80)
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
