package app.voicemagic.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavIO {
    private const val HEADER = 44

    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        StreamingWavWriter(file, sampleRate).use { it.write(samples, samples.size) }
    }

    /** Reads PCM 8/16/24/32-bit and float32 WAV, downmixing to mono. Returns null if not a supported WAV. */
    fun read(file: File): AudioData? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var pos = 12
        var fmtTag = 1; var channels = 1; var rate = 44100; var bits = 16
        var dataStart = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                fmtTag = bb.getShort(body).toInt() and 0xFFFF
                channels = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
                if (fmtTag == 0xFFFE && len >= 26) fmtTag = bb.getShort(body + 24).toInt() and 0xFFFF
            } else if (id == "data") {
                dataStart = body
                dataLen = if (len < 0 || body + len > bytes.size) bytes.size - body else len
                break
            }
            pos = body + len + (len and 1)
        }
        if (dataStart < 0 || channels < 1) return null
        val bytesPerSample = bits / 8
        if (bytesPerSample < 1) return null
        val frames = dataLen / (bytesPerSample * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                val o = dataStart + (f * channels + c) * bytesPerSample
                acc += when {
                    fmtTag == 3 && bits == 32 -> bb.getFloat(o)
                    bits == 16 -> bb.getShort(o) / 32768f
                    bits == 8 -> ((bytes[o].toInt() and 0xFF) - 128) / 128f
                    bits == 24 -> (((bytes[o + 2].toInt() shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o].toInt() and 0xFF)) / 8388608f)
                    bits == 32 -> bb.getInt(o) / 2147483648f
                    else -> return null
                }
            }
            out[f] = acc / channels
        }
        AudioData(out, rate)
    }.getOrNull()

    fun floatToPcm16(x: Float): Short = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
}

/** Writes 16-bit mono PCM incrementally and patches the header on close. */
class StreamingWavWriter(file: File, private val sampleRate: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    fun write(samples: FloatArray, count: Int) {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bb.putShort(WavIO.floatToPcm16(samples[i]))
        raf.write(bb.array(), 0, count * 2)
        dataBytes += count * 2L
    }

    override fun close() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(sampleRate); h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }
}
