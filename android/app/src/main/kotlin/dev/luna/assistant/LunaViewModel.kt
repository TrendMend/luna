package dev.luna.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamTextOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One conversation with Luna at a time: get a token, join the room, publish the microphone, play the agent, show its transcript
 * and its widgets (docs/protocol.md). The agent leaving the room ends it.
 */
class LunaViewModel(app: Application) : AndroidViewModel(app) {
    data class Line(val who: String, val text: String)

    val settings = Settings(app)
    private val tokens = TokenClient()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    private val _transcript = MutableStateFlow<List<Line>>(emptyList())
    val transcript: StateFlow<List<Line>> = _transcript
    private val _widgets = MutableStateFlow<Widgets?>(null)
    val widgets: StateFlow<Widgets?> = _widgets

    private var room: Room? = null
    private var events: Job? = null

    /** A conversation started by a call (Home Assistant or a reminder) passes the words to open with. */
    fun start(trigger: String = "button", say: String? = null) {
        if (_active.value) return
        if (!settings.configured) {
            _status.value = "Set the server address and access key first."
            return
        }
        _active.value = true
        _status.value = "Connecting…"
        _transcript.value = emptyList()
        viewModelScope.launch {
            try {
                val token = tokens.fetch(settings.server, settings.key, Protocol.tokenBody(trigger, say))
                val r = LiveKit.create(getApplication())
                room = r
                _widgets.value = Widgets(getApplication(), viewModelScope) { json -> sendAction(r, json) }
                r.registerTextStreamHandler(Protocol.TOPIC_UI) { reader, identity ->
                    if (identity == r.localParticipant.identity) return@registerTextStreamHandler
                    viewModelScope.launch { _widgets.value?.receive(reader.readAll().joinToString("")) }
                }
                r.registerTextStreamHandler(Protocol.TOPIC_TRANSCRIPTION) { reader, identity ->
                    viewModelScope.launch {
                        val text = reader.readAll().joinToString("").trim()
                        if (text.isNotEmpty()) {
                            val who = if (identity == r.localParticipant.identity) "You" else "Luna"
                            _transcript.value = _transcript.value + Line(who, text)
                        }
                    }
                }
                events = viewModelScope.launch {
                    r.events.collect { event ->
                        when (event) {
                            is RoomEvent.ParticipantAttributesChanged ->
                                event.changedAttributes[Protocol.AGENT_STATE_ATTR]?.let { _status.value = describe(it) }
                            is RoomEvent.Disconnected -> end()
                            else -> Unit
                        }
                    }
                }
                r.connect(token.serverUrl, token.token)
                r.localParticipant.setMicrophoneEnabled(true)
                _status.value = "Connected. Say hello."
            } catch (e: Exception) {
                _status.value = (e as? LunaException)?.message ?: "Could not start: ${e.message}"
                end()
            }
        }
    }

    fun report(message: String) {
        _status.value = message
    }

    fun stop() {
        end()
        _status.value = ""
    }

    private fun describe(state: String) = when (state) {
        "listening" -> "Listening…"
        "thinking" -> "Thinking…"
        "speaking" -> "Luna is speaking…"
        "initializing" -> "Waking up…"
        else -> state
    }

    private fun sendAction(r: Room, json: String) {
        viewModelScope.launch {
            try {
                r.localParticipant.sendText(json, StreamTextOptions(topic = Protocol.TOPIC_ACTION))
            } catch (e: Exception) {
                // The conversation may have just ended; a tap on a closing widget is not worth an error.
            }
        }
    }

    private fun end() {
        events?.cancel()
        events = null
        _widgets.value?.close()
        _widgets.value = null
        room?.let {
            it.disconnect()
            it.release()
        }
        room = null
        _active.value = false
    }

    override fun onCleared() {
        end()
    }
}
