package dev.luna.assistant

import dev.luna.assistant.ui.OrbState
import io.livekit.android.compose.state.AgentState

/** What the agent is doing, in the words and the orb the person sees. Plain language, no system terms. */
object Status {
    fun text(state: AgentState, micOn: Boolean, name: String = "Luna"): String = when (state) {
        AgentState.SPEAKING -> "$name is speaking"
        AgentState.THINKING -> "Thinking…"
        AgentState.LISTENING, AgentState.IDLE -> if (micOn) "Listening" else "Muted. You can type instead."
        AgentState.FAILED -> "Couldn't reach $name"
        AgentState.DISCONNECTED -> "Call ended"
        else -> "Connecting…"
    }

    fun orb(state: AgentState, micOn: Boolean): OrbState = when (state) {
        AgentState.SPEAKING -> OrbState.Speaking
        AgentState.THINKING -> OrbState.Thinking
        AgentState.LISTENING, AgentState.IDLE -> if (micOn) OrbState.Listening else OrbState.Idle
        AgentState.FAILED, AgentState.DISCONNECTED -> OrbState.Idle
        else -> OrbState.Connecting
    }

    /** The loudness that drives the orb: Luna's voice while she speaks, the person's microphone otherwise. 0 to 1. */
    fun level(amplitudes: FloatArray?): Float {
        if (amplitudes == null || amplitudes.isEmpty()) return 0f
        return (amplitudes.average().toFloat() * 2.5f).coerceIn(0f, 1f)
    }
}
