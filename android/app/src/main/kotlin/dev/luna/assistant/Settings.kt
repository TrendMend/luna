package dev.luna.assistant

import android.content.Context
import androidx.core.content.edit

/**
 * Where Luna lives and which key this phone uses (from `luna-web add-device`). Kept in this app's private storage and never
 * logged. A key is an access credential for the home assistant, so the app excludes it from backups (see the manifest).
 */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("luna", Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = prefs.edit { putString("server", v.trim().trimEnd('/')) }

    var key: String
        get() = prefs.getString("key", "") ?: ""
        set(v) = prefs.edit { putString("key", v.trim()) }

    /** Keep a standing connection so Home Assistant and reminders can make this phone speak. */
    var listenForCalls: Boolean
        get() = prefs.getBoolean("listen_for_calls", false)
        set(v) = prefs.edit { putBoolean("listen_for_calls", v) }

    val configured: Boolean get() = server.isNotEmpty() && key.isNotEmpty()
}
