package app.voicemagic.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Simple (non-real-time-monitored) microphone capture used for samples and editor recordings. */
class PcmRecorder(private val scope: CoroutineScope) {
    private var record: AudioRecord? = null
    private var job: Job? = null
    private var data = FloatArray(44100 * 10)
    private var count = 0
    var sampleRate = 44100
        private set
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** RECORD_AUDIO must be granted. [maxSeconds] stops the recording automatically. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(rate: Int = 44100, maxSeconds: Int = 300, onAutoStop: (() -> Unit)? = null): Boolean {
        if (_recording.value) return true
        sampleRate = rate
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, min * 4)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        record = rec
        count = 0
        data = FloatArray(rate * 10)
        rec.startRecording()
        _recording.value = true
        job = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(2048)
            val limit = rate * maxSeconds
            while (isActive && _recording.value) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (count + n > data.size) data = data.copyOf(data.size * 2)
                var pk = 0f
                for (i in 0 until n) { val v = buf[i] / 32768f; data[count++] = v; pk = maxOf(pk, abs(v)) }
                _level.value = pk
                if (count >= limit) { _recording.value = false; onAutoStop?.invoke() }
            }
        }
        return true
    }

    @Synchronized
    fun stop(): AudioData {
        _recording.value = false
        runBlockingJoin()
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        _level.value = 0f
        return AudioData(data.copyOf(count), sampleRate)
    }

    private fun runBlockingJoin() {
        val j = job
        job = null
        kotlinx.coroutines.runBlocking { j?.join() }
    }
}
