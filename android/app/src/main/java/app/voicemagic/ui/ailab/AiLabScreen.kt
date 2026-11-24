package app.voicemagic.ui.ailab

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.voicemagic.AppContainer
import app.voicemagic.R
import app.voicemagic.VoiceMagicApp
import app.voicemagic.ai.AiException
import app.voicemagic.ai.AiStatus
import app.voicemagic.audio.AudioData
import app.voicemagic.audio.AudioDecoder
import app.voicemagic.audio.EffectParams
import app.voicemagic.audio.OfflineProcessor
import app.voicemagic.audio.PcmRecorder
import app.voicemagic.audio.WavIO
import app.voicemagic.data.RecordingEntity
import app.voicemagic.data.UsageEntity
import app.voicemagic.data.VoiceProfileEntity
import app.voicemagic.ui.components.Chip
import app.voicemagic.ui.components.LevelMeter
import app.voicemagic.ui.components.ParamSlider
import app.voicemagic.ui.components.SectionTitle
import app.voicemagic.ui.components.VmCard
import app.voicemagic.ui.components.rememberMicPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Recording { NONE, CONSENT, SAMPLE, SOURCE }

data class AiLabState(
    val status: AiStatus? = null,
    val selectedProfile: Long? = null,
    val recording: Recording = Recording.NONE,
    val consentAudio: AudioData? = null,
    val source: AudioData? = null,
    val result: AudioData? = null,
    val resultIsCloud: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val messageRes: Int? = null,
)

class AiLabViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as VoiceMagicApp).container
    private val _s = MutableStateFlow(AiLabState())
    val state: StateFlow<AiLabState> = _s.asStateFlow()
    val profiles = c.db.profiles().all()
    private val recorder = PcmRecorder(viewModelScope)
    val level = recorder.level

    init { refreshStatus() }

    fun refreshStatus() {
        _s.update { it.copy(status = null) }
        viewModelScope.launch { val s = c.cloud.status(); _s.update { it.copy(status = s) } }
    }

    fun select(id: Long?) = _s.update { it.copy(selectedProfile = id, result = null) }

    // ---- recording helpers
    fun startRecording(kind: Recording) {
        if (recorder.start(44100, maxSeconds = 30)) _s.update { it.copy(recording = kind, messageRes = null, message = null) }
        else _s.update { it.copy(messageRes = R.string.err_mic_open) }
    }

    /** Stops recording; returns the captured audio and clears the recording flag. */
    fun stopRecording(): AudioData? {
        val kind = _s.value.recording
        val d = recorder.stop().takeIf { it.samples.isNotEmpty() }
        _s.update {
            when (kind) {
                Recording.CONSENT -> it.copy(recording = Recording.NONE, consentAudio = d)
                Recording.SOURCE -> it.copy(recording = Recording.NONE, source = d, result = null)
                else -> it.copy(recording = Recording.NONE)
            }
        }
        return d
    }

    fun importSource(uri: Uri) {
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            val r = withContext(Dispatchers.IO) { runCatching { AudioDecoder.decode(getApplication(), uri) } }
            _s.update { it.copy(busy = false, source = r.getOrNull(), result = null, messageRes = if (r.isFailure) R.string.err_import else null) }
        }
    }

    fun importSample(profile: VoiceProfileEntity, uri: Uri) {
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { AudioDecoder.decode(getApplication(), uri) }.getOrNull() }
            if (d == null) { _s.update { it.copy(messageRes = R.string.err_import) }; return@launch }
            addSample(profile, d)
        }
    }

    fun recordedSampleFor(profile: VoiceProfileEntity) {
        val d = stopRecording() ?: return
        viewModelScope.launch { addSample(profile, d) }
    }

    private suspend fun addSample(profile: VoiceProfileEntity, d: AudioData) {
        val f = File(c.files.voiceSamples, "sample_${profile.id}_${System.currentTimeMillis()}.wav")
        withContext(Dispatchers.IO) { WavIO.write(f, d.samples, d.sampleRate) }
        val fresh = c.db.profiles().snapshot().firstOrNull { it.id == profile.id } ?: return
        val paths = (fresh.samplePaths.lines().filter { it.isNotBlank() } + f.absolutePath).joinToString("\n")
        c.db.profiles().upsert(fresh.copy(samplePaths = paths))
    }

    // ---- profile CRUD
    fun createProfile(name: String) {
        val consent = _s.value.consentAudio ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val f = File(c.files.voiceSamples, "consent_$now.wav")
            withContext(Dispatchers.IO) { WavIO.write(f, consent.samples, consent.sampleRate) }
            c.db.profiles().upsert(VoiceProfileEntity(name = name, createdAt = now, consentAt = now,
                consentSamplePath = f.absolutePath, samplePaths = "", params = EffectParams().serialize()))
            _s.update { it.copy(consentAudio = null) }
        }
    }

    fun updateParams(p: VoiceProfileEntity, params: EffectParams) {
        viewModelScope.launch { c.db.profiles().upsert(p.copy(params = params.serialize())) }
    }

    fun rename(p: VoiceProfileEntity, name: String) { viewModelScope.launch { c.db.profiles().upsert(p.copy(name = name)) } }

    fun deleteSamples(p: VoiceProfileEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { p.samplePaths.lines().filter { it.isNotBlank() }.forEach { File(it).delete() } }
            c.db.profiles().upsert(p.copy(samplePaths = ""))
        }
    }

    fun deleteProfile(p: VoiceProfileEntity) {
        viewModelScope.launch {
            var note: String? = null
            p.cloudProfileId?.let { id -> try { c.cloud.deleteProfile(id) } catch (e: Exception) { note = "Cloud deletion failed: ${e.message}" } }
            withContext(Dispatchers.IO) {
                p.samplePaths.lines().filter { it.isNotBlank() }.forEach { File(it).delete() }
                p.consentSamplePath?.let { File(it).delete() }
            }
            c.db.profiles().delete(p)
            if (_s.value.selectedProfile == p.id) _s.update { it.copy(selectedProfile = null) }
            note?.let { n -> _s.update { it.copy(message = n) } }
        }
    }

    /** Uploads the first stored sample to the cloud provider. UI must have shown the disclosure first. */
    fun uploadProfile(p: VoiceProfileEntity) {
        val sample = p.samplePaths.lines().firstOrNull { it.isNotBlank() }?.let { File(it) }
        if (sample == null || !sample.exists()) { _s.update { it.copy(messageRes = R.string.err_no_samples) }; return }
        viewModelScope.launch {
            _s.update { it.copy(busy = true, message = null, messageRes = null) }
            try {
                val id = c.cloud.createProfile(p.name, sample)
                c.db.profiles().upsert(p.copy(cloudProfileId = id))
                _s.update { it.copy(busy = false, messageRes = R.string.cloud_profile_created) }
            } catch (e: AiException) {
                _s.update { it.copy(busy = false, message = e.message) }
            } catch (e: Exception) {
                _s.update { it.copy(busy = false, message = e.message ?: "error") }
            }
        }
    }

    // ---- conversion
    /** Local demonstration mode: pure DSP (pitch/timbre) with the profile's characteristics. NOT a neural model. */
    fun convertLocal(p: VoiceProfileEntity) {
        val src = _s.value.source ?: return
        val params = EffectParams.deserialize(p.params) ?: EffectParams()
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            val out = withContext(Dispatchers.Default) { runCatching { OfflineProcessor.process(src.samples, src.sampleRate, params) }.getOrNull() }
            _s.update { it.copy(busy = false, result = out?.let { o -> AudioData(o, src.sampleRate) }, resultIsCloud = false,
                messageRes = if (out == null) R.string.err_process else null) }
        }
    }

    fun convertCloud(p: VoiceProfileEntity) {
        val src = _s.value.source ?: return
        val cloudId = p.cloudProfileId ?: return
        viewModelScope.launch {
            _s.update { it.copy(busy = true, message = null, messageRes = null) }
            val tmp = File(getApplication<Application>().cacheDir, "cloud_src.wav")
            try {
                withContext(Dispatchers.IO) { WavIO.write(tmp, src.samples, src.sampleRate) }
                val r = c.cloud.convert(cloudId, tmp)
                val outFile = File(getApplication<Application>().cacheDir, "cloud_out.wav")
                withContext(Dispatchers.IO) { outFile.writeBytes(r.wav) }
                val d = WavIO.read(outFile)
                c.db.usage().insert(UsageEntity(ts = System.currentTimeMillis(), kind = "ai_convert", credits = 0, note = "remaining=${r.creditsRemaining}"))
                _s.update { it.copy(busy = false, result = d, resultIsCloud = true, messageRes = if (d == null) R.string.err_process else null) }
            } catch (e: AiException) {
                _s.update { it.copy(busy = false, message = e.message) }
            } catch (e: Exception) {
                _s.update { it.copy(busy = false, message = e.message ?: "error") }
            } finally { tmp.delete() }
        }
    }

    fun playResult() { _s.value.result?.let { c.player.play(it.samples, it.sampleRate) } }
    fun playSource() { _s.value.source?.let { c.player.play(it.samples, it.sampleRate) } }
    fun stopPlayback() = c.player.stop()

    fun saveResult(name: String) {
        val r = _s.value.result ?: return
        val cloud = _s.value.resultIsCloud
        viewModelScope.launch {
            val f = c.files.newRecordingFile(if (cloud) "ai" else "profile")
            withContext(Dispatchers.IO) { WavIO.write(f, r.samples, r.sampleRate) }
            c.db.recordings().insert(RecordingEntity(name = name, path = f.absolutePath, durationMs = r.durationMs,
                sampleRate = r.sampleRate, effectId = null, createdAt = System.currentTimeMillis(), aiGenerated = cloud))
            _s.update { it.copy(messageRes = R.string.saved_to_library) }
        }
    }

    override fun onCleared() { c.player.stop(); if (recorder.recording.value) recorder.stop() }
}

@Composable
fun AiLabScreen(c: AppContainer, vm: AiLabViewModel = viewModel()) {
    val st by vm.state.collectAsState()
    val profiles by vm.profiles.collectAsState(initial = emptyList())
    val mic = rememberMicPermission()
    val level by vm.level.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var disclosureFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showSaveName by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importSource) }
    var samplePickerFor by remember { mutableStateOf<VoiceProfileEntity?>(null) }
    val samplePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val p = samplePickerFor
        if (uri != null && p != null) vm.importSample(p, uri)
    }
    val selected = profiles.firstOrNull { it.id == st.selectedProfile }
    val ready = st.status as? AiStatus.Ready

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.nav_ai), style = MaterialTheme.typography.headlineSmall)

        // ---- provider status (never pretends)
        VmCard {
            SectionTitle(stringResource(R.string.ai_status))
            val s = st.status
            Text(when (s) {
                null -> stringResource(R.string.checking)
                AiStatus.NotConfigured -> stringResource(R.string.cloud_not_configured)
                is AiStatus.Unreachable -> stringResource(R.string.cloud_unreachable, s.detail)
                is AiStatus.BackendWithoutProvider -> stringResource(R.string.cloud_no_provider, s.credits)
                is AiStatus.Ready -> stringResource(R.string.cloud_ready, s.credits)
            })
            Chip(stringResource(R.string.local_demo_mode), MaterialTheme.colorScheme.secondary)
            Text(stringResource(R.string.local_demo_explain), style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ vm.refreshStatus() }) { Text(stringResource(R.string.refresh)) }
        }

        // ---- profiles
        SectionTitle(stringResource(R.string.voice_profiles))
        Text(stringResource(R.string.consent_policy_short), style = MaterialTheme.typography.bodySmall)
        Button({ showCreate = true }) { Text(stringResource(R.string.new_profile)) }
        profiles.forEach { p ->
            val isSel = p.id == st.selectedProfile
            VmCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    TextButton({ vm.select(if (isSel) null else p.id) }) { Text(stringResource(if (isSel) R.string.close else R.string.open)) }
                }
                val n = p.samplePaths.lines().count { it.isNotBlank() }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.samples_count, n))
                    Chip(stringResource(R.string.consent_given), MaterialTheme.colorScheme.secondary)
                    if (p.cloudProfileId != null) Chip(stringResource(R.string.in_cloud))
                }
                if (isSel) {
                    var params by remember(p.id, p.params) { mutableStateOf(EffectParams.deserialize(p.params) ?: EffectParams()) }
                    ParamSlider(stringResource(R.string.pitch), params.pitchSemitones, -12f..12f, { params = params.copy(pitchSemitones = it) }) { "%+.1f st".format(it) }
                    ParamSlider(stringResource(R.string.formant), params.formant, -1f..1f, { params = params.copy(formant = it) })
                    ParamSlider(stringResource(R.string.eq_low), params.eqLowDb, -12f..12f, { params = params.copy(eqLowDb = it) }) { "%+.0f dB".format(it) }
                    ParamSlider(stringResource(R.string.eq_high), params.eqHighDb, -12f..12f, { params = params.copy(eqHighDb = it) }) { "%+.0f dB".format(it) }
                    OutlinedButton({ vm.updateParams(p, params) }) { Text(stringResource(R.string.save)) }

                    SectionTitle(stringResource(R.string.voice_samples))
                    if (st.recording == Recording.SAMPLE) {
                        LevelMeter(level, stringResource(R.string.input_level))
                        Button({ vm.recordedSampleFor(p) }) { Text(stringResource(R.string.stop_save)) }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ mic.ensure { vm.startRecording(Recording.SAMPLE) } }, enabled = st.recording == Recording.NONE) { Text(stringResource(R.string.record_sample)) }
                        OutlinedButton({ samplePickerFor = p; samplePicker.launch(arrayOf("audio/*")) }) { Text(stringResource(R.string.import_audio)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ vm.deleteSamples(p) }, enabled = n > 0) { Text(stringResource(R.string.delete_samples)) }
                        OutlinedButton({ vm.deleteProfile(p) }) { Text(stringResource(R.string.delete_profile)) }
                    }
                    if (ready != null && p.cloudProfileId == null) {
                        OutlinedButton({ disclosureFor = { vm.uploadProfile(p) } }, enabled = n > 0 && !st.busy) { Text(stringResource(R.string.upload_cloud_profile)) }
                    }
                }
            }
        }

        // ---- conversion
        if (selected != null) {
            SectionTitle(stringResource(R.string.convert_voice))
            VmCard {
                Text(st.source?.let { stringResource(R.string.sample_ready, it.durationMs / 1000f) } ?: stringResource(R.string.sample_none))
                if (st.recording == Recording.SOURCE) {
                    LevelMeter(level, stringResource(R.string.input_level))
                    Button({ vm.stopRecording() }) { Text(stringResource(R.string.stop)) }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ mic.ensure { vm.startRecording(Recording.SOURCE) } }, enabled = st.recording == Recording.NONE) { Text(stringResource(R.string.record_sample)) }
                    OutlinedButton({ sourcePicker.launch(arrayOf("audio/*")) }) { Text(stringResource(R.string.import_audio)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.convertLocal(selected) }, enabled = st.source != null && !st.busy) { Text(stringResource(R.string.convert_local)) }
                    Button({ disclosureFor = { vm.convertCloud(selected) } },
                        enabled = ready != null && selected.cloudProfileId != null && st.source != null && !st.busy) { Text(stringResource(R.string.convert_cloud)) }
                }
                if (ready == null) Text(stringResource(R.string.cloud_convert_unavailable), style = MaterialTheme.typography.bodySmall)
                if (st.busy) Text(stringResource(R.string.working))
                st.result?.let {
                    Chip(stringResource(if (st.resultIsCloud) R.string.ai_generated_label else R.string.local_effect_label),
                        if (st.resultIsCloud) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ vm.playResult() }) { Text(stringResource(R.string.preview)) }
                        OutlinedButton({ vm.playSource() }) { Text(stringResource(R.string.play_original)) }
                        OutlinedButton({ vm.stopPlayback() }) { Text(stringResource(R.string.stop)) }
                    }
                    Button({ saveName = ""; showSaveName = true }) { Text(stringResource(R.string.save_to_library)) }
                }
            }
        }
        st.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        st.messageRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.secondary) }
    }

    // ---- dialogs
    if (showCreate) CreateProfileDialog(
        recording = st.recording == Recording.CONSENT, hasConsentAudio = st.consentAudio != null, level = level,
        onRecord = { mic.ensure { vm.startRecording(Recording.CONSENT) } },
        onStop = { vm.stopRecording() },
        onCreate = { name -> vm.createProfile(name); showCreate = false },
        onCancel = { showCreate = false },
    )
    disclosureFor?.let { action ->
        AlertDialog(
            onDismissRequest = { disclosureFor = null },
            title = { Text(stringResource(R.string.cloud_disclosure_title)) },
            text = { Text(stringResource(R.string.cloud_disclosure_body)) },
            confirmButton = { TextButton({ disclosureFor = null; action() }) { Text(stringResource(R.string.agree_upload)) } },
            dismissButton = { TextButton({ disclosureFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showSaveName) AlertDialog(
        onDismissRequest = { showSaveName = false },
        title = { Text(stringResource(R.string.save_to_library)) },
        text = { OutlinedTextField(saveName, { saveName = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true) },
        confirmButton = { TextButton({ vm.saveResult(saveName.ifBlank { "AI Voice ${System.currentTimeMillis() % 100000}" }); showSaveName = false }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton({ showSaveName = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CreateProfileDialog(
    recording: Boolean, hasConsentAudio: Boolean, level: Float,
    onRecord: () -> Unit, onStop: () -> Unit, onCreate: (String) -> Unit, onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var own by remember { mutableStateOf(false) }
    var noDeceive by remember { mutableStateOf(false) }
    var upload by remember { mutableStateOf(false) }
    val canCreate = name.isNotBlank() && own && noDeceive && upload && hasConsentAudio
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.new_profile)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                Text(stringResource(R.string.consent_statement_intro), style = MaterialTheme.typography.bodySmall)
                ConsentRow(own, { own = it }, R.string.consent_own)
                ConsentRow(noDeceive, { noDeceive = it }, R.string.consent_no_deceive)
                ConsentRow(upload, { upload = it }, R.string.consent_upload)
                Text(stringResource(R.string.consent_record_phrase), style = MaterialTheme.typography.bodyMedium)
                if (recording) { LevelMeter(level, stringResource(R.string.input_level)); Button(onStop) { Text(stringResource(R.string.stop)) } }
                else OutlinedButton(onRecord) { Text(stringResource(if (hasConsentAudio) R.string.re_record else R.string.record_consent)) }
                if (hasConsentAudio) Chip(stringResource(R.string.consent_recorded), MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { TextButton({ onCreate(name.trim()) }, enabled = canCreate) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConsentRow(checked: Boolean, onChange: (Boolean) -> Unit, textRes: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(checked, onChange)
        Text(stringResource(textRes), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}
