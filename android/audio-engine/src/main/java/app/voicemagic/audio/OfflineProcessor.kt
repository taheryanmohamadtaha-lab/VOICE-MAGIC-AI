package app.voicemagic.audio

/** Applies the native DSP chain to a finished buffer (Studio previews, Editor effects, export). */
object OfflineProcessor {
    /** @param tailSeconds extra silence appended so echo/reverb tails are not cut off. */
    fun process(samples: FloatArray, sampleRate: Int, params: EffectParams, tailSeconds: Float = 1.5f): FloatArray {
        if (samples.isEmpty()) return samples
        check(NativeAudioEngine.available) { "Native audio library not loaded" }
        val tail = if (params.hasTail) (tailSeconds * sampleRate).toInt() else 0
        val input = if (tail > 0) samples.copyOf(samples.size + tail) else samples
        return NativeAudioEngine.nativeProcessOffline(input, sampleRate, params.toArray())
    }
}
