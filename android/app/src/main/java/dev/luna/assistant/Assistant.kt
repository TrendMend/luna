package dev.luna.assistant

import android.app.Activity
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
 * session. The session does nothing but open Luna's conversation, through AssistActivity (see below), so everything that happens
 * after is the ordinary conversation screen.
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
    // No window of the session's own: the conversation is a normal screen of the app.
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
 * Where the assist gesture lands (both from the session above and from the older ASSIST / VOICE_COMMAND intents). It has no screen:
 * it hands over to [MainActivity] in the app's own task and goes away. Doing it this way keeps the conversation screen out of the
 * assistant's special task, where launch rules are stricter.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_ASSIST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
