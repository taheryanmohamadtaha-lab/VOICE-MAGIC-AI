package app.voicemagic.ui.studio

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.voicemagic.AppContainer
import app.voicemagic.R
import app.voicemagic.VoiceMagicApp
import app.voicemagic.audio.AudioData
import app.voicemagic.audio.AudioDecoder
import app.voicemagic.audio.EffectParams
import app.voicemagic.audio.OfflineProcessor
import app.voicemagic.audio.PcmRecorder
import app.voicemagic.audio.WavIO
import app.voicemagic.data.PresetEntity
import app.voicemagic.data.RecordingEntity
import app.voicemagic.effects.EffectCatalog
import app.voicemagic.effects.EffectCategory
import app.voicemagic.effects.VoiceEffect
import app.voicemagic.ui.components.Chip
import app.voicemagic.ui.components.LevelMeter
import app.voicemagic.ui.components.ParamSlider
import app.voicemagic.ui.components.SectionTitle
import app.voicemagic.ui.components.VmCard
import app.voicemagic.ui.components.rememberMicPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StudioState(
    val category: EffectCategory? = null,
    val selectedId: String? = null,
    val customName: String? = null,
    val intensity: Float = 0.5f,
    val params: EffectParams = EffectParams(),
    val sample: AudioData? = null,
    val recordingSample: Boolean = false,
    val busy: Boolean = false,
    val messageRes: Int? = null,
)

class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as VoiceMagicApp).container
    private val _s = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _s.asStateFlow()
    private val recorder = PcmRecorder(viewModelScope)
    val level = recorder.level
    val favorites = c.settings.favorites
    val presets = c.db.presets().all()

    fun setCategory(cat: EffectCategory?) = _s.update { it.copy(category = cat) }

    fun select(e: VoiceEffect) {
        _s.update { it.copy(selectedId = e.id, customName = null, params = e.build(it.intensity)) }
        viewModelScope.launch { c.settings.pushRecent(e.id) }
    }

    fun selectPreset(p: PresetEntity) {
        val params = EffectParams.deserialize(p.params) ?: return
        _s.update { it.copy(selectedId = "preset:${p.id}", customName = p.name, params = params) }
    }

    fun setIntensity(v: Float) {
        _s.update { st ->
            val fx = st.selectedId?.let { EffectCatalog.byId(it) }
            st.copy(intensity = v, params = fx?.build(v) ?: st.params)
        }
    }

    fun edit(f: (EffectParams) -> EffectParams) = _s.update { it.copy(params = f(it.params)) }
    fun toggleFavorite(id: String) { viewModelScope.launch { c.settings.toggleFavorite(id) } }

    fun startSample() {
        if (recorder.start(44100, maxSeconds = 20)) _s.update { it.copy(recordingSample = true, messageRes = null) }
        else _s.update { it.copy(messageRes = R.string.err_mic_open) }
    }

    fun stopSample() {
        val d = recorder.stop()
        _s.update { it.copy(recordingSample = false, sample = d.takeIf { x -> x.samples.isNotEmpty() }) }
    }

    fun importSample(uri: Uri) {
        _s.update { it.copy(busy = true, messageRes = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AudioDecoder.decode(getApplication(), uri) } }
            _s.update { it.copy(busy = false, sample = r.getOrNull(), messageRes = if (r.isFailure) R.string.err_import else null) }
        }
    }

    private suspend fun render(): AudioData? {
        val st = _s.value
        val src = st.sample ?: return null
        return withContext(Dispatchers.Default) {
            runCatching { AudioData(OfflineProcessor.process(src.samples, src.sampleRate, st.params), src.sampleRate) }.getOrNull()
        }
    }

    fun preview() {
        viewModelScope.launch {
            _s.update { it.copy(busy = true, messageRes = null) }
            val out = render()
            _s.update { it.copy(busy = false, messageRes = if (out == null) R.string.err_process else null) }
            if (out != null) c.player.play(out.samples, out.sampleRate)
        }
    }

    fun original() { _s.value.sample?.let { c.player.play(it.samples, it.sampleRate) } }
    fun stopPlayback() = c.player.stop()

    fun save(name: String) {
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            val out = render()
            if (out == null) { _s.update { it.copy(busy = false, messageRes = R.string.err_process) }; return@launch }
            val f = c.files.newRecordingFile("studio")
            withContext(Dispatchers.IO) { WavIO.write(f, out.samples, out.sampleRate) }
            c.db.recordings().insert(RecordingEntity(name = name, path = f.absolutePath, durationMs = out.durationMs,
                sampleRate = out.sampleRate, effectId = _s.value.selectedId, createdAt = System.currentTimeMillis()))
            _s.update { it.copy(busy = false, messageRes = R.string.saved_to_library) }
        }
    }

    fun savePreset(name: String) {
        viewModelScope.launch { c.db.presets().insert(PresetEntity(name = name, params = _s.value.params.serialize())) }
    }

    fun deletePreset(p: PresetEntity) { viewModelScope.launch { c.db.presets().delete(p) } }

    override fun onCleared() { c.player.stop(); if (recorder.recording.value) recorder.stop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(c: AppContainer, vm: StudioViewModel = viewModel()) {
    val st by vm.state.collectAsState()
    val favs by vm.favorites.collectAsState(initial = emptySet())
    val presets by vm.presets.collectAsState(initial = emptyList())
    val playing by c.player.playing.collectAsState()
    val mic = rememberMicPermission()
    val level by vm.level.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importSample) }
    var saveDialog by remember { mutableStateOf<String?>(null) }  // "recording" | "preset"
    var nameInput by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.nav_studio), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }

        // 1) sample source
        item {
            VmCard {
                SectionTitle(stringResource(R.string.studio_sample))
                Text(
                    st.sample?.let { stringResource(R.string.sample_ready, it.durationMs / 1000f) } ?: stringResource(R.string.sample_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (st.recordingSample) LevelMeter(level, stringResource(R.string.input_level))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!st.recordingSample) Button({ mic.ensure { vm.startSample() } }) { Text(stringResource(R.string.record_sample)) }
                    else Button({ vm.stopSample() }) { Text(stringResource(R.string.stop)) }
                    OutlinedButton({ picker.launch(arrayOf("audio/*")) }, enabled = !st.recordingSample) { Text(stringResource(R.string.import_audio)) }
                }
                st.messageRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.secondary) }
            }
        }

        // 2) categories
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = st.category == null, onClick = { vm.setCategory(null) }, label = { Text(stringResource(R.string.cat_all)) }) }
                items(EffectCategory.values().toList()) { cat ->
                    FilterChip(selected = st.category == cat, onClick = { vm.setCategory(cat) }, label = { Text(stringResource(cat.label)) })
                }
            }
        }

        // 3) effects
        val list = EffectCatalog.all.filter { st.category == null || st.category == it.category }
        items(list, key = { it.id }) { fx ->
            val selected = st.selectedId == fx.id
            VmCard(Modifier.clickable { vm.select(fx) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(fx.name), style = MaterialTheme.typography.titleMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(fx.description), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Chip(stringResource(fx.category.label), MaterialTheme.colorScheme.secondary)
                            Chip(stringResource(if (fx.offline) R.string.available_offline else R.string.requires_online))
                            if (fx.premium) Chip(stringResource(R.string.premium_badge), MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton({ vm.toggleFavorite(fx.id) }) {
                        Icon(if (fx.id in favs) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, stringResource(R.string.favorite))
                    }
                }
            }
        }
        if ((st.category == null || st.category == EffectCategory.CUSTOM)) {
            items(presets, key = { "p${it.id}" }) { p ->
                VmCard(Modifier.clickable { vm.selectPreset(p) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Chip(stringResource(R.string.cat_custom), MaterialTheme.colorScheme.secondary)
                        }
                        TextButton({ vm.deletePreset(p) }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }

        // 4) controls for selected effect
        if (st.selectedId != null) item {
            VmCard {
                SectionTitle(st.customName ?: EffectCatalog.byId(st.selectedId!!)?.let { stringResource(it.name) } ?: "")
                if (EffectCatalog.byId(st.selectedId!!) != null)
                    ParamSlider(stringResource(R.string.intensity), st.intensity, 0f..1f, vm::setIntensity) { "${(it * 100).toInt()}%" }
                val p = st.params
                ParamSlider(stringResource(R.string.pitch), p.pitchSemitones, -12f..12f, { v -> vm.edit { it.copy(pitchSemitones = v) } }) { "%+.1f st".format(it) }
                ParamSlider(stringResource(R.string.formant), p.formant, -1f..1f, { v -> vm.edit { it.copy(formant = v) } })
                ParamSlider(stringResource(R.string.echo), p.echoMix, 0f..1f, { v -> vm.edit { it.copy(echoMix = v) } })
                ParamSlider(stringResource(R.string.reverb), p.reverbMix, 0f..1f, { v -> vm.edit { it.copy(reverbMix = v) } })
                ParamSlider(stringResource(R.string.distortion), p.distortion, 0f..1f, { v -> vm.edit { it.copy(distortion = v) } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ if (playing) vm.stopPlayback() else vm.preview() }, enabled = st.sample != null && !st.busy) {
                        Text(stringResource(if (playing) R.string.stop else R.string.preview))
                    }
                    OutlinedButton({ vm.original() }, enabled = st.sample != null) { Text(stringResource(R.string.play_original)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ nameInput = ""; saveDialog = "recording" }, enabled = st.sample != null && !st.busy) { Text(stringResource(R.string.save_to_library)) }
                    OutlinedButton({ nameInput = ""; saveDialog = "preset" }) { Text(stringResource(R.string.save_preset)) }
                }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
    }

    saveDialog?.let { kind ->
        AlertDialog(
            onDismissRequest = { saveDialog = null },
            title = { Text(stringResource(if (kind == "preset") R.string.save_preset else R.string.save_to_library)) },
            text = { OutlinedTextField(nameInput, { nameInput = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val n = nameInput.ifBlank { "Voice ${System.currentTimeMillis() % 100000}" }
                    if (kind == "preset") vm.savePreset(n) else vm.save(n)
                    saveDialog = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton({ saveDialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
