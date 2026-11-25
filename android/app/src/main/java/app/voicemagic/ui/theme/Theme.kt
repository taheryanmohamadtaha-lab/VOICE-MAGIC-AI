package app.voicemagic.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.voicemagic.R
import app.voicemagic.data.ThemeMode
import app.voicemagic.data.UiSettings

val AccentChoices: List<Long> = listOf(0xFF8B5CF6, 0xFF22D3EE, 0xFFEC4899, 0xFF34D399, 0xFFFB923C, 0xFF60A5FA)

/** A selectable look: background/surface tones (dark and light) plus a default accent. */
data class ThemePreset(
    val id: String,
    @StringRes val name: Int,
    val accent: Long,
    val secondary: Long,
    val darkBg: Long, val darkSurface: Long, val darkVariant: Long,
    val lightBg: Long, val lightVariant: Long,
)

val ThemePresets: List<ThemePreset> = listOf(
    ThemePreset("neon", R.string.preset_neon, 0xFF8B5CF6, 0xFF22D3EE, 0xFF0B0820, 0xFF16123A, 0xFF211B52, 0xFFF6F3FF, 0xFFE9E4FF),
    ThemePreset("ocean", R.string.preset_ocean, 0xFF38BDF8, 0xFF2DD4BF, 0xFF06141F, 0xFF0C2233, 0xFF12344D, 0xFFEFF8FF, 0xFFDCEEFA),
    ThemePreset("sunset", R.string.preset_sunset, 0xFFFB923C, 0xFFF43F5E, 0xFF1C0F0A, 0xFF2B1812, 0xFF3D2219, 0xFFFFF5EC, 0xFFFFE6D3),
    ThemePreset("forest", R.string.preset_forest, 0xFF34D399, 0xFFA3E635, 0xFF07170F, 0xFF0F2619, 0xFF173826, 0xFFF0FBF4, 0xFFDDF3E5),
    ThemePreset("rose", R.string.preset_rose, 0xFFEC4899, 0xFFFBBF24, 0xFF1D0A16, 0xFF2D1224, 0xFF421A35, 0xFFFFF1F7, 0xFFFFDCEB),
    ThemePreset("amoled", R.string.preset_amoled, 0xFFA78BFA, 0xFF22D3EE, 0xFF000000, 0xFF0A0A0A, 0xFF171717, 0xFFFAFAFA, 0xFFEDEDED),
)

fun presetById(id: String): ThemePreset = ThemePresets.firstOrNull { it.id == id } ?: ThemePresets.first()

@Composable
fun VoiceMagicTheme(settings: UiSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val accent = Color(settings.accent.toInt())
    val pr = presetById(settings.preset)
    val secondary = Color(pr.secondary.toInt())
    val scheme = if (dark) darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        secondary = secondary,
        background = Color(pr.darkBg.toInt()),
        onBackground = Color(0xFFF1EEFF),
        surface = Color(pr.darkSurface.toInt()),
        onSurface = Color(0xFFF1EEFF),
        surfaceVariant = Color(pr.darkVariant.toInt()),
        onSurfaceVariant = Color(0xFFC9C2F0),
        error = Color(0xFFFF6B8A),
    ) else lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        secondary = secondary,
        background = Color(pr.lightBg.toInt()),
        onBackground = Color(0xFF1B1538),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1B1538),
        surfaceVariant = Color(pr.lightVariant.toInt()),
        onSurfaceVariant = Color(0xFF4A4370),
        error = Color(0xFFC62848),
    )
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * settings.fontScale)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
