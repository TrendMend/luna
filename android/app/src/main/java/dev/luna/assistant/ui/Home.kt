package dev.luna.assistant.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The first screen once Luna is set up: a greeting, Luna, and one big way to start talking. */
@Composable
fun HomeContent(
    greeting: String,
    onTalk: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    animateOrb: Boolean = true,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Luna", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            IconButton(onClick = onSettings, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(28.dp))
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(greeting, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
            Text("How can I help?", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            LunaOrb(OrbState.Listening, 0.1f, Modifier.size(220.dp), animate = animateOrb)
        }
        Button(onClick = onTalk, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(72.dp)) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(12.dp))
            Text("Talk to Luna", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** First run: paste the link that connects this phone to the Luna at home. */
@Composable
fun SetupContent(
    link: String,
    onLinkChange: (String) -> Unit,
    error: String?,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    animateOrb: Boolean = true,
) {
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        LunaOrb(OrbState.Connecting, 0f, Modifier.size(180.dp), animate = animateOrb)
        Spacer(Modifier.height(24.dp))
        Text("Let's connect to Luna", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Paste the setup link for this phone. Whoever set up Luna at home has it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        LinkField(link, onLinkChange, error, onConnect)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onConnect, enabled = link.isNotBlank(), shape = CircleShape, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("Connect", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A pill-shaped field for the setup link. Also used when changing the server in Settings. */
@Composable
fun LinkField(link: String, onLinkChange: (String) -> Unit, error: String?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = link,
        onValueChange = onLinkChange,
        placeholder = { Text("http://10.0.0.90:8080/#key=…", style = MaterialTheme.typography.bodyLarge) },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        shape = MaterialTheme.shapes.extraLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            errorContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/** The greeting for the time of day. Pure, so it is tested. */
object Greeting {
    fun forHour(hour: Int): String = when {
        hour < 5 -> "Hello"
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}
