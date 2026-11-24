package app.voicemagic.audio

/** Mono floating point audio in [-1, 1]. */
class AudioData(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = if (sampleRate <= 0) 0 else samples.size * 1000L / sampleRate
}
