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

    fun listeningForWakeWords(name: String) = "$name is listening for wake words"
    const val LISTENING_DETAIL = "Say a wake word to start talking. Nothing is sent until you do."

    fun wakeTitle(name: String) = name
    const val WAKE_DETAIL = "Tap to talk"
}
