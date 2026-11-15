package dev.luna.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether a conversation is going on in this app right now (the Talk button, a call, the assistant gesture or a wake word), and the rule
 * that there is never more than one: a conversation must [begin] before it connects, and is refused while another holds it. The screens
 * that host a conversation (the app's own and the assistant sheet) are separate activities with separate connections, so nothing else
 * stops a call, a wake word and the Talk button from each opening one.
 *
 * The wake word service watches [active]: it gives the microphone to the conversation and takes it back, after a pause, when the
 * conversation ends. The activities and the service run in the same process, so a plain object is enough.
 */
object ConversationState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    private var owner: Any? = null

    /** Claims the one conversation. False when another holds it: the caller must not start a second one. Repeating it for the holder is fine. */
    @Synchronized
    fun begin(who: Any): Boolean {
        if (owner != null && owner !== who) return false
        owner = who
        _active.value = true
        return true
    }

    /** Releases it. Ignored unless [who] holds it, so a conversation that was refused cannot mark the one still going on as over. */
    @Synchronized
    fun end(who: Any) {
        if (owner !== who) return
        owner = null
        _active.value = false
    }
}
