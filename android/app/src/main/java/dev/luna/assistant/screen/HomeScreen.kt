package dev.luna.assistant.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.luna.assistant.Settings
import dev.luna.assistant.Protocol
import dev.luna.assistant.ui.Greeting
import dev.luna.assistant.ui.HomeContent
import dev.luna.assistant.ui.SetupContent
import kotlinx.serialization.Serializable
import java.util.Calendar

@Serializable
object ConnectRoute

/** The first screen: connect this phone to Luna the first time, afterwards a greeting and a button to start talking. */
@Composable
fun HomeScreen(navigateToVoiceAssistant: () -> Unit, navigateToSettings: () -> Unit, settingsHidden: Boolean = false) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var configured by remember { mutableStateOf(settings.isConfigured) }
    var link by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    if (!configured) {
        SetupContent(
            link = link,
            onLinkChange = { link = it; error = null },
            error = error,
            onConnect = {
                if (settings.apply(link) && settings.isConfigured) {
                    configured = true
                    link = ""
                } else {
                    error = "That doesn't look like a setup link with a key."
                }
            },
        )
    } else {
        HomeContent(
            greeting = Greeting.forHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), Protocol.cleanName(settings.userName)),
            assistantName = settings.assistantName,
            onTalk = navigateToVoiceAssistant,
            onSettings = navigateToSettings,
            showSettings = !settingsHidden,
        )
    }
}
