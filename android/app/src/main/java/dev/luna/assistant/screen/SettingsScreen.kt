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
import kotlinx.serialization.Serializable

@Serializable
object SettingsRoute

@Composable
fun SettingsScreen(onCallsToggled: (Boolean) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var server by remember { mutableStateOf(settings.server) }
    var callsOn by remember { mutableStateOf(settings.listenForCalls) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    SettingsContent(
        server = server,
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
    )
}
