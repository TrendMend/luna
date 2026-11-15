package dev.luna.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.content.ContextCompat
import dev.luna.assistant.wakeword.WakeWordService

/**
 * Starts the standing services (calls, wake words) the settings ask for, without the app being on screen. A hidden app (managed
 * configuration `hide_app`, a kiosk) is one nobody opens, so after a restart nothing else would ever start them.
 *
 * Android 14+ refuses to start a microphone foreground service from the background, so [fromBackground] prefers a one-moment
 * invisible activity ([StartupActivity]) when the "display over other apps" permission is granted (that permission lets an app open
 * an activity from the background, and an activity on screen is allowed to start the service). Without it, it tries directly,
 * which works on older Android versions and wherever the system makes an exception for the app.
 */
object StandingServices {
    private const val TAG = "LunaStartup"

    /** Starts what the settings ask for. Call it while an activity is on screen, or where the system allows it. Never throws. */
    fun start(context: Context) {
        val settings = Settings(context)
        if (!settings.isConfigured) return
        if (settings.listenForCalls) runCatching { ControlService.start(context) }.onFailure { Log.w(TAG, "calls service: $it") }
        if (settings.listenForWakeWords &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { WakeWordService.start(context) }.onFailure { Log.w(TAG, "wake word service: $it") }
        }
    }

    /** From a boot or update broadcast, or the assistant service coming up: nothing is on screen. */
    fun fromBackground(context: Context) {
        val settings = Settings(context)
        if (!settings.isConfigured || !(settings.listenForCalls || settings.listenForWakeWords)) return
        if (AndroidSettings.canDrawOverlays(context)) {
            val launched = runCatching {
                context.startActivity(Intent(context, StartupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (launched) return
        }
        start(context)
    }
}

/** Has no screen: starts the standing services while it is the activity on screen (so Android allows it), then goes away. */
class StartupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StandingServices.start(this)
        finish()
    }
}
