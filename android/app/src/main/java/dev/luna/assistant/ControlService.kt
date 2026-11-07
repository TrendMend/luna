package dev.luna.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The standing control-room connection (docs/protocol.md, "Calls from Home Assistant" and "Reminders"). While it runs, Home
 * Assistant or a reminder can make this phone speak: a `luna.call` data message becomes a full-screen notification that opens
 * the app and starts a conversation with the words to open with.
 *
 * It holds a notification for as long as it runs (Android requires one) and reconnects with a growing pause when the
 * connection drops. It does not start at boot; the person turns it on in the app.
 */
class ControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val tokens = TokenClient()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val notification = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Luna is waiting for calls")
            .setContentText("Reminders and calls from the house will ring here.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp(null))
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_STATUS, notification)
        }
        if (job == null) job = scope.launch { listen() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun listen() {
        var pause = 2_000L
        while (scope.isActive) {
            try {
                val settings = Settings(this)
                if (settings.isConfigured) {
                    val token = tokens.fetch(settings.server, settings.key, Protocol.CONTROL_TOKEN_BODY)
                    val room = LiveKit.create(applicationContext)
                    val gone = CompletableDeferred<Unit>()
                    val events = scope.launch {
                        room.events.collect { event ->
                            when (event) {
                                is RoomEvent.DataReceived -> Protocol.parseCall(event.topic, event.data)?.let { ring(it) }
                                is RoomEvent.Disconnected -> gone.complete(Unit)
                                else -> Unit
                            }
                        }
                    }
                    try {
                        room.connect(token.serverUrl, token.token)
                        pause = 2_000L
                        gone.await()
                    } finally {
                        events.cancel()
                        room.disconnect()
                        room.release()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline, server restarting, key revoked: keep trying, slowly.
            }
            delay(pause)
            pause = minOf(pause * 2, 60_000L)
        }
    }

    private fun openApp(say: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = if (say == null) Intent.ACTION_MAIN else ACTION_CALL
            putExtra(EXTRA_SAY, say)
        }
        return PendingIntent.getActivity(this, if (say == null) 0 else 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ring(call: Protocol.Call) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pending = openApp(call.say ?: "")
        val notification = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Luna")
            .setContentText(call.say ?: "Luna would like to talk")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_CALL, notification)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Waiting for calls", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CALLS, "Calls and reminders", NotificationManager.IMPORTANCE_HIGH))
    }

    companion object {
        const val ACTION_CALL = "dev.luna.assistant.CALL"
        const val EXTRA_SAY = "say"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_CALLS = "calls"
        private const val NOTIFICATION_STATUS = 1
        private const val NOTIFICATION_CALL = 2

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, ControlService::class.java))
        fun stop(context: Context) {
            context.stopService(Intent(context, ControlService::class.java))
        }
    }
}
