package app.voicemagic.ui.editor

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.voicemagic.AppContainer
import app.voicemagic.EditorRequest
import app.voicemagic.R
import app.voicemagic.VoiceMagicApp
import app.voicemagic.audio.AacEncoder
import app.voicemagic.audio.AudioDecoder
import app.voicemagic.audio.AudioOps
import app.voicemagic.audio.EffectParams
import app.voicemagic.audio.OfflineProcessor
import app.voicemagic.audio.PcmRecorder
import app.voicemagic.audio.WavIO
import app.voicemagic.data.ProjectEntity
import app.voicemagic.data.RecordingEntity
import app.voicemagic.ui.components.Chip
import app.voicemagic.ui.components.LevelMeter
import app.voicemagic.ui.components.ParamSlider
import app.voicemagic.ui.components.SectionTitle
import app.voicemagic.ui.components.VmCard
import app.voicemagic.ui.components.Waveform
import app.voicemagic.ui.components.formatDuration
import app.voicemagic.ui.components.rememberMicPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

class Track(val id: Long, val name: String, val samples: FloatArray, val sampleRate: Int, val gain: Float = 1f) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
    fun with(samples: FloatArray = this.samples, name: String = this.name, gain: Float = this.gain) = Track(id, name, samples, sampleRate, gain)
}

enum class Tool(val label: Int) {
    VOLUME(R.string.tool_volume), SPEED(R.string.tool_speed), PITCH(R.string.tool_pitch), EQ(R.string.tool_eq),
    DENOISE(R.string.tool_denoise), REVERB(R.string.reverb), ECHO(R.string.echo), FADE(R.string.tool_fade),
}

data class EditorState(
    val tracks: List<Track> = emptyList(),
    val selected: Int = 0,
    val selStart: Float = 0f,
    val selEnd: Float = 1f,
    val recording: Boolean = false,
    val busy: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val messageRes: Int? = null,
    val shareFile: File? = null,
    val projectId: Long? = null,
    val projectName: String = "",
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as VoiceMagicApp).container
    private val _s = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _s.asStateFlow()
    private val undo = ArrayDeque<List<Track>>()
    private val redo = ArrayDeque<List<Track>>()
    private var nextId = 1L
    private val recorder = PcmRecorder(viewModelScope)
    val level = recorder.level

    private val projectRate: Int get() = _s.value.tracks.firstOrNull()?.sampleRate ?: 44100
    private val cur: Track? get() = _s.value.tracks.getOrNull(_s.value.selected)

    private fun mutate(newTracks: List<Track>, selected: Int = _s.value.selected) {
        undo.addLast(_s.value.tracks); if (undo.size > 15) undo.removeFirst()
        redo.clear()
        _s.update { it.copy(tracks = newTracks, selected = selected.coerceIn(0, max(0, newTracks.size - 1)), canUndo = true, canRedo = false, messageRes = null) }
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(_s.value.tracks)
        _s.update { it.copy(tracks = prev, selected = it.selected.coerceIn(0, max(0, prev.size - 1)), canUndo = undo.isNotEmpty(), canRedo = true) }
    }

    fun redo() {
        val nxt = redo.removeLastOrNull() ?: return
        undo.addLast(_s.value.tracks)
        _s.update { it.copy(tracks = nxt, selected = it.selected.coerceIn(0, max(0, nxt.size - 1)), canUndo = true, canRedo = redo.isNotEmpty()) }
    }

    fun selectTrack(i: Int) = _s.update { it.copy(selected = i, selStart = 0f, selEnd = 1f) }
    fun setSelection(a: Float, b: Float) = _s.update { it.copy(selStart = a.coerceIn(0f, 1f), selEnd = b.coerceIn(0f, 1f)) }

    private fun range(t: Track): Pair<Int, Int> {
        val s = _s.value
        val a = (s.selStart * t.samples.size).toInt()
        val b = (s.selEnd * t.samples.size).toInt()
        return if (b - a < 16) 0 to t.samples.size else a to b
    }

    // ---- adding material
    private fun addTrack(name: String, samples: FloatArray, rate: Int) {
        val pr = if (_s.value.tracks.isEmpty()) rate else projectRate
        val data = AudioOps.resample(samples, rate, pr)
        mutate(_s.value.tracks + Track(nextId++, name, data, pr), selected = _s.value.tracks.size)
        _s.update { it.copy(selStart = 0f, selEnd = 1f) }
    }

    fun importUri(uri: Uri, name: String = "Import") {
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            val r = withContext(Dispatchers.IO) { runCatching { AudioDecoder.decode(getApplication(), uri) } }
            _s.update { it.copy(busy = false) }
            r.onSuccess { addTrack(name, it.samples, it.sampleRate) }.onFailure { _s.update { s -> s.copy(messageRes = R.string.err_import) } }
        }
    }

    fun openFile(path: String) {
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) { WavIO.read(File(path)) }
            if (d != null) addTrack(File(path).nameWithoutExtension, d.samples, d.sampleRate) else _s.update { it.copy(messageRes = R.string.err_import) }
        }
    }

    fun startRecording() {
        if (recorder.start(44100, 300)) _s.update { it.copy(recording = true) } else _s.update { it.copy(messageRes = R.string.err_mic_open) }
    }

    fun stopRecording() {
        val d = recorder.stop()
        _s.update { it.copy(recording = false) }
        if (d.samples.isNotEmpty()) addTrack("Recording", d.samples, d.sampleRate)
    }

    // ---- editing operations (all produce new arrays; history keeps the old ones)
    fun trim() { val t = cur ?: return; val (a, b) = range(t); mutate(replace(t.with(AudioOps.slice(t.samples, a, b)))) ; resetSel() }
    fun deleteSelection() { val t = cur ?: return; val (a, b) = range(t); mutate(replace(t.with(AudioOps.cut(t.samples, a, b)))); resetSel() }

    fun split() {
        val t = cur ?: return
        val at = (_s.value.selStart * t.samples.size).toInt()
        if (at < 16 || at > t.samples.size - 16) { _s.update { it.copy(messageRes = R.string.err_split) }; return }
        val l = t.with(AudioOps.slice(t.samples, 0, at), name = t.name + " A")
        val r = Track(nextId++, t.name + " B", AudioOps.slice(t.samples, at, t.samples.size), t.sampleRate)
        val list = _s.value.tracks.toMutableList().also { it[_s.value.selected] = l; it.add(_s.value.selected + 1, r) }
        mutate(list); resetSel()
    }

    fun mergeWithNext() {
        val i = _s.value.selected
        val a = _s.value.tracks.getOrNull(i) ?: return
        val b = _s.value.tracks.getOrNull(i + 1) ?: run { _s.update { it.copy(messageRes = R.string.err_merge) }; return }
        val list = _s.value.tracks.toMutableList()
        list[i] = a.with(AudioOps.concat(a.samples, b.samples), name = a.name + "+" + b.name)
        list.removeAt(i + 1)
        mutate(list)
    }

    fun mixAll() {
        val ts = _s.value.tracks
        if (ts.size < 2) { _s.update { it.copy(messageRes = R.string.err_mix) }; return }
        val mixed = AudioOps.mix(ts.map { it.samples }, ts.map { it.gain })
        mutate(listOf(Track(nextId++, "Mix", mixed, projectRate)), 0)
    }

    fun setTrackGain(i: Int, g: Float) {
        val list = _s.value.tracks.toMutableList()
        list[i] = list[i].with(gain = g)
        _s.update { it.copy(tracks = list) }  // mixing level only; not an undo step
    }

    fun removeTrack() { if (cur == null) return; mutate(_s.value.tracks.filterIndexed { i, _ -> i != _s.value.selected }) }

    fun gain(db: Float) = applyTo { AudioOps.gain(it, db) }
    fun speed(f: Float) = applyTo(wholeTrack = true) { AudioOps.varispeed(it, f) }
    fun fade(inS: Float, outS: Float, sr: Int) = applyTo { AudioOps.fade(it, sr, inS, outS) }

    fun chain(params: EffectParams) {
        val t = cur ?: return
        val (a, b) = range(t)
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            val seg = withContext(Dispatchers.Default) { runCatching { OfflineProcessor.process(AudioOps.slice(t.samples, a, b), t.sampleRate, params) }.getOrNull() }
            _s.update { it.copy(busy = false) }
            if (seg == null) { _s.update { it.copy(messageRes = R.string.err_process) }; return@launch }
            mutate(replace(t.with(t.samples.copyOfRange(0, a) + seg + t.samples.copyOfRange(b, t.samples.size))))
        }
    }

    private fun applyTo(wholeTrack: Boolean = false, f: (FloatArray) -> FloatArray) {
        val t = cur ?: return
        if (wholeTrack) { mutate(replace(t.with(f(t.samples)))); return }
        val (a, b) = range(t)
        mutate(replace(t.with(t.samples.copyOfRange(0, a) + f(AudioOps.slice(t.samples, a, b)) + t.samples.copyOfRange(b, t.samples.size))))
    }

    private fun replace(t: Track): List<Track> = _s.value.tracks.toMutableList().also { it[_s.value.selected] = t }
    private fun resetSel() = _s.update { it.copy(selStart = 0f, selEnd = 1f) }

    // ---- playback
    fun playSelection() { val t = cur ?: return; val (a, b) = range(t); c.player.play(AudioOps.slice(t.samples, a, b).let { s -> AudioOps.gain(s, 0f) }, t.sampleRate) }
    fun playMix() {
        val ts = _s.value.tracks
        if (ts.isEmpty()) return
        c.player.play(AudioOps.mix(ts.map { it.samples }, ts.map { it.gain }), projectRate)
    }
    fun stopPlayback() = c.player.stop()

    // ---- projects
    fun saveProject(name: String) {
        val ts = _s.value.tracks
        if (ts.isEmpty()) return
        viewModelScope.launch {
            val id = c.db.projects().upsert(ProjectEntity(id = _s.value.projectId ?: 0, name = name, manifest = "", updatedAt = System.currentTimeMillis()))
            val pid = if (_s.value.projectId != null) _s.value.projectId!! else id
            val dir = File(c.files.projects, pid.toString()).apply { deleteRecursively(); mkdirs() }
            val lines = withContext(Dispatchers.IO) {
                ts.mapIndexed { i, t ->
                    val f = File(dir, "track$i.wav"); WavIO.write(f, t.samples, t.sampleRate)
                    "${t.name.replace("|", "_")}|track$i.wav|${t.sampleRate}|${t.gain}"
                }
            }
            c.db.projects().upsert(ProjectEntity(id = pid, name = name, manifest = lines.joinToString("\n"), updatedAt = System.currentTimeMillis()))
            _s.update { it.copy(projectId = pid, projectName = name, messageRes = R.string.project_saved) }
        }
    }

    fun openProject(id: Long) {
        viewModelScope.launch {
            val p = c.db.projects().snapshot().firstOrNull { it.id == id } ?: return@launch
            val dir = File(c.files.projects, id.toString())
            val tracks = withContext(Dispatchers.IO) {
                p.manifest.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                    val parts = line.split("|")
                    val d = WavIO.read(File(dir, parts[1])) ?: return@mapNotNull null
                    Track(nextId++, parts[0], d.samples, d.sampleRate, parts.getOrNull(3)?.toFloatOrNull() ?: 1f)
                }
            }
            undo.clear(); redo.clear()
            _s.value = EditorState(tracks = tracks, projectId = id, projectName = p.name)
        }
    }

    // ---- export
    fun export(format: String, bitrateKbps: Int) {
        val ts = _s.value.tracks
        if (ts.isEmpty()) return
        viewModelScope.launch {
            _s.update { it.copy(busy = true, messageRes = null) }
            val mix = if (ts.size == 1) AudioOps.gain(ts[0].samples, 0f) else AudioOps.mix(ts.map { it.samples }, ts.map { it.gain })
            val base = (_s.value.projectName.ifBlank { "export" }).replace(Regex("[^A-Za-z0-9_-]"), "_") + "_" + System.currentTimeMillis()
            val out = File(c.files.exports, base + if (format == "m4a") ".m4a" else ".wav")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (format == "m4a") AacEncoder.encodeM4a(mix, projectRate, out, bitrateKbps * 1000) else WavIO.write(out, mix, projectRate)
                }.isSuccess
            }
            _s.update { it.copy(busy = false, shareFile = if (ok) out else null, messageRes = if (ok) R.string.export_done else R.string.err_export) }
            if (ok && format == "wav") {
                c.db.recordings().insert(RecordingEntity(name = base, path = out.absolutePath, durationMs = mix.size * 1000L / projectRate,
                    sampleRate = projectRate, effectId = null, createdAt = System.currentTimeMillis()))
            }
        }
    }

    fun shareConsumed() = _s.update { it.copy(shareFile = null) }
    fun clearAll() { undo.clear(); redo.clear(); _s.value = EditorState() }
    override fun onCleared() { c.player.stop(); if (recorder.recording.value) recorder.stop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(c: AppContainer, vm: EditorViewModel = viewModel()) {
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    val mic = rememberMicPermission()
    val level by vm.level.collectAsState()
    val playing by c.player.playing.collectAsState()
    val bitrate by c.settings.exportBitrateKbps.collectAsState(initial = 128)
    var tool by remember { mutableStateOf<Tool?>(null) }
    var showSave by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importUri(it) } }

    // Hand-over from the Library
    val inbox by c.editorInbox.collectAsState()
    LaunchedEffect(inbox) {
        when (val r = inbox) {
            is EditorRequest.OpenFile -> vm.openFile(r.path)
            is EditorRequest.OpenProject -> vm.openProject(r.id)
            null -> {}
        }
        if (inbox != null) c.editorInbox.value = null
    }
    // Android Share sheet after export
    LaunchedEffect(st.shareFile) {
        val f = st.shareFile ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val mime = if (f.extension == "m4a") "audio/mp4" else "audio/wav"
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null))
        vm.shareConsumed()
    }

    val track = st.tracks.getOrNull(st.selected)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.nav_editor), style = MaterialTheme.typography.headlineSmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ picker.launch(arrayOf("audio/*")) }) { Text(stringResource(R.string.import_audio)) }
            if (!st.recording) OutlinedButton({ mic.ensure { vm.startRecording() } }) { Text(stringResource(R.string.record)) }
            else Button({ vm.stopRecording() }) { Text(stringResource(R.string.stop)) }
        }
        if (st.recording) LevelMeter(level, stringResource(R.string.input_level))
        if (st.busy) Text(stringResource(R.string.working))
        st.messageRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.secondary) }

        if (track == null) {
            Text(stringResource(R.string.editor_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(st.tracks.size) { i ->
                    FilterChip(selected = i == st.selected, onClick = { vm.selectTrack(i) }, label = { Text(st.tracks[i].name.take(14)) })
                }
            }
            VmCard {
                val peaks = remember(track.samples) { AudioOps.peaks(track.samples, 120) }
                var anchor by remember { mutableFloatStateOf(0f) }
                Box(Modifier.fillMaxWidth()
                    .pointerInput(track.id) { detectTapGestures { vm.setSelection(0f, 1f) } }
                    .pointerInput(track.id) {
                        detectDragGestures(
                            onDragStart = { o -> anchor = (o.x / size.width).coerceIn(0f, 1f) },
                            onDrag = { ch, _ -> val x = (ch.position.x / size.width).coerceIn(0f, 1f); vm.setSelection(min(anchor, x), max(anchor, x)) },
                        )
                    }) { Waveform(peaks, selection = st.selStart..st.selEnd) }
                Text(stringResource(R.string.editor_selection_hint), style = MaterialTheme.typography.bodySmall)
                Text("${formatDuration(track.durationMs)} • ${track.sampleRate} Hz", style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ if (playing) vm.stopPlayback() else vm.playSelection() }) { Text(stringResource(if (playing) R.string.stop else R.string.play)) }
                    OutlinedButton({ vm.playMix() }) { Text(stringResource(R.string.play_mix)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ vm.undo() }, enabled = st.canUndo) { Text(stringResource(R.string.undo)) }
                    OutlinedButton({ vm.redo() }, enabled = st.canRedo) { Text(stringResource(R.string.redo)) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { OutlinedButton({ vm.trim() }) { Text(stringResource(R.string.trim)) } }
                    item { OutlinedButton({ vm.deleteSelection() }) { Text(stringResource(R.string.crop_out)) } }
                    item { OutlinedButton({ vm.split() }) { Text(stringResource(R.string.split)) } }
                    item { OutlinedButton({ vm.mergeWithNext() }) { Text(stringResource(R.string.merge)) } }
                    item { OutlinedButton({ vm.mixAll() }) { Text(stringResource(R.string.mix)) } }
                    item { OutlinedButton({ vm.removeTrack() }) { Text(stringResource(R.string.delete)) } }
                }
                if (st.tracks.size > 1) {
                    ParamSlider(stringResource(R.string.track_level), track.gain, 0f..2f, { vm.setTrackGain(st.selected, it) }) { "${(it * 100).toInt()}%" }
                }
            }

            SectionTitle(stringResource(R.string.tools))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Tool.values().toList()) { t -> FilterChip(selected = tool == t, onClick = { tool = if (tool == t) null else t }, label = { Text(stringResource(t.label)) }) }
            }
            tool?.let { ToolPanel(it, track.sampleRate, vm) }
        }

        if (track != null) {
            SectionTitle(stringResource(R.string.project))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ nameInput = st.projectName; showSave = true }) { Text(stringResource(R.string.save_project)) }
                Button({ showExport = true }, enabled = !st.busy) { Text(stringResource(R.string.export)) }
            }
        }
    }

    if (showSave) AlertDialog(
        onDismissRequest = { showSave = false },
        title = { Text(stringResource(R.string.save_project)) },
        text = { OutlinedTextField(nameInput, { nameInput = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true) },
        confirmButton = { TextButton({ vm.saveProject(nameInput.ifBlank { "Project" }); showSave = false }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton({ showSave = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (showExport) {
        var fmt by remember { mutableStateOf("wav") }
        var kbps by remember { mutableStateOf(bitrate) }
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text(stringResource(R.string.export)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(fmt == "wav", { fmt = "wav" }, label = { Text("WAV 16-bit") })
                        FilterChip(fmt == "m4a", { fmt = "m4a" }, label = { Text("AAC / M4A") })
                    }
                    if (fmt == "m4a") Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(96, 128, 192, 256).forEach { k -> FilterChip(kbps == k, { kbps = k }, label = { Text("$k kbps") }) }
                    }
                    Text(stringResource(R.string.mp3_unavailable), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton({ showExport = false; vm.export(fmt, kbps) }) { Text(stringResource(R.string.export)) } },
            dismissButton = { TextButton({ showExport = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ToolPanel(tool: Tool, sampleRate: Int, vm: EditorViewModel) {
    VmCard {
        when (tool) {
            Tool.VOLUME -> { var v by remember { mutableFloatStateOf(0f) }
                ParamSlider(stringResource(R.string.tool_volume), v, -18f..18f, { v = it }) { "%+.1f dB".format(it) }
                Button({ vm.gain(v) }) { Text(stringResource(R.string.apply)) } }
            Tool.SPEED -> { var v by remember { mutableFloatStateOf(1f) }
                ParamSlider(stringResource(R.string.tool_speed), v, 0.5f..2f, { v = it }) { "%.2fx".format(it) }
                Text(stringResource(R.string.speed_note), style = MaterialTheme.typography.bodySmall)
                Button({ vm.speed(v) }) { Text(stringResource(R.string.apply)) } }
            Tool.PITCH -> { var v by remember { mutableFloatStateOf(0f) }
                ParamSlider(stringResource(R.string.pitch), v, -12f..12f, { v = it }) { "%+.1f st".format(it) }
                Button({ vm.chain(EffectParams(pitchSemitones = v)) }) { Text(stringResource(R.string.apply)) } }
            Tool.EQ -> { var lo by remember { mutableFloatStateOf(0f) }; var mid by remember { mutableFloatStateOf(0f) }; var hi by remember { mutableFloatStateOf(0f) }
                ParamSlider(stringResource(R.string.eq_low), lo, -12f..12f, { lo = it }) { "%+.0f dB".format(it) }
                ParamSlider(stringResource(R.string.eq_mid), mid, -12f..12f, { mid = it }) { "%+.0f dB".format(it) }
                ParamSlider(stringResource(R.string.eq_high), hi, -12f..12f, { hi = it }) { "%+.0f dB".format(it) }
                Button({ vm.chain(EffectParams(eqLowDb = lo, eqMidDb = mid, eqHighDb = hi)) }) { Text(stringResource(R.string.apply)) } }
            Tool.DENOISE -> { Text(stringResource(R.string.denoise_note), style = MaterialTheme.typography.bodySmall)
                Button({ vm.chain(EffectParams(denoise = true)) }) { Text(stringResource(R.string.apply)) } }
            Tool.REVERB -> { var m by remember { mutableFloatStateOf(0.3f) }; var r by remember { mutableFloatStateOf(0.6f) }
                ParamSlider(stringResource(R.string.reverb), m, 0f..1f, { m = it })
                ParamSlider(stringResource(R.string.room_size), r, 0f..1f, { r = it })
                Button({ vm.chain(EffectParams(reverbMix = m, reverbRoom = r)) }) { Text(stringResource(R.string.apply)) } }
            Tool.ECHO -> { var m by remember { mutableFloatStateOf(0.4f) }; var d by remember { mutableFloatStateOf(300f) }; var f by remember { mutableFloatStateOf(0.4f) }
                ParamSlider(stringResource(R.string.echo), m, 0f..1f, { m = it })
                ParamSlider(stringResource(R.string.delay), d, 50f..900f, { d = it }) { "%.0f ms".format(it) }
                ParamSlider(stringResource(R.string.feedback), f, 0f..0.9f, { f = it })
                Button({ vm.chain(EffectParams(echoMix = m, echoDelayMs = d, echoFeedback = f)) }) { Text(stringResource(R.string.apply)) } }
            Tool.FADE -> { var i by remember { mutableFloatStateOf(0.5f) }; var o by remember { mutableFloatStateOf(0.5f) }
                ParamSlider(stringResource(R.string.fade_in), i, 0f..5f, { i = it }) { "%.1f s".format(it) }
                ParamSlider(stringResource(R.string.fade_out), o, 0f..5f, { o = it }) { "%.1f s".format(it) }
                Button({ vm.fade(i, o, sampleRate) }) { Text(stringResource(R.string.apply)) } }
        }
    }
}
