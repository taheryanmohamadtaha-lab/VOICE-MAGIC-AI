package app.voicemagic

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import app.voicemagic.data.UiSettings
import app.voicemagic.ui.AppRoot
import app.voicemagic.ui.theme.VoiceMagicTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Persian is the primary language; set it once on first launch (user can switch in Settings).
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fa"))
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VoiceMagicApp).container
        setContent {
            val ui by container.settings.ui.collectAsState(initial = UiSettings())
            VoiceMagicTheme(ui) { AppRoot(container) }
        }
    }
}
