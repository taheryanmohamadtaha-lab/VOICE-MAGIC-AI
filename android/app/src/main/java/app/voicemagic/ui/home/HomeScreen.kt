package app.voicemagic.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.voicemagic.AppContainer
import app.voicemagic.R
import app.voicemagic.ai.AiStatus
import app.voicemagic.effects.EffectCatalog
import app.voicemagic.ui.components.Chip
import app.voicemagic.ui.components.SectionTitle
import app.voicemagic.ui.components.VmCard
import app.voicemagic.ui.components.formatDuration
import app.voicemagic.ui.components.rememberIsOnline
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun HomeScreen(c: AppContainer, go: (String) -> Unit) {
    val recent by c.settings.recentEffects.collectAsState(initial = emptyList())
    val recordings by c.db.recordings().recent(5).collectAsState(initial = emptyList())
    val online = rememberIsOnline()
    val status by produceState<AiStatus?>(initialValue = null, online) { value = c.cloud.status() }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        AnimatedWave()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ go("live") }, Modifier.weight(1f)) { Text(stringResource(R.string.live_button)) }
            OutlinedButton({ go("studio") }, Modifier.weight(1f)) { Text(stringResource(R.string.quick_changer)) }
        }
        OutlinedButton({ go("ai") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.nav_ai)) }

        VmCard {
            SectionTitle(stringResource(R.string.status_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(if (online) R.string.online else R.string.offline))
                Chip(stringResource(R.string.offline_features_ok), MaterialTheme.colorScheme.secondary)
            }
            val s = status
            Text(
                when (s) {
                    null -> stringResource(R.string.checking)
                    AiStatus.NotConfigured -> stringResource(R.string.cloud_not_configured)
                    is AiStatus.Unreachable -> stringResource(R.string.cloud_unreachable, s.detail)
                    is AiStatus.BackendWithoutProvider -> stringResource(R.string.cloud_no_provider, s.credits)
                    is AiStatus.Ready -> stringResource(R.string.cloud_ready, s.credits) + if (s.premium) " • " + stringResource(R.string.premium) else " • " + stringResource(R.string.free_plan)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionTitle(stringResource(R.string.recent_effects))
        if (recent.isEmpty()) Text(stringResource(R.string.none_yet), style = MaterialTheme.typography.bodyMedium)
        else Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            recent.take(4).mapNotNull { EffectCatalog.byId(it) }.forEach { Chip(stringResource(it.name)) }
        }

        SectionTitle(stringResource(R.string.recent_recordings))
        if (recordings.isEmpty()) Text(stringResource(R.string.none_yet), style = MaterialTheme.typography.bodyMedium)
        recordings.forEach { r ->
            VmCard { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge)
                Text(formatDuration(r.durationMs), style = MaterialTheme.typography.bodyMedium)
            } }
        }
    }
}

@Composable
private fun AnimatedWave() {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val brush = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val bars = 48
        val step = size.width / bars
        val mid = size.height / 2
        for (i in 0 until bars) {
            val env = sin(PI * i / bars).toFloat()
            val h = (0.15f + 0.85f * kotlin.math.abs(sin(phase + i * 0.35f))) * env * mid
            drawLine(brush, Offset(i * step + step / 2, mid - h), Offset(i * step + step / 2, mid + h), strokeWidth = step * 0.55f, cap = StrokeCap.Round)
        }
    }
}
