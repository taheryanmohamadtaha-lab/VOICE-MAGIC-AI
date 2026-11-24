package app.voicemagic.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Plays an in-memory mono float buffer. */
class PcmPlayer(private val scope: CoroutineScope) {
    private var track: AudioTrack? = null
    private var job: Job? = null
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Synchronized
    fun play(samples: FloatArray, sampleRate: Int, onDone: (() -> Unit)? = null) {
        stop()
        if (samples.isEmpty()) { onDone?.invoke(); return }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track = t
        t.play()
        _playing.value = true
        val ms = samples.size * 1000L / sampleRate
        job = scope.launch {
            delay(ms + 150)
            if (track === t) { stop(); onDone?.invoke() }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        _playing.value = false
    }
}
