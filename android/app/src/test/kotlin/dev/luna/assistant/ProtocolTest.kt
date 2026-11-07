package dev.luna.assistant

import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun splitsJsonLinesAndSkipsBlanks() {
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), Protocol.splitMessages("{\"a\":1}\n\n  {\"b\":2}  \r\n"))
        assertTrue(Protocol.splitMessages("  \n").isEmpty())
    }

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
    fun tokenBodyAdvertisesWidgetsAndFiltersAttributes() {
        val body = JSONObject(Protocol.tokenBody("call", "Reminder: pills"))
        assertFalse(body.has("purpose"))
        val attrs = body.getJSONObject("participant_attributes")
        assertEquals(Protocol.BASIC_CATALOG, attrs.getString("luna.ui"))
        assertEquals("call", attrs.getString("luna.trigger"))
        assertEquals("Reminder: pills", attrs.getString("luna.say"))

        val bad = JSONObject(Protocol.tokenBody("Bad Trigger!", null)).getJSONObject("participant_attributes")
        assertFalse(bad.has("luna.trigger"))
        assertFalse(bad.has("luna.say"))

        assertEquals("control", JSONObject(Protocol.tokenBody(null, null, "control")).getString("purpose"))
    }

    @Test
    fun actionMessageHasEveryRequiredField() {
        val json = JSONObject(Protocol.actionJson("confirm", "fan", "b", 1_800_000_000_000L, mapOf("x" to 1, "n" to "Ron")))
        assertEquals("v0.9.1", json.getString("version"))
        val a = json.getJSONObject("action")
        assertEquals("confirm", a.getString("name"))
        assertEquals("fan", a.getString("surfaceId"))
        assertEquals("b", a.getString("sourceComponentId"))
        assertEquals("2027-01-15T08:00:00Z", a.getString("timestamp"))
        assertEquals(1, a.getJSONObject("context").getInt("x"))
        assertEquals("Ron", a.getJSONObject("context").getString("n"))
    }

    @Test
    fun errorMessageIsShaped() {
        val e = JSONObject(Protocol.errorJson("VALIDATION_FAILED", "s", "m".repeat(1000))).getJSONObject("error")
        assertEquals("VALIDATION_FAILED", e.getString("code"))
        assertEquals("s", e.getString("surfaceId"))
        assertEquals(300, e.getString("message").length)
    }

    @Test
    fun parsesSetupLinks() {
        assertEquals(Protocol.Link("https://luna.example.com", "abc123"), Protocol.parseLink("https://luna.example.com/#key=abc123"))
        assertEquals(Protocol.Link("http://10.0.0.5:8080", null), Protocol.parseLink(" http://10.0.0.5:8080/ "))
        assertEquals(Protocol.Link("https://h", "k"), Protocol.parseLink("https://h/?x=1#a=b&key=k"))
        assertNull(Protocol.parseLink("javascript:alert(1)"))
        assertNull(Protocol.parseLink("not a link"))
        assertNotNull(Protocol.parseLink("https://h#key="))
        assertNull(Protocol.parseLink("https://h#key=")!!.key)
    }

    /** If a library upgrade changes its catalog id, createSurface from the worker would be rejected: catch it here. */
    @Test
    fun ourCatalogIdIsTheOneTheRendererRegisters() {
        assertEquals(A2uiBasicCatalogV1.CatalogId, Protocol.BASIC_CATALOG)
    }
}
