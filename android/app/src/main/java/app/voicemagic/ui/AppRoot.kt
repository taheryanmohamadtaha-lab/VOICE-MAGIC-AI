package app.voicemagic.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import android.app.Activity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.voicemagic.AppContainer
import app.voicemagic.R
import app.voicemagic.ui.ailab.AiLabScreen
import app.voicemagic.ui.editor.EditorScreen
import app.voicemagic.ui.home.HomeScreen
import app.voicemagic.ui.library.LibraryScreen
import app.voicemagic.ui.live.LiveScreen
import app.voicemagic.ui.settings.AboutScreen
import app.voicemagic.ui.settings.CompatibilityScreen
import app.voicemagic.ui.settings.PrivacyScreen
import app.voicemagic.ui.settings.SettingsScreen
import app.voicemagic.ui.settings.SubscriptionScreen
import app.voicemagic.ui.studio.StudioScreen

enum class Dest(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    Home("home", R.string.nav_home, Icons.Filled.Home),
    Studio("studio", R.string.nav_studio, Icons.Filled.Tune),
    Live("live", R.string.nav_live, Icons.Filled.Mic),
    Ai("ai", R.string.nav_ai, Icons.Filled.AutoAwesome),
    Editor("editor", R.string.nav_editor, Icons.Filled.ContentCut),
    Library("library", R.string.nav_library, Icons.Filled.LibraryMusic),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
}

@Composable
fun AppRoot(c: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val go: (String) -> Unit = { route ->
        nav.navigate(route) {
            popUpTo("home") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val activity = LocalContext.current as? Activity
    var askExit by remember { mutableStateOf(false) }
    val isHome = current == null || current == Dest.Home.route
    val titleRes: Int? = when {
        current == "about" -> R.string.about
        current == "settings/privacy" -> R.string.privacy_policy
        current == "settings/compat" -> R.string.compat_title
        current == "settings/subscription" -> R.string.subscription
        else -> Dest.values().firstOrNull { it.route == current }?.label
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isHome) {
                        IconButton({ go("about") }) { Icon(Icons.Filled.Info, stringResource(R.string.about)) }
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        IconButton({ askExit = true }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, stringResource(R.string.exit_app)) }
                    } else {
                        // AutoMirrored: the arrow points the right way in Persian (RTL) and English (LTR).
                        IconButton({
                            val parent = if (current?.startsWith("settings/") == true || current == "about") "settings" else "home"
                            if (!nav.popBackStack(parent, false)) go(parent)
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                        Text(titleRes?.let { stringResource(it) } ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                // 7 destinations: scrollable bar so labels stay readable at large font sizes.
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Dest.values().forEach { d ->
                        val selected = current == d.route || (d == Dest.Settings && current?.startsWith("settings") == true)
                        Column(
                            Modifier.width(78.dp).clickable { go(d.route) }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(d.icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(d.label), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Dest.Home.route, modifier = Modifier.padding(pad).fillMaxSize()) {
            composable(Dest.Home.route) { HomeScreen(c, go) }
            composable(Dest.Studio.route) { StudioScreen(c) }
            composable(Dest.Live.route) { LiveScreen(c) }
            composable(Dest.Ai.route) { AiLabScreen(c) }
            composable(Dest.Editor.route) { EditorScreen(c) }
            composable(Dest.Library.route) { LibraryScreen(c, go) }
            composable(Dest.Settings.route) { SettingsScreen(c, go) }
            composable("settings/privacy") { PrivacyScreen() }
            composable("settings/compat") { CompatibilityScreen() }
            composable("settings/subscription") { SubscriptionScreen(c) }
            composable("about") { AboutScreen() }
        }
    }

    if (askExit) {
        AlertDialog(
            onDismissRequest = { askExit = false },
            title = { Text(stringResource(R.string.exit_app)) },
            text = { Text(stringResource(R.string.exit_confirm)) },
            confirmButton = { TextButton({ askExit = false; activity?.finishAffinity() }) { Text(stringResource(R.string.exit_app)) } },
            dismissButton = { TextButton({ askExit = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
