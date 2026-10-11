package com.example.hawakeword

/**
 * Defaults for the wake words that ship with the app. Thresholds come from each model's
 * evaluation (`optimal_threshold`, the point with the fewest false alarms per hour), and only
 * these words are listened for until the user switches others on.
 */
object BuiltInDefaults {
    /** Wake word names (see ModelCatalog.wakeWordName) that are on by default. */
    val ENABLED = setOf("ron", "rulon")

    private val THRESHOLDS = mapOf(
        "ron" to 0.85f,
        "rulon" to 0.83f,
    )

    fun threshold(wakeWord: String): Float? = THRESHOLDS[wakeWord]
}
