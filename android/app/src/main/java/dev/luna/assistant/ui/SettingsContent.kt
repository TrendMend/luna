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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.luna.assistant.Protocol
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Tune
import dev.luna.assistant.wakeword.WakeWord
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
    userName: String,
    agentName: String,
    onUserName: (String) -> Unit,
    onAgentName: (String) -> Unit,
    callsOn: Boolean,
    onCallsToggled: (Boolean) -> Unit,
    onChangeServer: (String) -> String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAssistantSettings: (() -> Unit)? = null,
    version: String = "",
    wake: WakeWordSettings? = null,
    popup: PopupSettings? = null,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(28.dp))
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NamesCard(userName, agentName, onUserName, onAgentName)
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

            if (wake != null) WakeWordsCard(wake)
            if (popup != null) PopupCard(popup)

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

/** The popup's options: a spoken hello, and the end button (off: tap outside the popup to close it). */
data class PopupSettings(
    val greeting: Boolean,
    val onGreeting: (Boolean) -> Unit,
    val endButton: Boolean,
    val onEndButton: (Boolean) -> Unit,
)

@Composable
private fun PopupCard(popup: PopupSettings) {
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Popup", style = MaterialTheme.typography.titleMedium)
            PopupSwitch("Say hi when it opens", "A quick \"Hi!\" so you know Luna is listening.", popup.greeting, popup.onGreeting)
            PopupSwitch("Show the end button", "Off: tap anywhere outside the popup to close it.", popup.endButton, popup.onEndButton)
        }
    }
}

@Composable
private fun PopupSwitch(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(16.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** What the wake word card shows and does. [overlayAllowed] false shows the button that opens the system's "display over other apps" setting. */
data class WakeWordSettings(
    val on: Boolean,
    val onToggled: (Boolean) -> Unit,
    val words: List<WakeWord>,
    val enabled: Set<String>,
    val onWordToggled: (String, Boolean) -> Unit,
    val sensitivity: Float,                         // the main one, -1..1: every word follows it unless it has its own
    val onSensitivity: (Float) -> Unit,
    val onSensitivityFinished: () -> Unit,          // the slider was let go: apply it
    val own: Map<String, Float>,                    // words that have a sensitivity of their own
    val onWordSensitivity: (String, Float) -> Unit,
    val onWordSensitivityFinished: () -> Unit,
    val onWordReset: (String) -> Unit,              // back to following the main setting
    val overlayAllowed: Boolean,
    val onAllowOverlay: () -> Unit,
    val isAssistant: Boolean,
)

/** Listening for wake words: on or off, which words, how easily they wake it, and the permission that lets it open over other apps. */
@Composable
private fun WakeWordsCard(wake: WakeWordSettings) {
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Listen for wake words", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Say a name to start talking, hands free. The microphone stays on, listening on this phone only: nothing is sent until you say one. " +
                            "It shows a notification and uses some battery.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(16.dp))
                Switch(checked = wake.on, onCheckedChange = wake.onToggled)
            }
            if (wake.on) {
                wake.words.forEach { word -> WakeWordRow(word, wake) }
                Column {
                    Text("How easily they wake", style = MaterialTheme.typography.titleSmall)
                    Slider(value = wake.sensitivity, onValueChange = wake.onSensitivity, onValueChangeFinished = wake.onSensitivityFinished, valueRange = -1f..1f, steps = 3)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Fewer false wakes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Wakes more easily", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!wake.isAssistant && !wake.overlayAllowed) {
                    Text(
                        "To open over what you are doing, Luna needs permission to display over other apps.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = wake.onAllowOverlay, shape = CircleShape, modifier = Modifier.height(56.dp)) {
                        Text("Allow display over other apps", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * One wake word: its name, a configure button, and its switch. The button opens that word's own sensitivity right below the row; a word
 * follows the main slider until it is moved here, and "Use the main setting" puts it back.
 */
@Composable
private fun WakeWordRow(word: WakeWord, wake: WakeWordSettings) {
    var open by rememberSaveable(word.id) { mutableStateOf(false) }
    val custom = word.id in wake.own
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(word.label, style = MaterialTheme.typography.bodyLarge)
                if (custom) Text("Own setting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { open = !open }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Tune, contentDescription = "Adjust ${word.label}", modifier = Modifier.size(24.dp))
            }
            Switch(checked = word.id in wake.enabled, onCheckedChange = { wake.onWordToggled(word.id, it) })
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Slider(
                    value = wake.own[word.id] ?: wake.sensitivity,
                    onValueChange = { wake.onWordSensitivity(word.id, it) },
                    onValueChangeFinished = wake.onWordSensitivityFinished,
                    valueRange = -1f..1f,
                    steps = 3,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Fewer false wakes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Wakes more easily", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (word.note != null) {
                    Text(word.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                if (custom) {
                    TextButton(onClick = { wake.onWordReset(word.id) }) { Text("Use the main setting") }
                }
            }
        }
    }
}

/** What to call the person and what to call the assistant. Saved as they type; a name that is not safe to send shows what to fix. */
@Composable
private fun NamesCard(userName: String, agentName: String, onUserName: (String) -> Unit, onAgentName: (String) -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Names", style = MaterialTheme.typography.titleMedium)
            NameField("What should I call you?", userName, onUserName)
            NameField("What would you like to call me?", agentName, onAgentName, placeholder = "Luna")
        }
    }
}

@Composable
private fun NameField(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "") {
    val bad = value.isNotBlank() && Protocol.cleanName(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(40)) },
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
        singleLine = true,
        isError = bad,
        supportingText = if (bad) ({ Text("Letters only, up to 24 characters.") }) else null,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
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
