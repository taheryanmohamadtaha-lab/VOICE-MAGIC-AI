package app.voicemagic

import android.content.Context
import androidx.room.Room
import app.voicemagic.ai.BackendClient
import app.voicemagic.ai.CloudAiClient
import app.voicemagic.audio.LiveEngine
import app.voicemagic.audio.PcmPlayer
import app.voicemagic.data.AppDatabase
import app.voicemagic.data.AppFiles
import app.voicemagic.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Manual dependency container (no DI framework needed for an app this size). */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "voice_magic.db").build()
    val settings = SettingsRepository(context)
    val files = AppFiles(context)
    val cloud: CloudAiClient = BackendClient(settings)
    val live = LiveEngine(appScope)
    val player = PcmPlayer(appScope)

    /** Library -> Editor hand-over: path of a WAV file or a project id to open. */
    val editorInbox = MutableStateFlow<EditorRequest?>(null)
}

sealed class EditorRequest {
    data class OpenFile(val path: String) : EditorRequest()
    data class OpenProject(val id: Long) : EditorRequest()
}
