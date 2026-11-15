package dev.luna.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a restart or an app update, makes the launcher icon match the admin's policy and brings the calls and wake word services back
 * (see [StandingServices]: a hidden app or a kiosk is never opened, so nothing else would). The microphone service cannot be started
 * from a receiver on Android 14+, which is why that goes through an invisible activity when the overlay permission allows it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ManagedConfig.reload(context)
        LauncherIcon.apply(context)
        StandingServices.fromBackground(context)      // a hidden app is never opened, so this is the only chance to bring the services back
    }
}
