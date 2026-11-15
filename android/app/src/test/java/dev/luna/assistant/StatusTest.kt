package dev.luna.assistant

import dev.luna.assistant.ui.OrbState
import io.livekit.android.compose.state.AgentState
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTest {
    @Test
    fun wordsArePlainAndFollowTheAgent() {
        assertEquals("Luna is speaking", Status.text(AgentState.SPEAKING, true))
        assertEquals("Thinking…", Status.text(AgentState.THINKING, true))
        assertEquals("Listening", Status.text(AgentState.LISTENING, true))
        assertEquals("Connecting…", Status.text(AgentState.CONNECTING, true))
        assertEquals("Connecting…", Status.text(AgentState.INITIALIZING, true))
        assertEquals("Couldn't reach Luna", Status.text(AgentState.FAILED, true))
    }

    @Test
    fun mutedMeansTheOrbRestsAndTheTextSaysTypeInstead() {
        assertEquals(OrbState.Idle, Status.orb(AgentState.LISTENING, micOn = false))
        assertEquals("Muted. You can type instead.", Status.text(AgentState.LISTENING, micOn = false))
        assertEquals(OrbState.Speaking, Status.orb(AgentState.SPEAKING, micOn = false))   // she can still talk
    }

    @Test
    fun levelIsScaledAndClamped() {
        assertEquals(0f, Status.level(null), 0f)
        assertEquals(0f, Status.level(floatArrayOf()), 0f)
        assertEquals(0.25f, Status.level(floatArrayOf(0.1f, 0.1f)), 0.001f)
        assertEquals(1f, Status.level(floatArrayOf(0.9f, 0.9f)), 0f)
    }

    @Test
    fun usesTheAssistantsChosenName() {
        assertEquals("Nova is speaking", Status.text(AgentState.SPEAKING, true, "Nova"))
        assertEquals("Couldn't reach Nova", Status.text(AgentState.FAILED, true, "Nova"))
        assertEquals("Luna is speaking", Status.text(AgentState.SPEAKING, true))
    }
}
