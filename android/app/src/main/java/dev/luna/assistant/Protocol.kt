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

    const val TOPIC_UI = "luna.ui"
    const val TOPIC_ACTION = "luna.ui.action"

    /** One A2UI message per non-blank line (the worker sends JSON Lines on `luna.ui`). */
    fun splitMessages(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

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
