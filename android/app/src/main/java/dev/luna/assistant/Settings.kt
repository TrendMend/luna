package dev.luna.assistant

import android.content.Context

/** Where Luna's token service is and which key this phone uses. Kept in private app storage. */
class Settings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("luna", Context.MODE_PRIVATE)

    /**
     * What an IT admin has set through Android Enterprise managed configurations (see [ManagedConfig]). A managed value wins over the
     * person's own; the person's own stays stored underneath, so it comes back if the admin removes the policy.
     */
    val managed: ManagedConfig get() = ManagedConfig.current(appContext)

    /** The admin keeps the Settings screen from the person: no button for it, and the screen itself is not reachable. */
    val settingsHidden: Boolean get() = managed.hideSettings ?: false

    /**
     * The admin hides the app's icon, so it is used only through the assistant. Never while the phone is not set up: with no icon and
     * no way to connect, the person (and the admin) would have nothing to start from.
     */
    val appHidden: Boolean get() = (managed.hideApp ?: false) && isConfigured

    /** Whether any setting is being set by the admin: the Settings screen says so. */
    val isManaged: Boolean get() = !managed.isEmpty

    var server: String
        get() = managed.server ?: prefs.getString("server", "") ?: ""
        set(v) = prefs.edit().putString("server", v).apply()

    var key: String
        get() = managed.key ?: prefs.getString("key", "") ?: ""
        set(v) = prefs.edit().putString("key", v).apply()

    /** Whether the standing connection runs, so reminders and calls from the house can ring this phone. */
    var listenForCalls: Boolean
        get() = managed.listenForCalls ?: prefs.getBoolean("listen_for_calls", false)
        set(v) = prefs.edit().putBoolean("listen_for_calls", v).apply()

    /** Whether the phone listens for wake words (microphone always on, a notification, some battery). Off until the person turns it on. */
    var listenForWakeWords: Boolean
        get() = managed.listenForWakeWords ?: prefs.getBoolean("listen_for_wake_words", false)
        set(v) = prefs.edit().putBoolean("listen_for_wake_words", v).apply()

    /** Ids of the wake words that are on (see [dev.luna.assistant.wakeword.WakeWords]); all of them until the person turns some off. */
    var wakeWords: Set<String>
        get() = managed.wakeWords ?: prefs.getStringSet("wake_words", null)?.toSet() ?: dev.luna.assistant.wakeword.WakeWords.defaultEnabled
        set(v) = prefs.edit().putStringSet("wake_words", v).apply()

    /** -1 (fewer false wakes) to +1 (easier to wake); 0 is the listener's tuning. */
    var wakeSensitivity: Float
        get() = managed.wakeSensitivity ?: prefs.getFloat("wake_sensitivity", 0f)
        set(v) = prefs.edit().putFloat("wake_sensitivity", v.coerceIn(-1f, 1f)).apply()

    /**
     * A sensitivity of one word's own (same -1..+1 scale as [wakeSensitivity]), for a person who tunes words one by one. A word without one
     * follows [wakeSensitivity]; null removes it.
     */
    fun wakeSensitivityOf(id: String): Float? = if (prefs.contains("wake_sensitivity_$id")) prefs.getFloat("wake_sensitivity_$id", 0f) else null

    fun setWakeSensitivityOf(id: String, value: Float?) {
        prefs.edit().apply { if (value == null) remove("wake_sensitivity_$id") else putFloat("wake_sensitivity_$id", value.coerceIn(-1f, 1f)) }.apply()
    }

    /** Every word that has a sensitivity of its own. */
    val wakeOverrides: Map<String, Float>
        get() = dev.luna.assistant.wakeword.WakeWords.all.mapNotNull { w -> wakeSensitivityOf(w.id)?.let { w.id to it } }.toMap()

    /** A quick recorded "Hi!" when Luna opens by itself being asked (a button or press and hold; calls and wake words speak their own). */
    var greetingCue: Boolean
        get() = managed.greetingCue ?: prefs.getBoolean("greeting_cue", true)
        set(v) = prefs.edit().putBoolean("greeting_cue", v).apply()

    /** Without the end button the popup closes by tapping outside it (the conversation ends with it). */
    var popupEndButton: Boolean
        get() = managed.popupEndButton ?: prefs.getBoolean("popup_end_button", true)
        set(v) = prefs.edit().putBoolean("popup_end_button", v).apply()

    /** The language Luna speaks (an id from [Languages]); empty follows whatever the person speaks. */
    var language: String
        get() = managed.language ?: Languages.clean(prefs.getString("language", "")) ?: ""
        set(v) = prefs.edit().putString("language", Languages.clean(v) ?: "").apply()

    /** Speak only [language], whatever the person uses. Has no effect without a language. */
    var languageOnly: Boolean
        get() = managed.languageOnly ?: prefs.getBoolean("language_only", false)
        set(v) = prefs.edit().putBoolean("language_only", v).apply()

    /** What the person wants to be called, and what they want to call the assistant. Raw as typed; use [Protocol.cleanName] before using. */
    var userName: String
        get() = managed.userName ?: prefs.getString("user_name", "") ?: ""
        set(v) = prefs.edit().putString("user_name", v.trim()).apply()

    var agentName: String
        get() = managed.agentName ?: prefs.getString("agent_name", "") ?: ""
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
