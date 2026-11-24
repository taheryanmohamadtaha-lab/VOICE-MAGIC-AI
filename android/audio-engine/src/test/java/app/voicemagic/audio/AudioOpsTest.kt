package app.voicemagic.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioOpsTest {
    @Test fun cutRemovesRange() {
        assertArrayEquals(floatArrayOf(1f, 4f), AudioOps.cut(floatArrayOf(1f, 2f, 3f, 4f), 1, 3), 0f)
    }

    @Test fun varispeedDoubleHalvesLength() {
        val y = AudioOps.varispeed(FloatArray(1000) { it / 1000f }, 2f)
        assertEquals(500, y.size)
    }

    @Test fun mixClipsAndAligns() {
        val m = AudioOps.mix(listOf(floatArrayOf(0.8f, 0.8f), floatArrayOf(0.8f)), listOf(1f, 1f))
        assertEquals(2, m.size)
        assertEquals(1f, m[0], 0f)
        assertEquals(0.8f, m[1], 1e-6f)
    }

    @Test fun fadeStartsAtZero() {
        val y = AudioOps.fade(FloatArray(100) { 1f }, 100, 0.5f, 0f)
        assertEquals(0f, y[0], 0f)
        assertEquals(1f, y[99], 0f)
    }

    @Test fun paramsRoundTrip() {
        val p = EffectParams(pitchSemitones = 3f, denoise = true, bandpass = true, reverbMix = 0.4f)
        assertEquals(EffectParams.COUNT, p.toArray().size)
        assertEquals(p, EffectParams.deserialize(p.serialize()))
    }

    @Test fun wavRoundTrip() {
        val f = File.createTempFile("t", ".wav")
        val src = FloatArray(2000) { Math.sin(it * 0.05).toFloat() * 0.5f }
        WavIO.write(f, src, 22050)
        val d = WavIO.read(f)
        assertNotNull(d)
        assertEquals(22050, d!!.sampleRate)
        assertEquals(src.size, d.samples.size)
        assertTrue(Math.abs(src[100] - d.samples[100]) < 1e-3)
        f.delete()
    }
}
