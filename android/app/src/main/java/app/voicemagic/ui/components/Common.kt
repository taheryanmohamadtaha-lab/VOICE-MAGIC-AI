package app.voicemagic.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.voicemagic.R

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 6.dp))
}

@Composable
fun VmCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    format: (Float) -> String = { "%.2f".format(it) },
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(format(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

/** Horizontal level meter, 0..1. Red when near clipping. */
@Composable
fun LevelMeter(level: Float, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(
            Modifier.fillMaxWidth().height(10.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))
        ) {
            val l = level.coerceIn(0f, 1f)
            val c = when { l > 0.95f -> Color(0xFFFF4D6D); l > 0.75f -> Color(0xFFFFC857); else -> Color(0xFF34D399) }
            Box(Modifier.fillMaxWidth(l).height(10.dp).background(c, RoundedCornerShape(5.dp)))
        }
    }
}

/** Bars waveform. [peaks] in 0..1. Optional [selection] fractions drawn as a highlighted range. */
@Composable
fun Waveform(
    peaks: FloatArray,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    selection: ClosedFloatingPointRange<Float>? = null,
) {
    val sel = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val n = peaks.size
        if (selection != null) {
            drawRect(sel, Offset(size.width * selection.start, 0f), Size(size.width * (selection.endInclusive - selection.start), size.height))
        }
        if (n == 0) {
            drawLine(color.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
            return@Canvas
        }
        val step = size.width / n
        val mid = size.height / 2
        for (i in 0 until n) {
            val h = (peaks[i].coerceIn(0f, 1f) * mid).coerceAtLeast(1.5f)
            val x = i * step + step / 2
            drawLine(color, Offset(x, mid - h), Offset(x, mid + h), strokeWidth = (step * 0.7f).coerceAtLeast(1.5f), cap = StrokeCap.Round)
        }
    }
}

// ------------------------------------------------------------------ microphone permission

class MicPermission(val has: () -> Boolean, val ensure: (onGranted: () -> Unit) -> Unit)

/**
 * Shows an explanation dialog BEFORE the system permission prompt, then runs the action only if granted.
 * Nothing records until the user presses a record/start button that calls ensure().
 */
@Composable
fun rememberMicPermission(): MicPermission {
    val ctx = LocalContext.current
    var showExplain by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { pending?.invoke() } else denied = true
        pending = null
    }
    fun has() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    if (showExplain) {
        AlertDialog(
            onDismissRequest = { showExplain = false; pending = null },
            title = { Text(stringResource(R.string.mic_explain_title)) },
            text = { Text(stringResource(R.string.mic_explain_body)) },
            confirmButton = { TextButton({ showExplain = false; launcher.launch(Manifest.permission.RECORD_AUDIO) }) { Text(stringResource(R.string.continue_label)) } },
            dismissButton = { TextButton({ showExplain = false; pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text(stringResource(R.string.mic_denied_title)) },
            text = { Text(stringResource(R.string.mic_denied_body)) },
            confirmButton = { TextButton({ denied = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    return remember {
        MicPermission(
            has = { has() },
            ensure = { action -> if (has()) action() else { pending = action; showExplain = true } },
        )
    }
}

// ------------------------------------------------------------------ connectivity

@Composable
fun rememberIsOnline(): Boolean {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    fun now(): Boolean {
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    var online by remember { mutableStateOf(now()) }
    DisposableEffect(cm) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = now() }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
        onDispose { cm.unregisterNetworkCallback(cb) }
    }
    return online
}

@Composable
fun Chip(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Box(modifier.background(color.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}
