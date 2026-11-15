package dev.luna.assistant

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStateTest {
    private val first = Any()
    private val second = Any()

    @After
    fun release() {
        ConversationState.end(first)
        ConversationState.end(second)
    }

    @Test
    fun onlyOneConversationCanHoldIt() {
        assertTrue(ConversationState.begin(first))
        assertFalse(ConversationState.begin(second))        // a call, a wake word or the Talk button while one is going on
        assertTrue(ConversationState.active.value)
    }

    @Test
    fun aRefusedConversationCannotEndTheOneStillGoingOn() {
        assertTrue(ConversationState.begin(first))
        assertFalse(ConversationState.begin(second))
        ConversationState.end(second)
        assertTrue(ConversationState.active.value)          // the wake word service must not start listening mid-conversation
    }

    @Test
    fun itCanBeClaimedAgainOnceTheHolderLetsGo() {
        assertTrue(ConversationState.begin(first))
        ConversationState.end(first)
        assertFalse(ConversationState.active.value)
        assertTrue(ConversationState.begin(second))
    }

    @Test
    fun theHolderRepeatingItIsNotRefused() {
        assertTrue(ConversationState.begin(first))
        assertTrue(ConversationState.begin(first))
    }
}
