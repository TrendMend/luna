package dev.luna.assistant.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.luna.assistant.Settings
import io.livekit.android.LiveKit
import io.livekit.android.token.TokenSource

/** Holds the Room across configuration changes such as rotation. */
class VoiceAssistantViewModel(application: Application) : AndroidViewModel(application) {

    val room = LiveKit.create(application)

    /** Luna's token service speaks LiveKit's standard endpoint format; the device's access key goes in Authorization. */
    val tokenSource = Settings(application).let {
        TokenSource.fromEndpoint("${it.server}/api/token", headers = mapOf("Authorization" to "Bearer ${it.key}"))
    }

    override fun onCleared() {
        super.onCleared()
        room.disconnect()
        room.release()
    }
}
