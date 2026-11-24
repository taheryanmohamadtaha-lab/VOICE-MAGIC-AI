package app.voicemagic.ui.library

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.voicemagic.AppContainer
import app.voicemagic.EditorRequest
import app.voicemagic.R
import app.voicemagic.audio.WavIO
import app.voicemagic.data.ProjectEntity
import app.voicemagic.data.RecordingEntity
import app.voicemagic.ui.components.Chip
import app.voicemagic.ui.components.VmCard
import app.voicemagic.ui.components.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(c: AppContainer, go: (String) -> Unit) {
    val recordings by c.db.recordings().all().collectAsState(initial = emptyList())
    val projects by c.db.projects().all().collectAsState(initial = emptyList())
    val playing by c.player.playing.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<RecordingEntity?>(null) }
    var confirmDeleteProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var playingId by remember { mutableStateOf<Long?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nav_library), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text(stringResource(R.string.recordings)) })
            FilterChip(tab == 1, { tab = 1 }, label = { Text(stringResource(R.string.projects)) })
        }
        if (tab == 0) {
            if (recordings.isEmpty()) Text(stringResource(R.string.none_yet))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recordings, key = { it.id }) { r ->
                    VmCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(formatDuration(r.durationMs))
                        }
                        if (r.aiGenerated) Chip(stringResource(R.string.ai_generated_label), MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val isThis = playing && playingId == r.id
                            OutlinedButton({
                                if (isThis) c.player.stop() else scope.launch {
                                    val d = withContext(Dispatchers.IO) { WavIO.read(File(r.path)) }
                                    if (d != null) { playingId = r.id; c.player.play(d.samples, d.sampleRate) }
                                }
                            }) { Text(stringResource(if (isThis) R.string.stop else R.string.play)) }
                            OutlinedButton({
                                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(r.path))
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, null))
                            }) { Text(stringResource(R.string.share)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton({ c.editorInbox.value = EditorRequest.OpenFile(r.path); go("editor") }) { Text(stringResource(R.string.open_in_editor)) }
                            TextButton({ confirmDelete = r }) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        } else {
            if (projects.isEmpty()) Text(stringResource(R.string.none_yet))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { p ->
                    VmCard {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton({ c.editorInbox.value = EditorRequest.OpenProject(p.id); go("editor") }) { Text(stringResource(R.string.open_in_editor)) }
                            TextButton({ confirmDeleteProject = p }) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(r.name) },
            confirmButton = { TextButton({
                scope.launch { withContext(Dispatchers.IO) { File(r.path).delete() }; c.db.recordings().delete(r) }
                confirmDelete = null
            }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    confirmDeleteProject?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDeleteProject = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(p.name) },
            confirmButton = { TextButton({
                scope.launch { withContext(Dispatchers.IO) { File(c.files.projects, p.id.toString()).deleteRecursively() }; c.db.projects().delete(p) }
                confirmDeleteProject = null
            }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ confirmDeleteProject = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
