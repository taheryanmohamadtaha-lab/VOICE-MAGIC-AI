package app.voicemagic.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Pure (JVM-testable) sample-buffer operations used by the editor. All functions return new arrays. */
object AudioOps {
    fun slice(x: FloatArray, from: Int, to: Int): FloatArray {
        val a = from.coerceIn(0, x.size)
        val b = to.coerceIn(a, x.size)
        return x.copyOfRange(a, b)
    }

    /** Remove [from, to) from the buffer. */
    fun cut(x: FloatArray, from: Int, to: Int): FloatArray {
        val a = from.coerceIn(0, x.size)
        val b = to.coerceIn(a, x.size)
        return x.copyOfRange(0, a) + x.copyOfRange(b, x.size)
    }

    fun concat(a: FloatArray, b: FloatArray): FloatArray = a + b

    fun gain(x: FloatArray, db: Float): FloatArray {
        val g = 10f.pow(db / 20f)
        return FloatArray(x.size) { (x[it] * g).coerceIn(-1f, 1f) }
    }

    fun fade(x: FloatArray, sampleRate: Int, inSeconds: Float, outSeconds: Float): FloatArray {
        val y = x.copyOf()
        val nIn = min((inSeconds * sampleRate).toInt(), y.size)
        val nOut = min((outSeconds * sampleRate).toInt(), y.size)
        for (i in 0 until nIn) y[i] *= i / nIn.toFloat()
        for (i in 0 until nOut) y[y.size - 1 - i] *= i / nOut.toFloat()
        return y
    }

    /** Varispeed: factor 2.0 = twice as fast AND one octave higher (no time-stretching). */
    fun varispeed(x: FloatArray, factor: Float): FloatArray {
        require(factor > 0.05f)
        val n = (x.size / factor).toInt()
        return FloatArray(n) { i ->
            val p = i * factor
            val i0 = p.toInt()
            val f = p - i0
            val a = x[min(i0, x.size - 1)]
            val b = x[min(i0 + 1, x.size - 1)]
            a * (1 - f) + b * f
        }
    }

    fun resample(x: FloatArray, fromRate: Int, toRate: Int): FloatArray =
        if (fromRate == toRate || x.isEmpty()) x else varispeed(x, fromRate.toFloat() / toRate.toFloat())

    /** Sum buffers with per-track gain, aligned at 0, clipped to [-1, 1]. */
    fun mix(tracks: List<FloatArray>, gains: List<Float>): FloatArray {
        val n = tracks.maxOfOrNull { it.size } ?: 0
        val out = FloatArray(n)
        tracks.forEachIndexed { t, x ->
            val g = gains.getOrElse(t) { 1f }
            for (i in x.indices) out[i] += x[i] * g
        }
        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
        return out
    }

    fun peak(x: FloatArray): Float { var p = 0f; for (v in x) p = max(p, abs(v)); return p }

    /** Max-abs per bucket, for waveform drawing. */
    fun peaks(x: FloatArray, buckets: Int): FloatArray {
        if (x.isEmpty() || buckets <= 0) return FloatArray(0)
        val out = FloatArray(buckets)
        for (b in 0 until buckets) {
            val s = (b.toLong() * x.size / buckets).toInt()
            val e = max(s + 1, ((b + 1).toLong() * x.size / buckets).toInt()).coerceAtMost(x.size)
            var m = 0f
            for (i in s until e) m = max(m, abs(x[i]))
            out[b] = m
        }
        return out
    }
}
