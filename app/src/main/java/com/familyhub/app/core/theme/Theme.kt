package com.familyhub.app.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CobaltBlue = Color(0xFF1D63ED)
val CobaltBlueDark = Color(0xFF6EA0FF)
val CharcoalBg = Color(0xFF111317)
val CharcoalSurface = Color(0xFF1B1F26)
val SoftGreyBg = Color(0xFFF6F8FB)
val PureWhite = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = CobaltBlue,
    onPrimary = PureWhite,
    background = SoftGreyBg,
    surface = PureWhite,
    onSurface = Color(0xFF14171F),
    surfaceVariant = Color(0xFFE9EEF5)
)

private val DarkColors = darkColorScheme(
    primary = CobaltBlueDark,
    onPrimary = Color(0xFF061B44),
    background = CharcoalBg,
    surface = CharcoalSurface,
    onSurface = Color(0xFFF1F4F9),
    surfaceVariant = Color(0xFF262C36)
)

@Composable
fun FamilyHubTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
