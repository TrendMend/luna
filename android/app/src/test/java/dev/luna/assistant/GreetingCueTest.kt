package dev.luna.assistant

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingCueTest {
    @Test
    fun hasSixDistinctClips() {
        assertEquals(6, GreetingCue.clips.size)
        assertEquals(6, GreetingCue.clips.toSet().size)
    }

    @Test
    fun neverRepeatsTheLastOne() {
        val random = Random(7)
        var last: Int? = null
        repeat(200) {
            val next = GreetingCue.pick(random, last)
            assertTrue(next in GreetingCue.clips)
            assertFalse(next == last)
            last = next
        }
    }

    @Test
    fun usesEveryClipOverTime() {
        val random = Random(1)
        assertEquals(GreetingCue.clips.toSet(), (1..200).map { GreetingCue.pick(random) }.toSet())
    }

    @Test
    fun tellsTheAgentWhenThePhoneAlreadySaidHi() {
        assertEquals("1", Protocol.conversationAttributes(null, null, greeted = true)["luna.greeted"])
        assertNull(Protocol.conversationAttributes(null, null)["luna.greeted"])
        assertNull(Protocol.conversationAttributes(null, null, greeted = false)["luna.greeted"])
    }
}
