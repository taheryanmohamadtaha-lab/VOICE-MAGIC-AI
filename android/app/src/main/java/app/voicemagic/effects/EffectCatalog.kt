package app.voicemagic.effects

import androidx.annotation.StringRes
import app.voicemagic.R
import app.voicemagic.audio.EffectParams

enum class EffectCategory(@StringRes val label: Int) {
    VOICE(R.string.cat_voice),
    CHARACTER(R.string.cat_character),
    COMMS(R.string.cat_comms),
    SPACE(R.string.cat_space),
    CINEMATIC(R.string.cat_cinematic),
    CUSTOM(R.string.cat_custom),
}

/** Every effect is implemented by the offline native DSP chain. No cloud needed. */
data class VoiceEffect(
    val id: String,
    @StringRes val name: Int,
    @StringRes val description: Int,
    val category: EffectCategory,
    val premium: Boolean = false,
    val build: (intensity: Float) -> EffectParams,
) {
    val offline: Boolean = true
}

object EffectCatalog {
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    val all: List<VoiceEffect> = listOf(
        VoiceEffect("male", R.string.fx_male, R.string.fx_male_d, EffectCategory.VOICE) { t ->
            EffectParams(pitchSemitones = lerp(-1f, -5f, t), formant = -0.6f * t, eqLowDb = 2f * t)
        },
        VoiceEffect("female", R.string.fx_female, R.string.fx_female_d, EffectCategory.VOICE) { t ->
            EffectParams(pitchSemitones = lerp(2f, 6f, t), formant = 0.6f * t, eqHighDb = 2f * t)
        },
        VoiceEffect("child", R.string.fx_child, R.string.fx_child_d, EffectCategory.VOICE) { t ->
            EffectParams(pitchSemitones = lerp(4f, 8f, t), formant = 0.8f * t)
        },
        VoiceEffect("elderly", R.string.fx_elderly, R.string.fx_elderly_d, EffectCategory.VOICE) { t ->
            EffectParams(pitchSemitones = lerp(-0.5f, -2f, t), ringFreqHz = 5.5f, ringMix = 0.35f * t, eqHighDb = -5f * t)
        },
        VoiceEffect("robot", R.string.fx_robot, R.string.fx_robot_d, EffectCategory.CHARACTER) { t ->
            EffectParams(pitchSemitones = -1f, ringFreqHz = 55f, ringMix = lerp(0.6f, 1f, t), eqMidDb = 3f)
        },
        VoiceEffect("alien", R.string.fx_alien, R.string.fx_alien_d, EffectCategory.CHARACTER) { t ->
            EffectParams(pitchSemitones = lerp(3f, 7f, t), ringFreqHz = 180f, ringMix = 0.5f * t,
                echoMix = 0.3f * t, echoDelayMs = 40f, echoFeedback = 0.4f)
        },
        VoiceEffect("monster", R.string.fx_monster, R.string.fx_monster_d, EffectCategory.CHARACTER) { t ->
            EffectParams(pitchSemitones = lerp(-5f, -11f, t), formant = -0.8f, distortion = lerp(0.2f, 0.6f, t), reverbMix = 0.2f)
        },
        VoiceEffect("cartoon", R.string.fx_cartoon, R.string.fx_cartoon_d, EffectCategory.CHARACTER) { t ->
            EffectParams(pitchSemitones = lerp(6f, 10f, t), formant = 0.4f, eqHighDb = 4f)
        },
        VoiceEffect("helium", R.string.fx_helium, R.string.fx_helium_d, EffectCategory.CHARACTER) { t ->
            EffectParams(pitchSemitones = lerp(7f, 12f, t), formant = 1f)
        },
        VoiceEffect("chipmunk", R.string.fx_chipmunk, R.string.fx_chipmunk_d, EffectCategory.CHARACTER) { t ->
            EffectParams(pitchSemitones = lerp(9f, 12f, t), formant = 1f, eqHighDb = 2f)
        },
        VoiceEffect("deep", R.string.fx_deep, R.string.fx_deep_d, EffectCategory.CHARACTER) { t ->
            EffectParams(pitchSemitones = lerp(-4f, -10f, t), formant = -0.8f, eqLowDb = 3f)
        },
        VoiceEffect("radio", R.string.fx_radio, R.string.fx_radio_d, EffectCategory.COMMS) { t ->
            EffectParams(bandpass = true, distortion = 0.1f + 0.2f * t, eqMidDb = 4f * t)
        },
        VoiceEffect("telephone", R.string.fx_phone, R.string.fx_phone_d, EffectCategory.COMMS) { t ->
            EffectParams(bandpass = true, eqMidDb = lerp(3f, 8f, t), distortion = 0.15f)
        },
        VoiceEffect("echo", R.string.fx_echo, R.string.fx_echo_d, EffectCategory.SPACE) { t ->
            EffectParams(echoMix = lerp(0.3f, 0.8f, t), echoDelayMs = 300f, echoFeedback = lerp(0.3f, 0.6f, t))
        },
        VoiceEffect("reverb", R.string.fx_reverb, R.string.fx_reverb_d, EffectCategory.SPACE) { t ->
            EffectParams(reverbMix = lerp(0.25f, 0.7f, t), reverbRoom = lerp(0.5f, 0.95f, t))
        },
        VoiceEffect("trailer", R.string.fx_trailer, R.string.fx_trailer_d, EffectCategory.CINEMATIC, premium = true) { t ->
            EffectParams(pitchSemitones = lerp(-1f, -4f, t), formant = -0.4f, eqLowDb = 5f, reverbMix = lerp(0.3f, 0.6f, t),
                reverbRoom = 0.9f, echoMix = 0.2f, echoDelayMs = 380f, echoFeedback = 0.35f)
        },
        VoiceEffect("cave", R.string.fx_cave, R.string.fx_cave_d, EffectCategory.CINEMATIC, premium = true) { t ->
            EffectParams(reverbMix = lerp(0.5f, 0.85f, t), reverbRoom = 1f, echoMix = lerp(0.3f, 0.6f, t),
                echoDelayMs = 450f, echoFeedback = 0.55f, eqHighDb = -4f)
        },
    )

    fun byId(id: String): VoiceEffect? = all.firstOrNull { it.id == id }
}
