package dev.luna.assistant.wakeword

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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.luna.assistant.ConversationState
import dev.luna.assistant.LunaInteractionService
import dev.luna.assistant.MainActivity
import dev.luna.assistant.NotificationText
import dev.luna.assistant.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * Listens for the wake words (ron, rulon, alexa, ...) with the microphone, on the phone: nothing leaves the phone until one is heard.
 * Then it hands the microphone to a conversation and starts one (see [startConversation]); when that ends it listens again.
 *
 * A foreground service of type `microphone`: Android requires the notification, and from Android 14 such a service can only be
 * started while an activity is on screen and with the microphone permission granted. After a restart it is brought back by
 * [dev.luna.assistant.StandingServices] (from the boot receiver and the assistant service, through an invisible activity when the
 * overlay permission is granted), so a hidden app does not need to be opened. Like [dev.luna.assistant.ControlService] it can still be stopped by battery limits.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Status", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WAKE, "Wake word heard", NotificationManager.IMPORTANCE_HIGH))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()       // a microphone service cannot start without the permission (Android 14 throws)
            return START_NOT_STICKY
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(NotificationText.listeningTitle(Settings(this).assistantName))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)      // nothing on the lock screen
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_STATUS, notification)
            }
        } catch (e: Exception) {
            // Android 14+ refuses a microphone service started while nothing is on screen: say so instead of crashing the app.
            Log.w(TAG, "the system did not allow the wake word service to start: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        if (job == null) job = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        var pause = 2_000L
        while (scope.isActive) {
            try {
                val settings = Settings(this)
                val enabled = settings.wakeWords.intersect(WakeWords.all.map { it.id }.toSet())
                if (enabled.isEmpty()) return
                loadDetector(enabled).use { detector ->
                    val thresholds = WakeWords.thresholds(enabled, settings.wakeSensitivity, settings.wakeOverrides)
                    val trigger = WakeWordTrigger(thresholds)
                    Log.i(TAG, "listening for " + thresholds.entries.joinToString(" ") { "${it.key}>${"%.2f".format(it.value)}" })
                    pause = 2_000L
                    while (scope.isActive) {
                        ConversationState.active.first { !it }              // the microphone belongs to a conversation while one runs
                        val heard = withContext(Dispatchers.IO) { listen(detector, trigger) } ?: continue
                        Log.i(TAG, "wake word $heard")
                        startConversation(heard)
                        withTimeoutOrNull(START_TIMEOUT_MS) { ConversationState.active.first { it } }    // did it open?
                        ConversationState.active.first { !it }
                        trigger.mute(SystemClock.elapsedRealtime())
                        detector.reset()                                     // do not hear the end of Luna's own voice
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "wake word listening stopped: $e")               // microphone busy (a phone call), model missing: try again
            }
            delay(pause)
            pause = minOf(pause * 2, 60_000L)
        }
    }

    private fun loadDetector(enabled: Set<String>): OpenWakeWord {
        fun asset(name: String) = assets.open("wakeword/$name.onnx").use { it.readBytes() }
        return OpenWakeWord(asset("melspectrogram"), asset("embedding_model"), enabled.associateWith { asset(it) })
    }

    /** Records and scores until a wake word is heard (returns its id) or a conversation starts by other means (returns null). */
    private suspend fun listen(detector: OpenWakeWord, trigger: WakeWordTrigger): String? {
        val bufferBytes = max(AudioRecord.getMinBufferSize(OpenWakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), OpenWakeWord.CHUNK * 2 * 4)
        check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "the microphone permission was withdrawn"
        }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, OpenWakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "the microphone could not be opened" }
        try {
            record.startRecording()
            val chunk = ShortArray(OpenWakeWord.CHUNK)
            while (!ConversationState.active.value) {
                currentCoroutineContext().ensureActive()
                var filled = 0
                while (filled < chunk.size) {
                    val n = record.read(chunk, filled, chunk.size - filled)
                    check(n >= 0) { "microphone read failed ($n)" }
                    filled += n
                }
                val scores = detector.process(chunk)
                if (!detector.ready) continue
                // Near misses are the only way to tune a word, so anything that starts to sound like one is logged (debug level, only above 0.2).
                if (scores.values.any { it >= LOG_SCORE }) {
                    Log.d(TAG, "scores " + scores.entries.filter { it.value >= 0.1f }.joinToString(" ") { "${it.key}=${"%.2f".format(it.value)}" })
                }
                trigger.onScores(scores, SystemClock.elapsedRealtime())?.let { return it }
            }
            return null
        } finally {
            runCatching { record.stop() }
            record.release()                                                 // free the microphone for the conversation
        }
    }

    /**
     * Opens the conversation. In order of preference:
     *  1. Luna is the phone's selected assistant: ask the system to show its assistant sheet. Android always allows this, but it can
     *     still decline to draw it (a kiosk's lock task, for one), so if no conversation opens within [SHEET_TIMEOUT_MS] the next way is
     *     tried: Luna is never left answering with nothing on screen.
     *  2. The "display over other apps" permission is granted: start the app directly (newer Android versions may still refuse this
     *     from the background, which is why it is second).
     *  3. A full-screen notification: opens over the lock screen, but when the phone is in use it only shows a banner to tap.
     */
    private suspend fun startConversation(wakeWord: String) {
        if (LunaInteractionService.show(wakeWord)) {
            if (withTimeoutOrNull(SHEET_TIMEOUT_MS) { ConversationState.active.first { it } } != null) return
            Log.w(TAG, "the assistant sheet did not open; trying another way")
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_WAKE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_WAKE_WORD, wakeWord)
        }
        if (AndroidSettings.canDrawOverlays(this)) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "could not start the app from the background: $e")
            }
        }
        val pending = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_WAKE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(NotificationText.wakeTitle(Settings(this).assistantName))
            .setContentText(NotificationText.WAKE_DETAIL)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_WAKE, notification)
    }

    companion object {
        const val ACTION_WAKE = "dev.luna.assistant.WAKE"
        const val EXTRA_WAKE_WORD = "wake_word"
        private const val TAG = "LunaWakeWord"
        private const val LOG_SCORE = 0.2f
        private const val CHANNEL_STATUS = "wake_status"
        private const val CHANNEL_WAKE = "wake_heard"
        private const val NOTIFICATION_STATUS = 3
        private const val NOTIFICATION_WAKE = 4
        private const val START_TIMEOUT_MS = 20_000L
        private const val SHEET_TIMEOUT_MS = 4_000L

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
