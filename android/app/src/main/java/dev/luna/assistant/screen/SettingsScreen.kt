package dev.luna.assistant.screen

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.luna.assistant.Settings
import dev.luna.assistant.ui.SettingsContent
import dev.luna.assistant.ui.WakeWordSettings
import dev.luna.assistant.wakeword.WakeWords
import kotlinx.serialization.Serializable

@Serializable
object SettingsRoute

@Composable
fun SettingsScreen(
    onCallsToggled: (Boolean) -> Unit,
    onBack: () -> Unit,
    onWakeToggled: (Boolean) -> Unit = {},
    onWakeChanged: () -> Unit = {},
    overlayAllowed: Boolean = false,
    onAllowOverlay: () -> Unit = {},
    isAssistant: Boolean = false,
    /** Bumped when a setting was changed from outside this screen (a refused permission turned the wake switch off). */
    refresh: Int = 0,
) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var wakeOn by remember(refresh) { mutableStateOf(settings.listenForWakeWords) }
    var wakeWords by remember { mutableStateOf(settings.wakeWords) }
    var sensitivity by remember { mutableStateOf(settings.wakeSensitivity) }
    var own by remember { mutableStateOf(settings.wakeOverrides) }
    var server by remember { mutableStateOf(settings.server) }
    var callsOn by remember { mutableStateOf(settings.listenForCalls) }
    var userName by remember { mutableStateOf(settings.userName) }
    var agentName by remember { mutableStateOf(settings.agentName) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    SettingsContent(
        server = server,
        userName = userName,
        agentName = agentName,
        onUserName = { userName = it; settings.userName = it },
        onAgentName = { agentName = it; settings.agentName = it },
        callsOn = callsOn,
        onCallsToggled = { callsOn = it; onCallsToggled(it) },
        onChangeServer = { link ->
            if (settings.apply(link) && settings.isConfigured) {
                server = settings.server
                null
            } else {
                "That doesn't look like a setup link with a key."
            }
        },
        onBack = onBack,
        onAssistantSettings = {
            // The system's "Default digital assistant app" screen, where Luna can be chosen once it is installed as one
            context.startActivity(Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        version = version,
        wake = WakeWordSettings(
            on = wakeOn,
            onToggled = { wakeOn = it; onWakeToggled(it) },
            words = WakeWords.all,
            enabled = wakeWords,
            onWordToggled = { id, on ->
                wakeWords = if (on) wakeWords + id else wakeWords - id
                settings.wakeWords = wakeWords
                onWakeChanged()
            },
            sensitivity = sensitivity,
            onSensitivity = { sensitivity = it; settings.wakeSensitivity = it },
            onSensitivityFinished = onWakeChanged,
            own = own,
            onWordSensitivity = { id, value ->
                own = own + (id to value)
                settings.setWakeSensitivityOf(id, value)
            },
            onWordSensitivityFinished = onWakeChanged,
            onWordReset = { id ->
                own = own - id
                settings.setWakeSensitivityOf(id, null)
                onWakeChanged()
            },
            overlayAllowed = overlayAllowed,
            onAllowOverlay = onAllowOverlay,
            isAssistant = isAssistant,
        ),
    )
}
