package dev.luna.assistant.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.luna.assistant.Protocol
import dev.luna.assistant.Settings
import dev.luna.assistant.Widgets
import io.livekit.android.LiveKit
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.token.TokenSource
import kotlinx.coroutines.launch

/** Holds the Room (and this conversation's widgets) across configuration changes such as rotation. */
class VoiceAssistantViewModel(application: Application) : AndroidViewModel(application) {

    val room = LiveKit.create(application)

    /** Luna's token service speaks LiveKit's standard endpoint format; the device's access key goes in Authorization. */
    val tokenSource = Settings(application).let {
        TokenSource.fromEndpoint("${it.server}/api/token", headers = mapOf("Authorization" to "Bearer ${it.key}"))
    }

    /** Widgets the agent puts on the screen (A2UI over the `luna.ui` text stream); taps go back on `luna.ui.action`. */
    val widgets = Widgets(application, viewModelScope) { json -> sendAction(json) }

    init {
        room.registerTextStreamHandler(Protocol.TOPIC_UI) { reader, identity ->
            if (identity == room.localParticipant.identity) return@registerTextStreamHandler
            viewModelScope.launch { widgets.receive(reader.readAll().joinToString("")) }
        }
    }

    private fun sendAction(json: String) {
        viewModelScope.launch {
            try {
                room.localParticipant.sendText(json, StreamTextOptions(topic = Protocol.TOPIC_ACTION))
            } catch (e: Exception) {
                // The conversation may have just ended; a tap on a closing widget is not worth an error.
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        widgets.close()
        room.disconnect()
        room.release()
    }
}
