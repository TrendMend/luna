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
import dev.luna.assistant.screen.ConnectScreen
import dev.luna.assistant.screen.VoiceAssistantRoute
import dev.luna.assistant.screen.VoiceAssistantScreen
import dev.luna.assistant.ui.theme.LiveKitVoiceAssistantExampleTheme
import dev.luna.assistant.viewmodel.VoiceAssistantViewModel
import io.livekit.android.LiveKit
import io.livekit.android.util.LoggingLevel

class MainActivity : ComponentActivity() {

    /** A call from the house (Home Assistant, a timer or a reminder) that has not been answered yet. */
    private var incoming by mutableStateOf<Protocol.Call?>(null)

    // Registered up front: a launcher cannot be registered once the activity has started.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
                    navController.navigate(VoiceAssistantRoute(trigger = "call", say = call.say))
                }
            }

            LiveKitVoiceAssistantExampleTheme(dynamicColor = false) {
                Scaffold { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {

                        // Set up NavHost for the app
                        NavHost(navController, startDestination = ConnectRoute) {
                            composable<ConnectRoute> {
                                ConnectScreen(
                                    navigateToVoiceAssistant = {
                                        runOnUiThread { navController.navigate(VoiceAssistantRoute()) }
                                    },
                                    onCallsToggled = ::setCalls,
                                )
                            }

                            composable<VoiceAssistantRoute> { entry ->
                                val route = entry.toRoute<VoiceAssistantRoute>()
                                val viewModel = viewModel<VoiceAssistantViewModel>()
                                VoiceAssistantScreen(
                                    viewModel = viewModel,
                                    trigger = route.trigger,
                                    say = route.say,
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

    /** A call arrives as a full-screen notification: show over the lock screen and start talking. */
    private fun handle(intent: Intent?) {
        if (intent?.action != ControlService.ACTION_CALL) return
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        incoming = Protocol.Call(Protocol.cleanSay(intent.getStringExtra(ControlService.EXTRA_SAY)))
        intent.action = Intent.ACTION_MAIN        // do not answer again if the screen is rotated
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

    override fun onStart() {
        super.onStart()
        val settings = Settings(this)
        if (settings.listenForCalls && settings.isConfigured) ControlService.start(this)
    }
}
