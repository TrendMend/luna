package dev.luna.assistant

import org.json.JSONObject

/**
 * The parts of Luna's wire protocol (docs/protocol.md) that are plain functions, with no Android or LiveKit types so they can be
 * unit-tested on a JVM. Everything the server sends is treated as data and checked here.
 */
object Protocol {
    /** The message version we send. The A2UI parser on Android accepts v0.9 and v0.9.1. */
    const val VERSION = "v0.9.1"

    /** The catalog id the official Android renderer registers (not the v0_9_1 one: see docs/protocol.md). */
    const val BASIC_CATALOG = "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"

    /** Participant attribute announcing that this device can draw widgets. */
    const val ATTR_UI = "luna.ui"
    const val ATTR_TRIGGER = "luna.trigger"
    const val ATTR_SAY = "luna.say"
    const val ATTR_WAKE_WORD = "luna.wake_word"
    const val ATTR_USER_NAME = "luna.user_name"
    const val ATTR_AGENT_NAME = "luna.agent_name"
    const val ATTR_GREETED = "luna.greeted"

    const val TOPIC_UI = "luna.ui"
    const val TOPIC_ACTION = "luna.ui.action"
    const val TOPIC_CALL = "luna.call"

    const val MAX_SAY = 300

    /** Body of the token request for the standing control room (a quiet room that only receives `luna.call`). */
    const val CONTROL_TOKEN_BODY = """{"purpose":"control"}"""

    /** One A2UI message per non-blank line (the worker sends JSON Lines on `luna.ui`). */
    fun splitMessages(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Printable characters only, trimmed and length-limited; null if nothing is left. Mirrors the worker's clean_say. */
    fun cleanSay(text: String?): String? {
        if (text == null) return null
        val cleaned = text.filter { !it.isISOControl() }.take(MAX_SAY).trim()
        return cleaned.ifEmpty { null }
    }

    private val NAME = Regex("\\p{L}[\\p{L} '\u2019.-]{0,23}")

    /**
     * A name the person typed, if it is safe to send: letters, spaces, apostrophes, dots and hyphens, up to 24 characters and three
     * words. Mirrors the worker's clean_name, which checks again; anything else is not sent at all.
     */
    fun cleanName(text: String?): String? {
        val t = text?.trim()?.split(Regex("\\s+"))?.joinToString(" ") ?: return null
        return t.takeIf { NAME.matches(it) && it.split(" ").size <= 3 }
    }

    /** A `luna.call` data message from the control room: Home Assistant or a reminder asking this device to speak. */
    data class Call(val say: String?)

    fun parseCall(topic: String?, data: ByteArray): Call? {
        if (topic != TOPIC_CALL) return null
        val say = try {
            val obj = JSONObject(String(data, Charsets.UTF_8))
            if (obj.has("say") && !obj.isNull("say")) obj.get("say") as? String else null
        } catch (e: Exception) {
            return null
        }
        return Call(cleanSay(say))
    }

    /** The participant attributes of a conversation: widgets supported, and (for a call) how it started and what to say first. */
    fun conversationAttributes(
        trigger: String?,
        say: String?,
        userName: String? = null,
        agentName: String? = null,
        wakeWord: String? = null,
        /** The app already played its own hello (see GreetingCue), so the agent should not greet as well. */
        greeted: Boolean = false,
    ): Map<String, String> {
        val attributes = mutableMapOf(ATTR_UI to BASIC_CATALOG)
        // Which wake word was heard: the worker picks the greeting for it from `wake_words:` (the same ids as the NUC's listener).
        if (wakeWord != null && Regex("[a-z0-9_]{1,40}").matches(wakeWord)) attributes[ATTR_WAKE_WORD] = wakeWord
        if (greeted) attributes[ATTR_GREETED] = "1"
        cleanName(userName)?.let { attributes[ATTR_USER_NAME] = it }
        cleanName(agentName)?.let { attributes[ATTR_AGENT_NAME] = it }
        if (trigger != null && Regex("[a-z0-9_]{1,40}").matches(trigger)) attributes[ATTR_TRIGGER] = trigger
        cleanSay(say)?.let { attributes[ATTR_SAY] = it }
        return attributes
    }

    private fun isoUtc(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    /** What goes back on `luna.ui.action` when the person taps something. */
    fun actionJson(name: String, surfaceId: String, componentId: String, timestampMillis: Long, context: Map<String, Any?>): String {
        val action = JSONObject()
        action.put("name", name)
        action.put("surfaceId", surfaceId)
        action.put("sourceComponentId", componentId)
        action.put("timestamp", isoUtc(timestampMillis))
        action.put("context", JSONObject(context))
        return JSONObject().put("version", VERSION).put("action", action).toString()
    }

    /** What goes back when the device cannot render a message. */
    fun errorJson(code: String, surfaceId: String, message: String): String {
        val error = JSONObject()
        error.put("code", code)
        error.put("surfaceId", surfaceId)
        error.put("message", message.take(300))
        return JSONObject().put("version", VERSION).put("error", error).toString()
    }
}
