package net.rseb.ls4tc421.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Roles taken from the mock-up sheet (gen-ecrans-v1.py, CSS variables). Roles the sheet does not
// define (secondary, tertiary, surfaceContainerHighest, error container, ...) are derived tones.
private val LightColors = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF97F0FF),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF4A6367),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE7EC),
    onSecondaryContainer = Color(0xFF051F23),
    tertiary = Color(0xFF4D5F7C),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFBFAF5),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFBFAF5),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFDEDFD2),
    onSurfaceVariant = Color(0xFF43483F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F4EE),
    surfaceContainer = Color(0xFFF1F1EA),
    surfaceContainerHigh = Color(0xFFEBEBE2),
    surfaceContainerHighest = Color(0xFFE5E5DC),
    outline = Color(0xFF73796E),
    outlineVariant = Color(0xFFDEDFD2),
    inverseSurface = Color(0xFF2F312C),
    inverseOnSurface = Color(0xFFF1F1EA),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD8EB),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = Color(0xFFB1CBD0),
    onSecondary = Color(0xFF1C3438),
    secondaryContainer = Color(0xFF334B4F),
    onSecondaryContainer = Color(0xFFCDE7EC),
    tertiary = Color(0xFFB5C7E9),
    onTertiary = Color(0xFF1F314C),
    background = Color(0xFF12140F),
    onBackground = Color(0xFFE2E3DA),
    surface = Color(0xFF12140F),
    onSurface = Color(0xFFE2E3DA),
    surfaceVariant = Color(0xFF363A31),
    onSurfaceVariant = Color(0xFFC3C8B9),
    surfaceContainerLowest = Color(0xFF0D0F0B),
    surfaceContainerLow = Color(0xFF1A1C17),
    surfaceContainer = Color(0xFF1E211B),
    surfaceContainerHigh = Color(0xFF262A22),
    surfaceContainerHighest = Color(0xFF313529),
    outline = Color(0xFF8D9286),
    outlineVariant = Color(0xFF363A31),
    inverseSurface = Color(0xFFE2E3DA),
    inverseOnSurface = Color(0xFF2F312C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Colours outside the Material roles: the amber help banner of the Wi-Fi screen, and the green of the
 * slot declared in service in the slot grid (with its text colour; contrast 5.1:1 light, 7.8:1 dark).
 */
data class ExtraColors(val banner: Color, val onBanner: Color, val inService: Color, val onInService: Color)

private val LightExtra = ExtraColors(
    banner = Color(0xFFFFF8E1),
    onBanner = Color(0xFF5A4300),
    inService = Color(0xFF1B7F3B),
    onInService = Color(0xFFFFFFFF),
)
private val DarkExtra = ExtraColors(
    banner = Color(0xFF3A2E0A),
    onBanner = Color(0xFFF4D98A),
    inService = Color(0xFF7EDB8F),
    onInService = Color(0xFF00391A),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

/** Light or dark following the system. No dynamic colours. */
@Composable
fun LsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtraColors provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}
