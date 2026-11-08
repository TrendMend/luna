package dev.luna.assistant

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import dev.luna.assistant.wakeword.WakeWords

/**
 * Settings an IT admin has set for this device through Android Enterprise "managed configurations" (the schema is res/xml/app_restrictions.xml;
 * the MDM shows it as a form and delivers the answers as an app-restrictions bundle). A value that is present here wins over what the person set
 * in the app; a value that is absent leaves the person's own setting alone.
 *
 * MDMs are not consistent about types (a boolean can arrive as the text "true", a number as text, a list as an array or a comma-separated
 * string), so every reader accepts those and ignores anything it cannot make sense of instead of failing. Pure Kotlin on a map, so it is tested
 * on a JVM; [fromBundle] and [current] are the only parts that touch Android.
 */
class ManagedConfig(private val raw: Map<String, Any?>) {
    private val link: Link? = text(KEY_SETUP_LINK)?.let { Link.parse(it) }

    /** Explicit server and key win over the ones inside a setup link. */
    val server: String? = text(KEY_SERVER_URL)?.let { Link.parse(it)?.server } ?: link?.server
    val key: String? = text(KEY_API_KEY) ?: link?.key
    val userName: String? = text(KEY_USER_NAME)
    val agentName: String? = text(KEY_ASSISTANT_NAME)
    val listenForCalls: Boolean? = bool(KEY_LISTEN_FOR_CALLS)
    val listenForWakeWords: Boolean? = bool(KEY_LISTEN_FOR_WAKE_WORDS)
    val greetingCue: Boolean? = bool(KEY_GREETING_CUE)
    val popupEndButton: Boolean? = bool(KEY_POPUP_END_BUTTON)

    /** An id from [Languages] (anything else, such as the form's "user", means the person decides). */
    val language: String? = Languages.clean(text(KEY_LANGUAGE))
    val languageOnly: Boolean? = bool(KEY_LANGUAGE_ONLY)

    /** The admin hides the Settings screen from the person. */
    val hideSettings: Boolean? = bool(KEY_HIDE_SETTINGS)

    /** The admin hides the app's launcher icon: Luna is then only reached through the assistant gesture (and wake words, calls). */
    val hideApp: Boolean? = bool(KEY_HIDE_APP)

    /** Only words this build knows; an empty list is a real answer (no word at all). */
    val wakeWords: Set<String>? = list(KEY_WAKE_WORDS)?.let { ids -> ids.filter { id -> WakeWords.all.any { it.id == id } }.toSet() }

    /** -100..100 percent in the form, -1..+1 inside the app. */
    val wakeSensitivity: Float? = int(KEY_WAKE_SENSITIVITY)?.coerceIn(-100, 100)?.let { it / 100f }

    /** Which of the app's settings are being set by the admin (shown to the person, so they know why a change does not stick). */
    val managed: Set<String> = buildSet {
        if (server != null) add(KEY_SERVER_URL)
        if (key != null) add(KEY_API_KEY)
        if (userName != null) add(KEY_USER_NAME)
        if (agentName != null) add(KEY_ASSISTANT_NAME)
        if (listenForCalls != null) add(KEY_LISTEN_FOR_CALLS)
        if (listenForWakeWords != null) add(KEY_LISTEN_FOR_WAKE_WORDS)
        if (wakeWords != null) add(KEY_WAKE_WORDS)
        if (wakeSensitivity != null) add(KEY_WAKE_SENSITIVITY)
        if (greetingCue != null) add(KEY_GREETING_CUE)
        if (popupEndButton != null) add(KEY_POPUP_END_BUTTON)
        if (language != null) add(KEY_LANGUAGE)
        if (languageOnly != null) add(KEY_LANGUAGE_ONLY)
        if (hideSettings != null) add(KEY_HIDE_SETTINGS)
        if (hideApp != null) add(KEY_HIDE_APP)
    }

    val isEmpty: Boolean get() = managed.isEmpty()

    private fun text(name: String): String? = (raw[name] as? String)?.trim()?.ifEmpty { null }

    private fun bool(name: String): Boolean? = when (val v = raw[name]) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) { "true" -> true; "false" -> false; else -> null }
        else -> null
    }

    private fun int(name: String): Int? = when (val v = raw[name]) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun list(name: String): List<String>? = when (val v = raw[name]) {
        is Array<*> -> v.filterIsInstance<String>().map { it.trim() }
        is Collection<*> -> v.filterIsInstance<String>().map { it.trim() }
        is String -> if (v.isBlank()) null else v.split(',').map { it.trim() }.filter { it.isNotEmpty() }   // "" is the schema's default: not set
        else -> null
    }

    companion object {
        // The restriction keys: these exact names are what an admin's policy contains, so they must never change (see res/xml/app_restrictions.xml).
        const val KEY_SETUP_LINK = "setup_link"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_USER_NAME = "user_name"
        const val KEY_ASSISTANT_NAME = "assistant_name"
        const val KEY_LISTEN_FOR_CALLS = "listen_for_calls"
        const val KEY_LISTEN_FOR_WAKE_WORDS = "listen_for_wake_words"
        const val KEY_WAKE_WORDS = "wake_words"
        const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
        const val KEY_GREETING_CUE = "greeting_cue"
        const val KEY_POPUP_END_BUTTON = "popup_end_button"
        const val KEY_LANGUAGE = "language"
        const val KEY_LANGUAGE_ONLY = "language_only"
        const val KEY_HIDE_SETTINGS = "hide_settings"
        const val KEY_HIDE_APP = "hide_app"

        val NONE = ManagedConfig(emptyMap())

        fun fromBundle(bundle: Bundle?): ManagedConfig {
            if (bundle == null || bundle.isEmpty) return NONE
            // (the bundle is read through get(), the one call that returns whatever type the MDM put in)
            @Suppress("DEPRECATION")
            return ManagedConfig(bundle.keySet().associateWith { bundle.get(it) })
        }

        @Volatile private var cached: ManagedConfig? = null

        /** What the admin has set, read once and kept until [reload] (the system tells the app when the policy changes). */
        fun current(context: Context): ManagedConfig = cached ?: reload(context)

        fun reload(context: Context): ManagedConfig {
            val restrictions = runCatching {
                (context.applicationContext.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager).applicationRestrictions
            }.getOrNull()
            return fromBundle(restrictions).also { cached = it }
        }
    }
}
