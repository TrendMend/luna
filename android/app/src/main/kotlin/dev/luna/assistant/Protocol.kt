package dev.luna.assistant

import org.json.JSONObject

/**
 * Luna's wire protocol (docs/protocol.md) as plain functions, with no Android or LiveKit types so they can be unit-tested
 * on a JVM. Everything a server or another device sends is treated as data and checked here.
 */
object Protocol {
    /** The message version we send. The A2UI parser on Android accepts v0.9 and v0.9.1. */
    const val VERSION = "v0.9.1"

    /** The catalog id the official Android renderer registers (not the v0_9_1 one: see docs/protocol.md). */
    const val BASIC_CATALOG = "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"

    const val ATTR_UI = "luna.ui"
    const val ATTR_TRIGGER = "luna.trigger"
    const val ATTR_SAY = "luna.say"

    const val TOPIC_UI = "luna.ui"
    const val TOPIC_ACTION = "luna.ui.action"
    const val TOPIC_CALL = "luna.call"
    const val TOPIC_TRANSCRIPTION = "lk.transcription"
    const val AGENT_STATE_ATTR = "lk.agent.state"

    const val MAX_SAY = 300

    /** One A2UI message per non-blank line (the worker sends JSON Lines on `luna.ui`). */
    fun splitMessages(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Printable characters only, trimmed and length-limited; null if nothing is left. Mirrors the worker's clean_say. */
    fun cleanSay(text: String?): String? {
        if (text == null) return null
        val cleaned = text.filter { !it.isISOControl() }.take(MAX_SAY).trim()
        return cleaned.ifEmpty { null }
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

    /** Body of `POST /api/token`. `purpose` is "control" for the standing control-room token, null for a conversation. */
    fun tokenBody(trigger: String?, say: String?, purpose: String? = null): String {
        val body = JSONObject()
        if (purpose != null) body.put("purpose", purpose)
        val attrs = JSONObject()
        attrs.put(ATTR_UI, BASIC_CATALOG)
        if (trigger != null && Regex("[a-z0-9_]{1,40}").matches(trigger)) attrs.put(ATTR_TRIGGER, trigger)
        cleanSay(say)?.let { attrs.put(ATTR_SAY, it) }
        body.put("participant_attributes", attrs)
        return body.toString()
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

    /** A link from `luna-web add-device` ("https://host/#key=...") or a bare server address. */
    data class Link(val server: String, val key: String?)

    fun parseLink(text: String): Link? {
        val t = text.trim()
        if (!(t.startsWith("https://") || t.startsWith("http://"))) return null
        val hash = t.indexOf('#')
        val base = (if (hash >= 0) t.substring(0, hash) else t).substringBefore('?').trimEnd('/')
        if (base.length <= "https://".length - 1) return null
        val fragment = if (hash >= 0) t.substring(hash + 1) else ""
        val key = fragment.split('&').firstOrNull { it.startsWith("key=") }?.removePrefix("key=")?.trim()?.ifEmpty { null }
        return Link(base, key)
    }
}
