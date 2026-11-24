package app.voicemagic.ui.live

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.voicemagic.AppContainer
import app.voicemagic.R
import app.voicemagic.VoiceMagicApp
import app.voicemagic.audio.EffectParams
import app.voicemagic.audio.WavIO
import app.voicemagic.data.RecordingEntity
import app.voicemagic.effects.EffectCatalog
import app.voicemagic.ui.components.Chip
import app.voicemagic.ui.components.LevelMeter
import app.voicemagic.ui.components.ParamSlider
import app.voicemagic.ui.components.SectionTitle
import app.voicemagic.ui.components.VmCard
import app.voicemagic.ui.components.Waveform
import app.voicemagic.ui.components.formatDuration
import app.voicemagic.ui.components.rememberMicPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveState(
    val effectId: String? = null,
    val intensity: Float = 0.5f,
    val params: EffectParams = EffectParams(),
    val muted: Boolean = false,
    val monitor: Boolean = false,
    val headphones: Boolean = false,
    val elapsedMs: Long = 0,
    val errorRes: Int? = null,
    val savedRes: Int? = null,
    val interrupted: Boolean = false,
)

class LiveViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as VoiceMagicApp).container
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val engine = c.live
    private val _s = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _s.asStateFlow()
    val history = mutableStateListOf<Float>()  // input peaks for the live waveform
    private var focus: AudioFocusRequest? = null

    private fun headphonesConnected(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        when (it.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> false
        }
    }

    init {
        viewModelScope.launch {
            engine.meters.collect { m -> if (engine.running.value) { history.add(m.inPeak); if (history.size > 90) history.removeAt(0) } }
        }
        viewModelScope.launch {
            engine.needsRestart.collect { need -> if (need && engine.running.value) restart() }
        }
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                // Incoming call, navigation prompt, other app took audio: stop cleanly (saves any recording).
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    viewModelScope.launch { _s.update { it.copy(interrupted = true) }; stop() }
                }
            }.build()
        focus = req
        audio.requestAudioFocus(req)
    }

    fun start() {
        val hp = headphonesConnected()
        engine.setParams(_s.value.params)
        // Without headphones, monitoring the speaker would feed back into the mic: default it off.
        engine.setMonitor(hp)
        if (engine.start()) {
            requestFocus()
            history.clear()
            _s.update { it.copy(headphones = hp, monitor = hp, muted = false, errorRes = null, savedRes = null, interrupted = false, elapsedMs = 0) }
            engine.setMuted(false)
        } else {
            _s.update { it.copy(errorRes = R.string.err_live_start) }
        }
    }

    private fun restart() {
        val wasRecording = engine.recording.value
        // A route change (e.g. headset unplugged) invalidates the streams. Restart on the new route.
        viewModelScope.launch {
            val f = engine.stop()
            if (f != null) saveRecording(f)
            delay(200)
            start()
            if (wasRecording) startRecording()
        }
    }

    fun stop() {
        val f = engine.stop()
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
        if (f != null) viewModelScope.launch { saveRecording(f) }
    }

    private suspend fun saveRecording(f: java.io.File) {
        val d = WavIO.read(f) ?: return
        c.db.recordings().insert(RecordingEntity(name = "Live ${System.currentTimeMillis() % 100000}", path = f.absolutePath,
            durationMs = d.durationMs, sampleRate = d.sampleRate, effectId = _s.value.effectId, createdAt = System.currentTimeMillis()))
        _s.update { it.copy(savedRes = R.string.saved_to_library) }
    }

    fun startRecording() {
        if (engine.startRecording(c.files.newRecordingFile("live"))) {
            viewModelScope.launch {
                val t0 = System.currentTimeMillis()
                while (engine.recording.value) { _s.update { it.copy(elapsedMs = System.currentTimeMillis() - t0) }; delay(250) }
            }
        }
    }

    fun stopRecording() {
        val f = engine.stopRecording()
        if (f != null) viewModelScope.launch { saveRecording(f) }
    }

    fun select(id: String) {
        val fx = EffectCatalog.byId(id) ?: return
        _s.update { it.copy(effectId = id, params = fx.build(it.intensity)) }
        apply()
        viewModelScope.launch { c.settings.pushRecent(id) }
    }

    fun setIntensity(v: Float) {
        _s.update { st -> st.copy(intensity = v, params = st.effectId?.let { EffectCatalog.byId(it)?.build(v) } ?: st.params) }
        apply()
    }

    fun edit(f: (EffectParams) -> EffectParams) { _s.update { it.copy(params = f(it.params)) }; apply() }
    private fun apply() = engine.setParams(_s.value.params)

    fun setMuted(m: Boolean) { engine.setMuted(m); _s.update { it.copy(muted = m) } }
    fun setMonitor(m: Boolean) { engine.setMonitor(m); _s.update { it.copy(monitor = m) } }
    fun clearInterrupted() = _s.update { it.copy(interrupted = false) }

    override fun onCleared() { if (engine.running.value) stop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(c: AppContainer, vm: LiveViewModel = viewModel()) {
    val st by vm.state.collectAsState()
    val running by vm.engine.running.collectAsState()
    val recording by vm.engine.recording.collectAsState()
    val meters by vm.engine.meters.collectAsState()
    val mic = rememberMicPermission()

    // No recording in the background: the engine is stopped whenever the screen leaves the foreground.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && vm.engine.running.value) vm.stop() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.nav_live), style = MaterialTheme.typography.headlineSmall)

        VmCard {
            Waveform(vm.history.toFloatArray())
            LevelMeter(meters.inPeak, stringResource(R.string.input_level))
            LevelMeter(meters.outPeak, stringResource(R.string.output_level))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (running) {
                    Chip(if (meters.lowLatency) stringResource(R.string.low_latency) else stringResource(R.string.standard_latency),
                        if (meters.lowLatency) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                    if (meters.latencyMs >= 0) Chip("%.0f ms".format(meters.latencyMs))
                    Chip("${meters.sampleRate} Hz")
                }
                if (meters.clipCount > 0) Chip(stringResource(R.string.clipping), MaterialTheme.colorScheme.error)
            }
            if (recording) Text("● " + formatDuration(st.elapsedMs), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
            st.errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            st.savedRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.secondary) }
            if (st.interrupted) Text(stringResource(R.string.live_interrupted), color = MaterialTheme.colorScheme.error)
            if (running && st.monitor && !st.headphones) Text(stringResource(R.string.feedback_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) Button({ mic.ensure { vm.start() } }) { Text(stringResource(R.string.start)) }
                else Button({ vm.stop() }) { Text(stringResource(R.string.stop)) }
                if (running && !recording) OutlinedButton({ vm.startRecording() }) { Text(stringResource(R.string.record)) }
                if (recording) OutlinedButton({ vm.stopRecording() }) { Text(stringResource(R.string.stop_save)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.mute)); Switch(st.muted, vm::setMuted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(if (st.headphones || !running) R.string.headphone_monitor else R.string.headphone_monitor_nohp))
                Switch(st.monitor, vm::setMonitor)
            }
        }

        SectionTitle(stringResource(R.string.effect))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(EffectCatalog.all, key = { it.id }) { fx ->
                FilterChip(selected = st.effectId == fx.id, onClick = { vm.select(fx.id) }, label = { Text(stringResource(fx.name)) })
            }
        }
        VmCard {
            if (st.effectId != null) ParamSlider(stringResource(R.string.intensity), st.intensity, 0f..1f, vm::setIntensity) { "${(it * 100).toInt()}%" }
            val p = st.params
            ParamSlider(stringResource(R.string.pitch), p.pitchSemitones, -12f..12f, { v -> vm.edit { it.copy(pitchSemitones = v) } }) { "%+.1f st".format(it) }
            ParamSlider(stringResource(R.string.formant), p.formant, -1f..1f, { v -> vm.edit { it.copy(formant = v) } })
            ParamSlider(stringResource(R.string.echo), p.echoMix, 0f..1f, { v -> vm.edit { it.copy(echoMix = v) } })
            ParamSlider(stringResource(R.string.reverb), p.reverbMix, 0f..1f, { v -> vm.edit { it.copy(reverbMix = v) } })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.noise_suppression)); Switch(p.denoise, { v -> vm.edit { it.copy(denoise = v) } })
            }
        }
        Text(stringResource(R.string.live_note), style = MaterialTheme.typography.bodySmall)
    }
}
