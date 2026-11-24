package app.voicemagic.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

/** Decodes any Android-supported audio file (MP3, AAC/M4A, OGG, FLAC, WAV...) to mono float. */
object AudioDecoder {
    const val MAX_SECONDS = 300  // editor limit (memory protection)

    fun decode(context: Context, uri: Uri): AudioData {
        // Fast path: our own / standard WAV files.
        if (uri.scheme == "file") {
            uri.path?.let { WavIO.read(File(it)) }?.let { return it }
        } else {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    val tmp = File.createTempFile("imp", ".wav", context.cacheDir)
                    tmp.outputStream().use { ins.copyTo(it) }
                    val d = WavIO.read(tmp)
                    tmp.delete()
                    d
                }
            }.getOrNull()?.let { return it }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            return decodeWith(extractor)
        } finally {
            extractor.release()
        }
    }

    private fun decodeWith(extractor: MediaExtractor): AudioData {
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
        }
        require(track >= 0 && format != null) { "No audio track found" }
        extractor.selectTrack(track)
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var out = FloatArray(sampleRate * 10)
        var count = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = sb.remaining() / maxOf(1, channels)
                        if (count + frames > out.size) out = out.copyOf(maxOf(out.size * 2, count + frames))
                        for (f in 0 until frames) {
                            var acc = 0f
                            for (c in 0 until channels) acc += sb.get() / 32768f
                            out[count++] = acc / channels
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        require(count <= sampleRate * MAX_SECONDS) { "Audio longer than $MAX_SECONDS seconds is not supported in the editor" }
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return AudioData(out.copyOf(count), sampleRate)
    }
}
