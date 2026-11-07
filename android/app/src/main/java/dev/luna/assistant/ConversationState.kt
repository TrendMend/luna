package dev.luna.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether a conversation is going on in this app right now (the Talk button, a call, the assistant gesture or a wake word). The wake word
 * service watches it: it gives the microphone to the conversation and takes it back, after a pause, when the conversation ends.
 * The activity and the service run in the same process, so a plain object is enough.
 */
object ConversationState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun set(active: Boolean) {
        _active.value = active
    }
}
