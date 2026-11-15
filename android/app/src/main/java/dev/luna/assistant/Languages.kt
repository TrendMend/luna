package dev.luna.assistant

/**
 * The languages Luna can be asked to speak. The ids are the ones in the worker's `languages:` list (worker/config.yaml); the worker only
 * accepts an id it has, so a language added here must be added there too (worker/tests/test_languages.py checks that). The person (Settings)
 * or an admin (managed configuration) chooses one, and may say Luna should speak only that language.
 */
object Languages {
    data class Language(val id: String, val label: String)

    val all: List<Language> = listOf(
        Language("en", "English"), Language("es", "Spanish"), Language("fr", "French"), Language("de", "German"),
        Language("it", "Italian"), Language("pt", "Portuguese"), Language("hi", "Hindi"), Language("ja", "Japanese"),
        Language("ko", "Korean"), Language("zh", "Chinese (Mandarin)"),
    )

    /** Only an id from [all]; anything else (including "" and an MDM's "user") is no language. */
    fun clean(id: String?): String? = id?.trim()?.lowercase()?.takeIf { v -> all.any { it.id == v } }

    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: "Automatic"

    /** The recorded "Hi!" clips are English, so the cue is only played when the person has not chosen another language. */
    fun greetingCueFits(id: String): Boolean = id.isEmpty() || id == "en"
}
