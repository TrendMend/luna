package dev.luna.assistant

/** One row of the conversation: a spoken or typed message, or a widget the agent put there. */
data class Timed<T>(val key: String, val time: Long, val item: T)

object Timeline {
    /**
     * Messages and widgets in one list, oldest first, so a widget sits in the conversation where it appeared and scrolls away with
     * it. Equal times keep the order given: pass messages before widgets and a widget never lands before the words that
     * introduce it.
     */
    fun <T> merge(vararg groups: List<Timed<T>>): List<Timed<T>> = groups.flatMap { it }.sortedBy { it.time }
}
