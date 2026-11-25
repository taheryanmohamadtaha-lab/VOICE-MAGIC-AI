package app.voicemagic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.voicemagic.BuildConfig
import app.voicemagic.R
import app.voicemagic.ui.components.SectionTitle
import app.voicemagic.ui.components.VmCard

/** About the app and its creator. */
@Composable
fun AboutScreen() {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)

        VmCard {
            SectionTitle(stringResource(R.string.about_creator_label))
            Text("محمدطاها طاهریان", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.about_creator_role), style = MaterialTheme.typography.bodySmall)
        }
        VmCard {
            SectionTitle(stringResource(R.string.about))
            Text(stringResource(R.string.about_description))
        }
        VmCard {
            SectionTitle(stringResource(R.string.about_tech_title))
            Text(stringResource(R.string.about_tech_body), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall)
    }
}
