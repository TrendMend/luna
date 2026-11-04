package com.example.hawakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && (BuildConfig.LOCKED || Prefs(context).startOnBoot)) {
            // Note: Android 14+ may refuse to start a microphone foreground service from boot.
            // If so, just open the app once after reboot.
            runCatching { WakeWordService.start(context) }
        }
    }
}
