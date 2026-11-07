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

    /** Whether the phone listens for wake words (microphone always on, a notification, some battery). Off until the person turns it on. */
    var listenForWakeWords: Boolean
        get() = prefs.getBoolean("listen_for_wake_words", false)
        set(v) = prefs.edit().putBoolean("listen_for_wake_words", v).apply()

    /** Ids of the wake words that are on (see [dev.luna.assistant.wakeword.WakeWords]); all of them until the person turns some off. */
    var wakeWords: Set<String>
        get() = prefs.getStringSet("wake_words", null)?.toSet() ?: dev.luna.assistant.wakeword.WakeWords.defaultEnabled
        set(v) = prefs.edit().putStringSet("wake_words", v).apply()

    /** -1 (fewer false wakes) to +1 (easier to wake); 0 is the NUC's tuning. */
    var wakeSensitivity: Float
        get() = prefs.getFloat("wake_sensitivity", 0f)
        set(v) = prefs.edit().putFloat("wake_sensitivity", v.coerceIn(-1f, 1f)).apply()

    /** What the person wants to be called, and what they want to call the assistant. Raw as typed; use [Protocol.cleanName] before using. */
    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(v) = prefs.edit().putString("user_name", v.trim()).apply()

    var agentName: String
        get() = prefs.getString("agent_name", "") ?: ""
        set(v) = prefs.edit().putString("agent_name", v.trim()).apply()

    /** The assistant's name as the app shows it: the chosen one, or Luna. */
    val assistantName: String get() = Protocol.cleanName(agentName) ?: "Luna"

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
