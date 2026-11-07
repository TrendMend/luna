package dev.luna.assistant

import android.content.Context

/** Where Luna's token service is and which key this phone uses. Kept in private app storage. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("luna", Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = prefs.edit().putString("server", v).apply()

    var key: String
        get() = prefs.getString("key", "") ?: ""
        set(v) = prefs.edit().putString("key", v).apply()

    /** Whether the standing connection runs, so reminders and calls from the house can ring this phone. */
    var listenForCalls: Boolean
        get() = prefs.getBoolean("listen_for_calls", false)
        set(v) = prefs.edit().putBoolean("listen_for_calls", v).apply()

    val isConfigured: Boolean get() = server.isNotEmpty() && key.isNotEmpty()

    /** Takes the link from `luna-web add-device` (https://host/#key=...) or a bare server address. */
    fun apply(text: String): Boolean {
        val link = Link.parse(text) ?: return false
        server = link.server
        link.key?.let { key = it }
        return true
    }
}

/** A setup link: server address plus optional key. Pure, so it is unit-tested on a JVM. */
data class Link(val server: String, val key: String?) {
    companion object {
        fun parse(text: String): Link? {
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
}
