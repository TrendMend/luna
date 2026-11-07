package dev.luna.assistant

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.luna.assistant.screen.AssistantOverlay
import dev.luna.assistant.ui.theme.LunaTheme
import dev.luna.assistant.viewmodel.VoiceAssistantViewModel
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/*
 * Luna as the phone's default digital assistant. Once the person picks Luna under Settings, "Default digital assistant app", the
 * system starts this service and, when they press and hold the power or home button (or say the assistant gesture), asks it for a
 * session. The session has no window of its own (a session window cannot ask for the microphone permission or hold a LiveKit
 * connection across rotation); it opens AssistActivity (see below), a translucent activity drawn as a sheet over the current app.
 *
 * Android only accepts an assistant that declares ALL of these (the manifest and res/xml/interaction_service.xml are checked by
 * AssistantManifestTest): the service guarded by BIND_VOICE_INTERACTION, a session service, and a recognition service.
 */

/** The always-present service the system binds to. It needs no code of its own. */
class LunaInteractionService : VoiceInteractionService()

/** Creates one session each time the assistant is invoked. */
class LunaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = LunaSession(this)
}

class LunaSession(context: Context) : VoiceInteractionSession(context) {
    // No window of the session's own: the overlay is an activity (AssistActivity) so it can ask for permissions and hold the call.
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(Intent(context, AssistActivity::class.java))
    }
}

/**
 * Required for Android to accept the assistant, but Luna does not do dictation: it always answers "not available", so choosing
 * Luna as a keyboard's voice input fails politely instead of hanging.
 */
class LunaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback) = Unit
    override fun onStopListening(listener: Callback) = Unit
}

/**
 * Where the assist gesture lands (both from the session above and from the older ASSIST / VOICE_COMMAND intents): Luna's overlay.
 * Like other assistants it is not a full screen: the app the person was in stays visible, dimmed, and a sheet slides up from the
 * bottom with Luna's orb, what was said, widgets, and the controls. Tap outside it, press back, or End to leave. If the phone is not
 * set up yet there is nothing to show, so it opens the app's setup screen instead.
 */
class AssistActivity : ComponentActivity() {
    private val viewModel: VoiceAssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings(this).isConfigured) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
            return
        }
        setShowWhenLocked(true)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            LunaTheme {
                AssistantOverlay(viewModel = viewModel, onDismiss = { finish() })
            }
        }
    }
}
