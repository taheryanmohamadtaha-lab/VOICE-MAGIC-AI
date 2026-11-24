package app.voicemagic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.voicemagic.data.ThemeMode
import app.voicemagic.data.UiSettings

val AccentChoices: List<Long> = listOf(0xFF8B5CF6, 0xFF22D3EE, 0xFFEC4899, 0xFF34D399, 0xFFFB923C, 0xFF60A5FA)

@Composable
fun VoiceMagicTheme(settings: UiSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val accent = Color(settings.accent.toInt())
    val scheme = if (dark) darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        secondary = Color(0xFF22D3EE),
        background = Color(0xFF0B0820),
        onBackground = Color(0xFFF1EEFF),
        surface = Color(0xFF16123A),
        onSurface = Color(0xFFF1EEFF),
        surfaceVariant = Color(0xFF211B52),
        onSurfaceVariant = Color(0xFFC9C2F0),
        error = Color(0xFFFF6B8A),
    ) else lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        secondary = Color(0xFF0891B2),
        background = Color(0xFFF6F3FF),
        onBackground = Color(0xFF1B1538),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1B1538),
        surfaceVariant = Color(0xFFE9E4FF),
        onSurfaceVariant = Color(0xFF4A4370),
        error = Color(0xFFC62848),
    )
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * settings.fontScale)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
