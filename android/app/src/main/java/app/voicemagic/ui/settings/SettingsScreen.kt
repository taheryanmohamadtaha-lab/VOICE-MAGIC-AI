package app.voicemagic.ui.settings

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import app.voicemagic.AppContainer
import app.voicemagic.R
import app.voicemagic.ai.AiStatus
import app.voicemagic.data.ThemeMode
import app.voicemagic.data.UiSettings
import app.voicemagic.ui.components.Chip
import app.voicemagic.ui.components.ParamSlider
import app.voicemagic.ui.components.SectionTitle
import app.voicemagic.ui.components.VmCard
import app.voicemagic.ui.theme.AccentChoices
import app.voicemagic.ui.theme.ThemePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(c: AppContainer, go: (String) -> Unit) {
    val ui by c.settings.ui.collectAsState(initial = UiSettings())
    val backend by c.settings.backendUrl.collectAsState(initial = "")
    val scope = rememberCoroutineScope()
    var url by remember(backend) { mutableStateOf(backend) }
    var token by remember { mutableStateOf("") }
    var cloudMsg by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }  // "local" | "voices" | "cloud"
    var doneMsg by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineSmall)

        VmCard {
            SectionTitle(stringResource(R.string.language))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(ui.language == "fa", {
                    scope.launch { c.settings.setLanguage("fa") }
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fa"))
                }, label = { Text("فارسی") })
                FilterChip(ui.language == "en", {
                    scope.launch { c.settings.setLanguage("en") }
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                }, label = { Text("English") })
            }
        }

        VmCard {
            SectionTitle(stringResource(R.string.appearance))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(ui.themeMode == ThemeMode.DARK, { scope.launch { c.settings.setTheme(ThemeMode.DARK) } }, label = { Text(stringResource(R.string.theme_dark)) })
                FilterChip(ui.themeMode == ThemeMode.LIGHT, { scope.launch { c.settings.setTheme(ThemeMode.LIGHT) } }, label = { Text(stringResource(R.string.theme_light)) })
                FilterChip(ui.themeMode == ThemeMode.SYSTEM, { scope.launch { c.settings.setTheme(ThemeMode.SYSTEM) } }, label = { Text(stringResource(R.string.theme_system)) })
            }
            Text(stringResource(R.string.theme_preset), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemePresets.forEach { pr ->
                    val sel = ui.preset == pr.id
                    Column(
                        Modifier.clickable { scope.launch { c.settings.setPreset(pr.id, pr.accent) } },
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(52.dp)
                                .background(Brush.linearGradient(listOf(Color(pr.darkBg.toInt()), Color(pr.darkVariant.toInt()), Color(pr.accent.toInt()))), CircleShape)
                                .border(BorderStroke(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant), CircleShape),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) { if (sel) Text("✓", color = Color.White) }
                        Text(stringResource(pr.name), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(stringResource(R.string.accent_color), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentChoices.forEach { col ->
                    Box(Modifier.size(36.dp).background(Color(col.toInt()), CircleShape).clickable { scope.launch { c.settings.setAccent(col) } },
                        contentAlignment = androidx.compose.ui.Alignment.Center) {
                        if (ui.accent == col) Text("✓", color = Color.White)
                    }
                }
            }
            ParamSlider(stringResource(R.string.font_size), ui.fontScale, 0.85f..1.5f, { scope.launch { c.settings.setFontScale(it) } }) { "${(it * 100).toInt()}%" }
        }

        VmCard {
            SectionTitle(stringResource(R.string.cloud_settings))
            Text(stringResource(R.string.cloud_settings_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.backend_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.api_token)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    scope.launch {
                        c.settings.setBackendUrl(url)
                        if (token.isNotBlank()) { c.settings.setApiToken(token); token = "" }
                        val s = c.cloud.status()
                        cloudMsg = when (s) {
                            AiStatus.NotConfigured -> "Not configured"
                            is AiStatus.Unreachable -> "Unreachable: ${s.detail}"
                            is AiStatus.BackendWithoutProvider -> "Backend OK, no AI provider configured on server"
                            is AiStatus.Ready -> "OK — credits: ${s.credits}"
                        }
                    }
                }) { Text(stringResource(R.string.save_and_test)) }
            }
            cloudMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        }

        VmCard {
            SectionTitle(stringResource(R.string.more))
            OutlinedButton({ go("settings/subscription") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.subscription)) }
            OutlinedButton({ go("settings/compat") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.compat_title)) }
            OutlinedButton({ go("settings/privacy") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.privacy_policy)) }
            OutlinedButton({ go("about") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.about)) }
        }

        VmCard {
            SectionTitle(stringResource(R.string.your_data))
            OutlinedButton({ confirm = "voices" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete_all_voice_data)) }
            OutlinedButton({ confirm = "cloud" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete_cloud_data)) }
            OutlinedButton({ confirm = "local" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete_all_local)) }
            doneMsg?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.secondary) }
        }
        Text(stringResource(R.string.version_credit), style = MaterialTheme.typography.bodySmall)
    }

    confirm?.let { kind ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.are_you_sure)) },
            text = { Text(stringResource(when (kind) { "voices" -> R.string.confirm_voices; "cloud" -> R.string.confirm_cloud; else -> R.string.confirm_local })) },
            confirmButton = {
                TextButton({
                    confirm = null
                    scope.launch {
                        when (kind) {
                            "voices" -> {
                                c.db.profiles().snapshot().forEach { p -> p.cloudProfileId?.let { runCatching { c.cloud.deleteProfile(it) } } }
                                withContext(Dispatchers.IO) { c.files.wipeVoiceSamples() }
                                c.db.profiles().deleteAll()
                                doneMsg = R.string.done
                            }
                            "cloud" -> doneMsg = try { c.cloud.deleteAccountData(); R.string.done } catch (e: Exception) { R.string.err_cloud_delete }
                            else -> {
                                c.player.stop()
                                withContext(Dispatchers.IO) { c.files.wipeAll() }
                                c.db.recordings().deleteAll(); c.db.projects().deleteAll(); c.db.presets().deleteAll()
                                c.db.profiles().deleteAll(); c.db.usage().deleteAll()
                                c.settings.resetAll()
                                doneMsg = R.string.done
                            }
                        }
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun PrivacyScreen() {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.privacy_policy), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.privacy_body))
    }
}

@Composable
fun SubscriptionScreen(c: AppContainer) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.subscription), style = MaterialTheme.typography.headlineSmall)
        VmCard {
            SectionTitle(stringResource(R.string.free_plan))
            Text(stringResource(R.string.free_plan_features))
        }
        VmCard {
            SectionTitle(stringResource(R.string.premium))
            Text(stringResource(R.string.premium_features))
            Chip(stringResource(R.string.billing_not_configured), MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.billing_not_configured_body), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({}, enabled = false) { Text(stringResource(R.string.subscribe)) }
                OutlinedButton({}, enabled = false) { Text(stringResource(R.string.restore_purchases)) }
            }
        }
        val usage by c.db.usage().recent().collectAsState(initial = emptyList())
        VmCard {
            SectionTitle(stringResource(R.string.usage_history))
            if (usage.isEmpty()) Text(stringResource(R.string.none_yet))
            usage.take(20).forEach { Text("${java.text.DateFormat.getDateTimeInstance().format(it.ts)} — ${it.kind} (${it.note})", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun CompatibilityScreen() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val pro = remember { ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUDIO_PRO) }
    val lowLat = remember { ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUDIO_LOW_LATENCY) }
    val outs = remember { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
    val ins = remember { am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }
    fun name(d: AudioDeviceInfo) = "${d.productName} (${typeName(d.type)})"

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.compat_title), style = MaterialTheme.typography.headlineSmall)
        VmCard {
            SectionTitle(stringResource(R.string.this_device))
            Text(stringResource(R.string.low_latency_feature) + ": " + stringResource(if (lowLat) R.string.yes else R.string.no))
            Text(stringResource(R.string.pro_audio_feature) + ": " + stringResource(if (pro) R.string.yes else R.string.no))
            Text(stringResource(R.string.output_devices) + ":", style = MaterialTheme.typography.titleSmall)
            outs.forEach { Text("• " + name(it), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.input_devices) + ":", style = MaterialTheme.typography.titleSmall)
            ins.forEach { Text("• " + name(it), style = MaterialTheme.typography.bodySmall) }
        }
        VmCard {
            SectionTitle(stringResource(R.string.what_works))
            Text(stringResource(R.string.compat_works))
        }
        VmCard {
            SectionTitle(stringResource(R.string.what_does_not))
            Text(stringResource(R.string.compat_not))
        }
    }
}

private fun typeName(t: Int): String = when (t) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
    else -> "type $t"
}
