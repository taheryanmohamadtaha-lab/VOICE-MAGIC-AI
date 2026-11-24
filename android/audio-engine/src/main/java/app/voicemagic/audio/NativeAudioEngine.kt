package app.voicemagic.audio

/** Thin JNI facade over the C++ engine (libvmaudio.so). Use [LiveEngine] / [OfflineProcessor] instead. */
object NativeAudioEngine {
    val available: Boolean = try {
        System.loadLibrary("vmaudio")
        true
    } catch (t: Throwable) {
        false
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeStart(handle: Long, inputDeviceId: Int, outputDeviceId: Int): Boolean
    external fun nativeStop(handle: Long)
    external fun nativeSetParams(handle: Long, params: FloatArray)
    external fun nativeSetMute(handle: Long, mute: Boolean)
    external fun nativeSetMonitor(handle: Long, monitor: Boolean)
    external fun nativeSetRecording(handle: Long, recording: Boolean)
    external fun nativeResetClip(handle: Long)
    external fun nativeReadRecorded(handle: Long, buffer: FloatArray): Int
    external fun nativeGetMeters(handle: Long, out: FloatArray)
    external fun nativeProcessOffline(input: FloatArray, sampleRate: Int, params: FloatArray): FloatArray
}
