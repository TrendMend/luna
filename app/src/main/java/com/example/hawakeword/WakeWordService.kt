package com.example.hawakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the microphone open, runs the ONNX wake word model(s)
 * and forwards every detection to Home Assistant.
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: WakeWordEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine == null) startListening()
        return START_STICKY
    }

    private fun startListening() {
        val prefs = Prefs(this)
        startForegroundNotification("Listening…")

        val files = ModelCatalog.available(this).filter { prefs.isModelEnabled(it) }
        if (files.isEmpty()) {
            updateNotification("No enabled wake word model (add or import .onnx files)")
            stopSelf()
            return
        }
        if (!ModelCatalog.hasHelpers(this)) {
            updateNotification("Missing ${ModelCatalog.MEL} / ${ModelCatalog.EMBEDDING}")
            stopSelf()
            return
        }

        val specs = files.map {
            WakeWordEngine.ModelSpec(
                ModelCatalog.wakeWordName(it), it, prefs.thresholdFor(it), prefs.sendThresholdFor(it)
            )
        }

        // Keep HA's view of the app/OS version and device name current (e.g. after an update).
        scope.launch { HaClient.syncRegistration(this@WakeWordService, prefs) }

        val e = WakeWordEngine(
            context = this,
            models = specs,
            cooldownMs = prefs.cooldownMs,
            onDetection = { d ->
                if (!d.send) {
                    lastDetection = "${d.name} (${"%.2f".format(d.score)}) below send threshold, not sent"
                } else {
                    // Don't block the audio thread on the network.
                    scope.launch {
                        val err = HaClient.sendDetection(prefs, d.name, d.score, d.timestamp)
                        Log.d(TAG, "${d.name} score=${d.score} -> ${err ?: "sent"}")
                        lastDetection = "${d.name} (${"%.2f".format(d.score)}) " +
                            if (err == null) "sent" else "failed: $err"
                        updateNotification(
                            if (err == null) "Last: ${d.name} (${"%.2f".format(d.score)})"
                            else "Send failed: $err"
                        )
                    }
                }
            },
            onStats = { live = it },
            onError = { msg ->
                Log.e(TAG, "Engine stopped: $msg")
                lastError = msg
                updateNotification("Stopped: $msg")
                stopSelf()
            },
        )
        engine = e
        lastError = ""
        live = null
        running = true
        e.start()
    }

    override fun onDestroy() {
        running = false
        live = null
        engine?.stop()
        engine = null
        scope.cancel()
        super.onDestroy()
    }

    // --- notification plumbing -------------------------------------------------

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wake word listener", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val prefs = Prefs(this)
        // A custom text is always shown as-is; otherwise managed installs reveal nothing
        // (words, errors) on the notification shade and standard ones show live status.
        val shownText = prefs.notificationText.ifEmpty { if (BuildConfig.LOCKED) "Listening" else text }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(prefs.notificationTitle.ifEmpty { "HA Wake Word" })
            .setContentText(shownText)
            .setVisibility(
                if (BuildConfig.LOCKED) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .apply {
                // The managed build can only be stopped from inside the app, behind the password.
                if (!BuildConfig.LOCKED) addAction(0, "Stop", stop)
            }
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNotification(text: String) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wakeword"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.example.hawakeword.STOP"

        @Volatile
        var running = false
            private set

        /** Why the listener stopped on its own (model load failure, mic error...), shown on home. */
        @Volatile
        var lastError: String = ""
            private set

        /** Latest mic level and per-word scores, for the live view on the home screen. */
        @Volatile
        var live: WakeWordEngine.Stats? = null
            private set

        /** Last detection and delivery result, shown on the main screen for tuning. */
        @Volatile
        var lastDetection: String = ""
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
