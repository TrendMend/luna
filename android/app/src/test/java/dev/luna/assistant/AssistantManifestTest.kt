package dev.luna.assistant

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Android quietly ignores an app as a default assistant unless every declaration is present, and there is no emulator here to see
 * it fail. So the pieces are checked from the source manifest and the interaction XML: the service guarded by BIND_VOICE_INTERACTION
 * with its action and meta-data, the session and recognition services (both required), and the classes they name.
 */
class AssistantManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement

    private val manifest = parse("src/main/AndroidManifest.xml")
    private val config = parse("src/main/res/xml/interaction_service.xml")

    private fun services() = manifest.getElementsByTagName("service").let { n -> (0 until n.length).map { n.item(it) as Element } }
    private fun Element.attr(name: String) = getAttributeNS(android, name)
    private fun Element.actions() = getElementsByTagName("action").let { n -> (0 until n.length).map { (n.item(it) as Element).attr("name") } }

    @Test
    fun theInteractionServiceIsDeclaredTheWayAndroidRequires() {
        val service = services().single { it.actions().contains("android.service.voice.VoiceInteractionService") }
        assertEquals("android.permission.BIND_VOICE_INTERACTION", service.attr("permission"))
        val meta = service.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("android.voice_interaction", meta.attr("name"))
        assertEquals("@xml/interaction_service", meta.attr("resource"))
    }

    @Test
    fun sessionAndRecognitionServicesAreBothNamedAndDeclared() {
        assertEquals("voice-interaction-service", config.tagName)
        val session = config.attr("sessionService")
        val recognition = config.attr("recognitionService")
        assertTrue(session.isNotEmpty() && recognition.isNotEmpty())    // Android rejects the assistant if either is missing
        assertEquals("true", config.attr("supportsAssist"))
        val declared = services().associateBy { "dev.luna.assistant" + it.attr("name") }
        assertEquals("android.permission.BIND_VOICE_INTERACTION", declared.getValue(session).attr("permission"))
        assertNotNull(declared[recognition])
        // The classes the XML names exist
        listOf(session, recognition, config.attr("settingsActivity")).forEach { Class.forName(it, false, javaClass.classLoader) }
    }

    @Test
    fun theAssistGestureLandsOnAnActivityThatHandlesTheAssistIntent() {
        val activities = manifest.getElementsByTagName("activity").let { n -> (0 until n.length).map { n.item(it) as Element } }
        val assist = activities.single { it.attr("name") == ".AssistActivity" }
        assertTrue(assist.actions().containsAll(listOf("android.intent.action.ASSIST", "android.intent.action.VOICE_COMMAND")))
        assertEquals("true", assist.attr("exported"))
    }
}
