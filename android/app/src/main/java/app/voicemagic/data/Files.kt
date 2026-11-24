package app.voicemagic.data

import android.content.Context
import java.io.File

/** All app files live in internal storage (private to the app, removed on uninstall). */
class AppFiles(private val context: Context) {
    val recordings: File get() = File(context.filesDir, "recordings").apply { mkdirs() }
    val exports: File get() = File(context.filesDir, "exports").apply { mkdirs() }
    val projects: File get() = File(context.filesDir, "projects").apply { mkdirs() }
    val voiceSamples: File get() = File(context.filesDir, "voice_samples").apply { mkdirs() }

    fun newRecordingFile(prefix: String = "rec"): File =
        File(recordings, "${prefix}_${System.currentTimeMillis()}.wav")

    fun wipeVoiceSamples() { voiceSamples.deleteRecursively() }
    fun wipeAll() {
        recordings.deleteRecursively(); exports.deleteRecursively()
        projects.deleteRecursively(); voiceSamples.deleteRecursively()
    }
}
