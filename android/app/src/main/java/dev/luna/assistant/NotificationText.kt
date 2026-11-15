package dev.luna.assistant

/**
 * The words in the app's notifications. [name] is what the person calls the assistant ([Settings.assistantName]: their own choice, or
 * Luna), so a person who renamed it never sees "Luna" in a notification. Pure, so it is unit-tested on a JVM.
 */
object NotificationText {
    fun waitingForCalls(name: String) = "$name is waiting for calls"
    const val WAITING_DETAIL = "Reminders and calls from the house will ring here."

    /** The title of a call or reminder: just the assistant's name. */
    fun callTitle(name: String) = name
    fun callFallback(name: String) = "$name would like to talk"

    /**
     * The notification Android requires while the microphone service runs: only the assistant's name. It does not say what the service does
     * (no mention of wake words, no explanation), and it has no body text.
     */
    fun listeningTitle(name: String) = name

    fun wakeTitle(name: String) = name
    const val WAKE_DETAIL = "Tap to talk"
}
