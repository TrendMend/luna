package dev.luna.assistant

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.luna.assistant.screen.ConnectRoute
import dev.luna.assistant.screen.HomeScreen
import dev.luna.assistant.screen.SettingsRoute
import dev.luna.assistant.screen.SettingsScreen
import dev.luna.assistant.screen.VoiceAssistantRoute
import dev.luna.assistant.screen.VoiceAssistantScreen
import dev.luna.assistant.ui.theme.LunaTheme
import dev.luna.assistant.viewmodel.VoiceAssistantViewModel
import dev.luna.assistant.wakeword.WakeWordService
import dev.luna.assistant.wakeword.WakeWords
import io.livekit.android.LiveKit
import io.livekit.android.util.LoggingLevel

class MainActivity : ComponentActivity() {

    /** A conversation to open now: a call from the house (Home Assistant, a timer, a reminder) or the assistant gesture. */
    private var incoming by mutableStateOf<VoiceAssistantRoute?>(null)

    // Registered up front: a launcher cannot be registered once the activity has started.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Wake words need the microphone: asked for when the switch is turned on, and the service starts once it is granted. */
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startWakeService()
        } else {
            Settings(this).listenForWakeWords = false
            settingsRefresh++          // the switch on the Settings screen goes back to off
        }
    }

    // Read again whenever the app comes back to the front: the person may have just changed them in Android's own settings.
    private var overlayAllowed by mutableStateOf(false)
    private var isAssistant by mutableStateOf(false)
    private var settingsRefresh by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveKit.loggingLevel = LoggingLevel.DEBUG
        handle(intent)

        setContent {
            val navController = rememberNavController()

            // A call opens the conversation, unless one is already going (never cut that off).
            LaunchedEffect(incoming) {
                val call = incoming ?: return@LaunchedEffect
                incoming = null
                if (navController.currentDestination?.hasRoute<VoiceAssistantRoute>() != true) {
                    navController.navigate(call)
                }
            }

            LunaTheme() {
                Scaffold { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {

                        // Set up NavHost for the app
                        NavHost(navController, startDestination = ConnectRoute) {
                            composable<ConnectRoute> {
                                HomeScreen(
                                    navigateToVoiceAssistant = {
                                        runOnUiThread { navController.navigate(VoiceAssistantRoute()) }
                                    },
                                    navigateToSettings = { navController.navigate(SettingsRoute) },
                                )
                            }

                            composable<SettingsRoute> {
                                SettingsScreen(
                                    onCallsToggled = ::setCalls,
                                    onBack = { navController.navigateUp() },
                                    onWakeToggled = ::setWakeWords,
                                    onWakeChanged = ::restartWakeService,
                                    overlayAllowed = overlayAllowed,
                                    onAllowOverlay = ::allowOverlay,
                                    isAssistant = isAssistant,
                                    onAssistantNameChanged = ::refreshNotifications,
                                    refresh = settingsRefresh,
                                )
                            }

                            composable<VoiceAssistantRoute> { entry ->
                                val route = entry.toRoute<VoiceAssistantRoute>()
                                val viewModel = viewModel<VoiceAssistantViewModel>()
                                VoiceAssistantScreen(
                                    viewModel = viewModel,
                                    trigger = route.trigger,
                                    say = route.say,
                                    wakeWord = route.wakeWord,
                                    onEndCall = {
                                        runOnUiThread { navController.navigateUp() }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /**
     * A call arrives as a full-screen notification, the assistant gesture (press and hold power or home) arrives through
     * AssistActivity: either way show over the lock screen and start talking.
     */
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ControlService.ACTION_CALL -> {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                incoming = VoiceAssistantRoute(trigger = "call", say = Protocol.cleanSay(intent.getStringExtra(ControlService.EXTRA_SAY)))
            }
            WakeWordService.ACTION_WAKE -> {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                incoming = VoiceAssistantRoute(
                    trigger = "wake_word",
                    wakeWord = WakeWords.cleanId(intent.getStringExtra(WakeWordService.EXTRA_WAKE_WORD)),
                )
            }
            ACTION_ASSIST -> {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                // Before Luna is set up there is nothing to talk to: the Setup screen is what opens.
                if (Settings(this).isConfigured) incoming = VoiceAssistantRoute()
            }
            else -> return
        }
        intent.action = Intent.ACTION_MAIN        // do not start again if the screen is rotated
    }

    private fun setCalls(on: Boolean) {
        Settings(this).listenForCalls = on
        if (!on) {
            ControlService.stop(this)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // From Android 14 an app must be allowed (in Settings) to open over the lock screen; send the person there once.
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")),
            )
        }
        ControlService.start(this)
    }

    private fun setWakeWords(on: Boolean) {
        Settings(this).listenForWakeWords = on
        if (!on) {
            WakeWordService.stop(this)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWakeService()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Only while the app is on screen: from Android 14 a microphone service cannot be started from the background. */
    private fun startWakeService() {
        val settings = Settings(this)
        if (settings.listenForWakeWords && settings.isConfigured) WakeWordService.start(this)
    }

    /** A word was switched on or off, or the sensitivity changed: the service reads those when it starts. */
    private fun restartWakeService() {
        if (!Settings(this).listenForWakeWords) return
        WakeWordService.stop(this)
        startWakeService()
    }

    /**
     * The standing notifications carry the assistant's name, so after a rename each running service is started again: that only posts
     * its notification again (neither service restarts its work when started twice).
     */
    private fun refreshNotifications() {
        val settings = Settings(this)
        if (!settings.isConfigured) return
        if (settings.listenForCalls) ControlService.start(this)
        if (settings.listenForWakeWords && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            WakeWordService.start(this)
        }
    }

    private fun allowOverlay() {
        startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed = AndroidSettings.canDrawOverlays(this)
        isAssistant = LunaInteractionService.isSelected
    }

    companion object {
        /** Sent by AssistActivity when the person invokes the assistant. */
        const val ACTION_ASSIST = "dev.luna.assistant.ASSIST"
    }

    override fun onStart() {
        super.onStart()
        val settings = Settings(this)
        if (settings.listenForCalls && settings.isConfigured) ControlService.start(this)
        if (settings.listenForWakeWords && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWakeService()      // (after a restart of the phone this is what brings it back: open the app once)
        }
    }
}
