package cz.aalyrics.presentation.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cz.aalyrics.R
import cz.aalyrics.service.MediaNotificationListener

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsState()
    val ctx = LocalContext.current
    var listenerEnabled by remember { mutableStateOf(MediaNotificationListener.isEnabled(ctx)) }

    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.settings_notification_permission),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (listenerEnabled) stringResource(R.string.settings_notification_granted)
                    else stringResource(R.string.settings_notification_missing),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    listenerEnabled = MediaNotificationListener.isEnabled(ctx)
                }) {
                    Text(stringResource(R.string.settings_notification_grant))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.settings_offset),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("${ui.offsetMs} ms", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = ui.offsetMs.toFloat(),
                    onValueChange = { vm.setOffset(it.toLong()) },
                    valueRange = -5_000f..5_000f,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.settings_prefer_synced))
                Switch(checked = ui.preferSynced, onCheckedChange = vm::setPreferSynced)
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_aa_info_title),
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_aa_info_body),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = vm::clearCache, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_clear_cache))
        }
    }
}
