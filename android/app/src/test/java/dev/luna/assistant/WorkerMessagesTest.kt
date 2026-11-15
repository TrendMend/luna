package dev.luna.assistant

import androidx.a2ui.model.processor.A2uiJsonMessageParser
import androidx.a2ui.model.processor.A2uiJsonReader
import androidx.a2ui.model.processor.A2uiJsonToken
import androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage
import androidx.a2ui.model.protocol.A2uiDeleteSurfaceMessage
import androidx.a2ui.model.protocol.A2uiException
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage
import androidx.a2ui.model.protocol.A2uiUpdateDataModelMessage
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The messages the worker really sends (worker-widgets.jsonl is written by luna.ui.Widgets, see docs/protocol.md) go through the
 * real A2UI parser from the official library. The library's own tokenizer is android.util.JsonReader, which is only a stub on a
 * JVM, so Gson's reader (same API and rules) stands in for it; everything above the tokenizer is the library's code.
 */
class WorkerMessagesTest {
    private class GsonReader(raw: String) : A2uiJsonReader {
        private val d = JsonReader(StringReader(raw))
        override fun peek(): A2uiJsonToken = when (d.peek()) {
            JsonToken.BEGIN_OBJECT -> A2uiJsonToken.BEGIN_OBJECT
            JsonToken.BEGIN_ARRAY -> A2uiJsonToken.BEGIN_ARRAY
            JsonToken.END_OBJECT -> A2uiJsonToken.END_OBJECT
            JsonToken.END_ARRAY -> A2uiJsonToken.END_ARRAY
            JsonToken.NAME -> A2uiJsonToken.NAME
            JsonToken.STRING -> A2uiJsonToken.STRING
            JsonToken.NUMBER -> A2uiJsonToken.NUMBER
            JsonToken.BOOLEAN -> A2uiJsonToken.BOOLEAN
            JsonToken.NULL -> A2uiJsonToken.NULL
            JsonToken.END_DOCUMENT -> A2uiJsonToken.END_DOCUMENT
        }
        override fun beginObject() = d.beginObject()
        override fun endObject() = d.endObject()
        override fun beginArray() = d.beginArray()
        override fun endArray() = d.endArray()
        override fun hasNext() = d.hasNext()
        override fun nextName(): String = d.nextName()
        override fun nextString(): String = d.nextString()
        override fun nextBoolean() = d.nextBoolean()
        override fun nextDouble() = d.nextDouble()
        override fun nextInt() = d.nextInt()
        override fun nextLong() = d.nextLong()
        override fun nextNull() = d.nextNull()
        override fun skipValue() = d.skipValue()
        override fun close() = d.close()
    }

    private val parser = A2uiJsonMessageParser(::GsonReader)

    private val lines: List<String> = Protocol.splitMessages(
        checkNotNull(javaClass.getResourceAsStream("/worker-widgets.jsonl")) { "fixture missing" }.bufferedReader().readText(),
    )

    @Test
    fun everyMessageTheWorkerSendsParses() {
        assertTrue(lines.size >= 6)
        val messages = lines.map { parser.parse(it) }
        assertEquals(
            listOf("fan_choice", "fan_choice", "todo", "todo", "todo", "ref_clinic", "ref_clinic", "fan_choice", "weather", "weather", "todo", "choices", "choices", "ref_clinic", "doors", "doors"),
            messages.map { it.surfaceId },
        )
        val kinds = messages.map { it::class.simpleName }
        assertEquals(
            listOf(
                A2uiCreateSurfaceMessage::class, A2uiUpdateComponentsMessage::class,
                A2uiCreateSurfaceMessage::class, A2uiUpdateComponentsMessage::class, A2uiUpdateDataModelMessage::class,
                A2uiCreateSurfaceMessage::class, A2uiUpdateComponentsMessage::class,
                A2uiDeleteSurfaceMessage::class,
                A2uiCreateSurfaceMessage::class, A2uiUpdateComponentsMessage::class,
                A2uiDeleteSurfaceMessage::class,
                A2uiCreateSurfaceMessage::class, A2uiUpdateComponentsMessage::class,
                A2uiDeleteSurfaceMessage::class,
                A2uiCreateSurfaceMessage::class, A2uiUpdateComponentsMessage::class,
            ).map { it.simpleName },
            kinds,
        )
        assertEquals(Protocol.BASIC_CATALOG, (messages[0] as A2uiCreateSurfaceMessage).catalogId)
        assertEquals(8, (messages[1] as A2uiUpdateComponentsMessage).components.size)
    }

    @Test
    fun theTabbedWeatherCardParsesWithItsTabsAndSidewaysLists() {
        val weather = lines.filter { it.contains("\"surfaceId\":\"weather\"") && it.contains("updateComponents") }
        assertEquals(1, weather.size)
        val text = weather.single()
        // the shapes the worker builds (Tabs with title/child, List with direction), as the official parser reads them
        assertTrue("Tabs" in text && "\"direction\":\"horizontal\"" in text)
        for (title in listOf("Temperature", "Precipitation", "Wind", "Week")) assertTrue("tab $title", "\"title\":\"$title\"" in text)
        val message = parser.parse(text) as A2uiUpdateComponentsMessage
        assertTrue("only ${message.components.size} components", message.components.size > 60)
    }

    @Test
    fun theParserRejectsWhatItShould() {
        for (bad in listOf("""{"version":"v9","createSurface":{"surfaceId":"a","catalogId":"x"}}""", "not json", """{"version":"v0.9.1"}""")) {
            try {
                parser.parse(bad)
                fail("accepted: $bad")
            } catch (e: A2uiException) {
                assertNotNull(e.message)
            }
        }
    }
}
