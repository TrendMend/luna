package dev.luna.assistant.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.luna.assistant.R
import dev.luna.assistant.Settings
import kotlinx.serialization.Serializable

@Serializable
object ConnectRoute

@Composable
fun ConnectScreen(navigateToVoiceAssistant: () -> Unit, onCallsToggled: (Boolean) -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var configured by remember { mutableStateOf(settings.isConfigured) }
    var server by remember { mutableStateOf(settings.server) }
    var link by rememberSaveable { mutableStateOf("") }
    var callsOn by remember { mutableStateOf(settings.listenForCalls) }
    var editing by rememberSaveable { mutableStateOf(!settings.isConfigured) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Spacer(Modifier.size(48.dp))
        Image(painter = painterResource(R.drawable.connect_icon), contentDescription = null)
        Spacer(Modifier.size(16.dp))
        Text("Luna", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.size(8.dp))

        if (configured && !editing) {
            Text("Connected to $server", textAlign = TextAlign.Center)
            Spacer(Modifier.size(24.dp))
            Button(onClick = navigateToVoiceAssistant) { Text("Talk to Luna") }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { editing = true }) { Text("Change server") }
            Spacer(Modifier.size(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Ring for calls and reminders", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Keeps a quiet connection open so Luna can speak first: a timer, a reminder, or something at home.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = callsOn, onCheckedChange = { callsOn = it; onCallsToggled(it) })
            }
        } else {
            Text(
                "Paste the setup link for this phone (it looks like http://10.0.0.90:8080/#key=...).",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(16.dp))
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; error = null },
                label = { Text("Setup link") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(16.dp))
            Button(
                enabled = link.isNotBlank(),
                onClick = {
                    if (settings.apply(link) && settings.isConfigured) {
                        server = settings.server
                        configured = true
                        editing = false
                        link = ""
                    } else {
                        error = "That doesn't look like a setup link with a key."
                    }
                },
            ) { Text("Save") }
            if (configured) {
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { editing = false }) { Text("Cancel") }
            }
        }
    }
}
