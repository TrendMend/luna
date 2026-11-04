package com.example.hawakeword

/**
 * Defaults for the wake words that ship with the app: which are on, and their thresholds.
 * Only [ENABLED] words are listened for until the user switches others on; any of them can be
 * switched off, and a per-word override always wins. (Evaluation suggested ~0.85 / ~0.83 for the
 * fewest false alarms; 0.5 trades some of that for catching more.)
 */
object BuiltInDefaults {
    /** Wake word names (see ModelCatalog.wakeWordName) that are on by default. */
    val ENABLED = setOf("ron", "rulon")

    private val THRESHOLDS = mapOf(
        "ron" to 0.5f,
        "rulon" to 0.5f,
    )

    fun threshold(wakeWord: String): Float? = THRESHOLDS[wakeWord]
}
