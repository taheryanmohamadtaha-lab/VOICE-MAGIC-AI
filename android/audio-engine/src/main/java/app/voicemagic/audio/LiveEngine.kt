package app.voicemagic.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class Meters(
    val inPeak: Float = 0f,
    val outPeak: Float = 0f,
    val clipCount: Int = 0,
    val sampleRate: Int = 0,
    val latencyMs: Float = -1f,
    val lowLatency: Boolean = false,
    val droppedRecordSamples: Int = 0,
)

/**
 * Real-time microphone -> DSP -> output engine. Safe to call from the main thread:
 * the audio itself runs on the native Oboe callback thread.
 */
class LiveEngine(private val scope: CoroutineScope) {
    private var handle = 0L
    private var poll: Job? = null
    private var writer: Job? = null
    private var currentFile: File? = null
    private var params = EffectParams()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _meters = MutableStateFlow(Meters())
    val meters: StateFlow<Meters> = _meters.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    /** Set when the native layer reports a stream error (device unplugged, route change, ...). */
    private val _needsRestart = MutableStateFlow(false)
    val needsRestart: StateFlow<Boolean> = _needsRestart.asStateFlow()

    private var muted = false
    private var monitor = true

    /** RECORD_AUDIO permission must already be granted. Returns false if streams could not be opened. */
    @Synchronized
    fun start(inputDeviceId: Int = 0, outputDeviceId: Int = 0): Boolean {
        if (_running.value) return true
        if (!NativeAudioEngine.available) return false
        handle = NativeAudioEngine.nativeCreate()
        NativeAudioEngine.nativeSetParams(handle, params.toArray())
        NativeAudioEngine.nativeSetMute(handle, muted)
        NativeAudioEngine.nativeSetMonitor(handle, monitor)
        if (!NativeAudioEngine.nativeStart(handle, inputDeviceId, outputDeviceId)) {
            NativeAudioEngine.nativeDestroy(handle)
            handle = 0
            return false
        }
        _needsRestart.value = false
        _running.value = true
        val h = handle
        poll = scope.launch(Dispatchers.Default) {
            val m = FloatArray(9)
            while (isActive) {
                NativeAudioEngine.nativeGetMeters(h, m)
                _meters.value = Meters(m[0], m[1], m[2].toInt(), m[4].toInt(), m[6], m[7] > 0.5f, m[8].toInt())
                if (m[3] > 0.5f) _needsRestart.value = true
                delay(33)
            }
        }
        return true
    }

    @Synchronized
    fun stop(): File? {
        if (!_running.value) return null
        val f = stopRecording()
        poll?.cancel(); poll = null
        NativeAudioEngine.nativeStop(handle)
        NativeAudioEngine.nativeDestroy(handle)
        handle = 0
        _running.value = false
        _meters.value = Meters()
        return f
    }

    fun setParams(p: EffectParams) {
        params = p
        if (handle != 0L) NativeAudioEngine.nativeSetParams(handle, p.toArray())
    }

    fun setMuted(m: Boolean) { muted = m; if (handle != 0L) NativeAudioEngine.nativeSetMute(handle, m) }
    fun setMonitor(m: Boolean) { monitor = m; if (handle != 0L) NativeAudioEngine.nativeSetMonitor(handle, m) }
    fun resetClip() { if (handle != 0L) NativeAudioEngine.nativeResetClip(handle) }

    /** Starts writing the *processed* signal to a 16-bit mono WAV file (I/O happens off the audio thread). */
    @Synchronized
    fun startRecording(file: File): Boolean {
        if (!_running.value || _recording.value) return false
        currentFile = file
        val h = handle
        val sr = _meters.value.sampleRate.takeIf { it > 0 } ?: 48000
        NativeAudioEngine.nativeSetRecording(h, true)
        _recording.value = true
        writer = scope.launch(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val buf = FloatArray(8192)
            StreamingWavWriter(file, sr).use { w ->
                while (isActive && _recording.value) {
                    val n = NativeAudioEngine.nativeReadRecorded(h, buf)
                    if (n > 0) w.write(buf, n) else delay(20)
                }
                // drain what is left
                while (true) {
                    val n = NativeAudioEngine.nativeReadRecorded(h, buf)
                    if (n <= 0) break
                    w.write(buf, n)
                }
            }
        }
        return true
    }

    /** Returns the finished file, or null if not recording. */
    @Synchronized
    fun stopRecording(): File? {
        if (!_recording.value) return null
        if (handle != 0L) NativeAudioEngine.nativeSetRecording(handle, false)
        _recording.value = false
        val w = writer
        writer = null
        // let the writer drain; join is bounded because the flag is already false
        kotlinx.coroutines.runBlocking { w?.join() }
        return currentFile
    }
}
