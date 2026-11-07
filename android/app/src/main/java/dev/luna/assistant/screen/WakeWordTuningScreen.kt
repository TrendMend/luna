package dev.luna.assistant.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.luna.assistant.Settings
import dev.luna.assistant.ui.TunedWord
import dev.luna.assistant.ui.WakeWordTuningContent
import dev.luna.assistant.wakeword.WakeWords
import kotlinx.serialization.Serializable

@Serializable
object WakeWordTuningRoute

/** The per-word page behind "Fine-tune each word" in Settings. [onChanged] is called when a setting is final (a switch, or a slider let go). */
@Composable
fun WakeWordTuningScreen(onChanged: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var main by remember { mutableStateOf(settings.wakeSensitivity) }
    var own by remember { mutableStateOf(settings.wakeOverrides) }
    val enabled = remember { settings.wakeWords }

    WakeWordTuningContent(
        words = WakeWords.all.map { w ->
            TunedWord(w.id, w.label, w.note, on = w.id in enabled, custom = w.id in own, sensitivity = own[w.id] ?: main)
        },
        onCustom = { id, custom ->
            if (custom) {
                own = own + (id to main)                      // starts where the main setting is: nothing changes until it is moved
                settings.setWakeSensitivityOf(id, main)
            } else {
                own = own - id
                settings.setWakeSensitivityOf(id, null)
            }
            onChanged()
        },
        onSensitivity = { id, value ->
            own = own + (id to value)
            settings.setWakeSensitivityOf(id, value)
        },
        onSensitivityFinished = onChanged,
        onBack = onBack,
    )
}
