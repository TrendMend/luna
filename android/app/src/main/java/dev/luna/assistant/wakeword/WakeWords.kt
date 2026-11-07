package dev.luna.assistant.wakeword

/** One wake word the phone can listen for. [id] is what the agent is told (`luna.wake_word`) and what `wake_words:` in worker/config.yaml is keyed by. */
data class WakeWord(val id: String, val label: String, val threshold: Float)

/**
 * The wake words, with the same ids and thresholds as the NUC's listener (listener/config.yaml and this house's luna.nix):
 * 0.5 for the built-in openWakeWord words, 0.7 for `ron` and `rulon` (short, common sounds that woke it by accident at 0.5).
 * The model for each is `assets/wakeword/<id>.onnx`.
 */
object WakeWords {
    val all = listOf(
        WakeWord("ron", "Ron", 0.7f),
        WakeWord("rulon", "Rulon", 0.7f),
        WakeWord("alexa", "Alexa", 0.5f),
        WakeWord("hey_jarvis", "Hey Jarvis", 0.5f),
        WakeWord("hey_mycroft", "Hey Mycroft", 0.5f),
        WakeWord("hey_rhasspy", "Hey Rhasspy", 0.5f),
    )
    val defaultEnabled: Set<String> = all.map { it.id }.toSet()

    private val ID = Regex("[a-z0-9_]{1,40}")

    /** A wake word id that is safe to send to the agent (it checks again), or null. */
    fun cleanId(id: String?): String? = id?.takeIf { ID.matches(it) }

    /** The thresholds of [enabled] words, moved by [sensitivity] (-1 fewer false wakes ... +1 easier to wake), kept inside 0.2..0.95. */
    fun thresholds(enabled: Set<String>, sensitivity: Float = 0f): Map<String, Float> =
        all.filter { it.id in enabled }.associate { it.id to (it.threshold - 0.15f * sensitivity.coerceIn(-1f, 1f)).coerceIn(0.2f, 0.95f) }
}

/**
 * Decides when a wake word has been said, from the scores of consecutive 80 ms chunks. The listener's rules: a word counts only after
 * [patience] chunks in a row at or over its threshold (a single loud click does not), and nothing counts for [cooldownMs] after one
 * did (so the end of a conversation is not heard as a new call). One difference: when two words pass in the same chunk (`ron` and
 * `rulon` overlap) the highest score wins, where the listener took whichever it looked at first.
 */
class WakeWordTrigger(
    private val thresholds: Map<String, Float>,
    private val patience: Int = 2,
    private val cooldownMs: Long = 3_000,
) {
    private val streaks = HashMap<String, Int>()
    private var quietUntil = 0L

    /** The id of the wake word heard in this chunk, or null. [nowMs] is any steadily increasing clock. */
    fun onScores(scores: Map<String, Float>, nowMs: Long): String? {
        if (nowMs < quietUntil) {
            streaks.clear()
            return null
        }
        var heard: String? = null
        var best = 0f
        for ((id, threshold) in thresholds) {
            val score = scores[id] ?: 0f
            if (score >= threshold) {
                val streak = (streaks[id] ?: 0) + 1
                streaks[id] = streak
                if (streak >= patience && score > best) {
                    heard = id
                    best = score
                }
            } else {
                streaks[id] = 0
            }
        }
        if (heard != null) {
            streaks.clear()
            quietUntil = nowMs + cooldownMs
        }
        return heard
    }

    /** Stay silent for [cooldownMs] from [nowMs] (a conversation has just ended). */
    fun mute(nowMs: Long) {
        streaks.clear()
        quietUntil = nowMs + cooldownMs
    }
}
