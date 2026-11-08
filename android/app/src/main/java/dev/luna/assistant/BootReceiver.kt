package dev.luna.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a restart or an app update, makes the launcher icon match the admin's policy. Without it a policy that arrives while the app is closed
 * would only hide the icon the next time somebody opened the app, and a hidden app is one nobody opens. It does nothing else: the
 * microphone service cannot be started from here (Android 14+), and the app does not start itself at boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ManagedConfig.reload(context)
        LauncherIcon.apply(context)
    }
}
