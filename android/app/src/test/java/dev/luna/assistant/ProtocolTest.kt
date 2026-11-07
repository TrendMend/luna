package dev.luna.assistant

import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun splitsJsonLinesAndSkipsBlanks() {
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), Protocol.splitMessages("{\"a\":1}\n\n  {\"b\":2}  \r\n"))
        assertTrue(Protocol.splitMessages("  \n").isEmpty())
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
    fun ourCatalogIdIsTheOneTheRendererRegisters() {
        assertEquals(A2uiBasicCatalogV1.CatalogId, Protocol.BASIC_CATALOG)
    }
}
