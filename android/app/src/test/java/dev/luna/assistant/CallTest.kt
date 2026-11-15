package dev.luna.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CallTest {
    @Test
    fun parsesCallsOnlyOnTheCallTopic() {
        val body = "{\"say\":\"Your pasta timer is done.\"}".toByteArray()
        assertEquals(Protocol.Call("Your pasta timer is done."), Protocol.parseCall("luna.call", body))
        assertNull(Protocol.parseCall("luna.other", body))
        assertNull(Protocol.parseCall(null, body))
        assertNull(Protocol.parseCall("luna.call", "not json".toByteArray()))
    }

    @Test
    fun aCallWithoutWordsIsStillACall() {
        assertEquals(Protocol.Call(null), Protocol.parseCall("luna.call", "{}".toByteArray()))
        assertEquals(Protocol.Call(null), Protocol.parseCall("luna.call", "{\"say\":42}".toByteArray()))
    }

    @Test
    fun saysAreCleanedAndLimited() {
        assertEquals("hi there", Protocol.cleanSay("  hi\u0000 there\n "))
        assertNull(Protocol.cleanSay("   \u0007  "))
        assertNull(Protocol.cleanSay(null))
        assertEquals(Protocol.MAX_SAY, Protocol.cleanSay("x".repeat(1000))!!.length)
    }

    @Test
    fun conversationAttributesAnnounceWidgetsAndCarryOnlyValidCallFields() {
        val call = Protocol.conversationAttributes("call", "Reminder: pills")
        assertEquals(Protocol.BASIC_CATALOG, call["luna.ui"])
        assertEquals("call", call["luna.trigger"])
        assertEquals("Reminder: pills", call["luna.say"])

        val button = Protocol.conversationAttributes(null, null)
        assertEquals(setOf("luna.ui"), button.keys)

        val bad = Protocol.conversationAttributes("Bad Trigger!", "   ")
        assertFalse(bad.containsKey("luna.trigger"))
        assertFalse(bad.containsKey("luna.say"))
    }

    @Test
    fun controlTokenBodyAsksForTheQuietRoom() {
        assertEquals("control", org.json.JSONObject(Protocol.CONTROL_TOKEN_BODY).getString("purpose"))
    }
}
