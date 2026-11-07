package dev.luna.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Settings, as a screen of its own. [onChangeServer] takes a pasted setup link and returns a short problem to show (null when it
 * was accepted). [onAssistantSettings] is null when this phone has no such setting.
 */
@Composable
fun SettingsContent(
    server: String,
    callsOn: Boolean,
    onCallsToggled: (Boolean) -> Unit,
    onChangeServer: (String) -> String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAssistantSettings: (() -> Unit)? = null,
    version: String = "",
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(28.dp))
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerCard(server, onChangeServer)

            Card {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ring for calls and reminders", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Keeps a quiet connection open so Luna can speak first: a timer, a reminder, or something at home.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    Switch(checked = callsOn, onCheckedChange = onCallsToggled)
                }
            }

            if (onAssistantSettings != null) {
                Card(Modifier.clickable(onClick = onAssistantSettings)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("Make Luna your phone's assistant", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Then pressing and holding the power or home button starts Luna.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (version.isNotEmpty()) {
                Text(
                    "Luna $version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) { content() }
}

@Composable
private fun ServerCard(server: String, onChangeServer: (String) -> String?) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("The Luna at home", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(server, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (!editing) {
                OutlinedButton(onClick = { editing = true }, shape = CircleShape, modifier = Modifier.height(56.dp)) {
                    Text("Use a different setup link", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                fun save() {
                    error = onChangeServer(link)
                    if (error == null) {
                        editing = false
                        link = ""
                    }
                }
                LinkField(link, { link = it; error = null }, error, ::save)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::save, enabled = link.isNotBlank(), shape = CircleShape, modifier = Modifier.height(56.dp)) { Text("Save") }
                    OutlinedButton(onClick = { editing = false; link = ""; error = null }, shape = CircleShape, modifier = Modifier.height(56.dp)) { Text("Cancel") }
                }
            }
        }
    }
}
