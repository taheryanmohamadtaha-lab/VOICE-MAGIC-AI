package app.voicemagic.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.voicemagic.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("voice_magic_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accent: Long = 0xFF8B5CF6,
    val fontScale: Float = 1f,
    val language: String = "fa",
    val preset: String = "neon",
)

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent")
        val font = floatPreferencesKey("font_scale")
        val lang = stringPreferencesKey("language")
        val preset = stringPreferencesKey("theme_preset")
        val favorites = stringSetPreferencesKey("favorites")
        val recent = stringPreferencesKey("recent_effects")
        val backendUrl = stringPreferencesKey("backend_url")
        val tokenEnc = stringPreferencesKey("api_token_enc")
        val cloudDisclosure = booleanPreferencesKey("cloud_disclosure_accepted")
        val micExplained = booleanPreferencesKey("mic_explained")
        val exportBitrate = intPreferencesKey("export_bitrate_kbps")
        val localCreditsNote = stringPreferencesKey("credits_cache")
    }

    val ui: Flow<UiSettings> = context.store.data.map { p ->
        UiSettings(
            themeMode = runCatching { ThemeMode.valueOf(p[K.theme] ?: "DARK") }.getOrDefault(ThemeMode.DARK),
            accent = p[K.accent]?.toLongOrNull() ?: 0xFF8B5CF6,
            fontScale = p[K.font] ?: 1f,
            language = p[K.lang] ?: "fa",
            preset = p[K.preset] ?: "neon",
        )
    }
    val favorites: Flow<Set<String>> = context.store.data.map { it[K.favorites] ?: emptySet() }
    val recentEffects: Flow<List<String>> = context.store.data.map { (it[K.recent] ?: "").split(",").filter { s -> s.isNotBlank() } }
    val backendUrl: Flow<String> = context.store.data.map { it[K.backendUrl] ?: BuildConfig.DEFAULT_BACKEND_URL }
    val cloudDisclosureAccepted: Flow<Boolean> = context.store.data.map { it[K.cloudDisclosure] ?: false }
    val exportBitrateKbps: Flow<Int> = context.store.data.map { it[K.exportBitrate] ?: 128 }

    suspend fun apiToken(): String = SecureStore.decrypt(context.store.data.first()[K.tokenEnc] ?: "")

    private suspend fun edit(f: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) { context.store.edit { f(it) } }

    suspend fun setTheme(m: ThemeMode) = edit { it[K.theme] = m.name }
    suspend fun setAccent(argb: Long) = edit { it[K.accent] = argb.toString() }
    suspend fun setFontScale(s: Float) = edit { it[K.font] = s }
    suspend fun setPreset(id: String, accent: Long) = edit { it[K.preset] = id; it[K.accent] = accent.toString() }
    suspend fun setLanguage(l: String) = edit { it[K.lang] = l }
    suspend fun setBackendUrl(u: String) = edit { it[K.backendUrl] = u.trim().trimEnd('/') }
    suspend fun setApiToken(t: String) = edit { it[K.tokenEnc] = SecureStore.encrypt(t.trim()) }
    suspend fun setCloudDisclosureAccepted(v: Boolean) = edit { it[K.cloudDisclosure] = v }
    suspend fun setExportBitrate(kbps: Int) = edit { it[K.exportBitrate] = kbps }

    suspend fun toggleFavorite(id: String) = edit {
        val cur = it[K.favorites] ?: emptySet()
        it[K.favorites] = if (id in cur) cur - id else cur + id
    }

    suspend fun pushRecent(id: String) = edit {
        val cur = (it[K.recent] ?: "").split(",").filter { s -> s.isNotBlank() && s != id }
        it[K.recent] = (listOf(id) + cur).take(8).joinToString(",")
    }

    suspend fun resetAll() { context.store.edit { it.clear() } }
}
