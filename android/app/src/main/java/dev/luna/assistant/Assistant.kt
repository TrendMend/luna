package dev.luna.assistant

import android.graphics.Color
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.luna.assistant.screen.AssistantOverlay
import dev.luna.assistant.ui.theme.LunaTheme
import dev.luna.assistant.viewmodel.VoiceAssistantViewModel
import dev.luna.assistant.wakeword.WakeWords
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

/**
 * The always-present service the system binds to. It is also how a wake word opens Luna from the background: the system always allows the
 * selected assistant to show its sheet, where an ordinary app is barred from opening a screen while it is not on display.
 */
class LunaInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
        // The system starts the selected assistant on its own (also after a restart), so this brings the wake words back when the app is hidden.
        StandingServices.fromBackground(this)
    }

    override fun onShutdown() {
        if (instance === this) instance = null
        super.onShutdown()
    }

    companion object {
        const val ARG_WAKE_WORD = "wake_word"

        @Volatile
        private var instance: LunaInteractionService? = null

        /** Whether Luna is the phone's selected assistant right now. */
        val isSelected: Boolean get() = instance != null

        /** Asks the system to show Luna's sheet for [wakeWord]. False when Luna is not the phone's selected assistant (nothing happened). */
        fun show(wakeWord: String): Boolean {
            val service = instance ?: return false
            return try {
                service.showSession(Bundle().apply { putString(ARG_WAKE_WORD, wakeWord) }, VoiceInteractionSession.SHOW_WITH_ASSIST)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}

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
        startAssistantActivity(
            Intent(context, AssistActivity::class.java).putExtra(LunaInteractionService.ARG_WAKE_WORD, args?.getString(LunaInteractionService.ARG_WAKE_WORD)),
        )
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
        // A wake word can be heard while the screen is off (a kiosk, a phone on a table): the screen must light up, and stay lit for the
        // conversation, or Luna would be talking from a dark screen with nothing to see. The assist gesture needs neither: the screen is on.
        val fromWakeWord = WakeWords.cleanId(intent.getStringExtra(LunaInteractionService.ARG_WAKE_WORD)) != null
        showOverLockScreen(turnScreenOn = fromWakeWord)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Draw under the status bar and the camera cutout: otherwise the system paints them black and it reads as a whole screen
        // instead of something laid over the app.
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = when {
                Build.VERSION.SDK_INT >= 30 -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            LunaTheme {
                AssistantOverlay(
                    viewModel = viewModel,
                    wakeWord = WakeWords.cleanId(intent.getStringExtra(LunaInteractionService.ARG_WAKE_WORD)),
                    onDismiss = { finish() },
                )
            }
        }
    }
}
