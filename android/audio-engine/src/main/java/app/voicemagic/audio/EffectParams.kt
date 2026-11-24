package app.voicemagic.audio

/**
 * All adjustable DSP parameters. [toArray] order MUST match enum Param in dsp.h.
 * Note: [formant] is a spectral-tilt approximation (brighter/darker timbre), not true formant shifting.
 */
data class EffectParams(
    val pitchSemitones: Float = 0f,
    val formant: Float = 0f,
    val echoMix: Float = 0f,
    val echoDelayMs: Float = 250f,
    val echoFeedback: Float = 0.35f,
    val reverbMix: Float = 0f,
    val reverbRoom: Float = 0.5f,
    val ringFreqHz: Float = 30f,
    val ringMix: Float = 0f,
    val eqLowDb: Float = 0f,
    val eqMidDb: Float = 0f,
    val eqHighDb: Float = 0f,
    val gateDb: Float = -90f,
    val denoise: Boolean = false,
    val outGain: Float = 1f,
    val distortion: Float = 0f,
    val bandpass: Boolean = false,
) {
    fun toArray(): FloatArray = floatArrayOf(
        pitchSemitones, formant, echoMix, echoDelayMs, echoFeedback, reverbMix, reverbRoom,
        ringFreqHz, ringMix, eqLowDb, eqMidDb, eqHighDb, gateDb,
        if (denoise) 1f else 0f, outGain, distortion, if (bandpass) 1f else 0f,
    )

    /** Compact text form for database storage. */
    fun serialize(): String = toArray().joinToString(",")

    val hasTail: Boolean get() = echoMix > 0.001f || reverbMix > 0.001f

    companion object {
        const val COUNT = 17

        fun fromArray(a: FloatArray): EffectParams {
            require(a.size >= COUNT) { "need $COUNT values" }
            return EffectParams(
                a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10], a[11], a[12],
                a[13] > 0.5f, a[14], a[15], a[16] > 0.5f,
            )
        }

        fun deserialize(s: String): EffectParams? = runCatching {
            fromArray(s.split(",").map { it.trim().toFloat() }.toFloatArray())
        }.getOrNull()
    }
}
