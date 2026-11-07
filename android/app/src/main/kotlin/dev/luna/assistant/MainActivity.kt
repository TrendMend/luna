package dev.luna.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: LunaViewModel by viewModels()
    private var pendingCall: String? = null
    private var hasPendingCall = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPending() else vm.report("Luna needs the microphone to hear you.")
    }

    // Registered up front: a launcher cannot be registered once the activity has started.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7EC8E3), background = Color(0xFF12263A), surface = Color(0xFF12263A))) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LunaScreen(vm, onTalk = { requestAndStart(null) }, onCallsToggled = ::setCalls)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** A call from the house arrives as a full-screen notification: show over the lock screen and start talking. */
    private fun handle(intent: Intent?) {
        if (intent?.action != ControlService.ACTION_CALL) return
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        pendingCall = intent.getStringExtra(ControlService.EXTRA_SAY)?.ifBlank { null }
        hasPendingCall = true
        intent.action = Intent.ACTION_MAIN        // do not start again if the screen is rotated
        requestAndStart(pendingCall, fromCall = true)
    }

    private fun requestAndStart(say: String?, fromCall: Boolean = false) {
        if (fromCall) {
            pendingCall = say
            hasPendingCall = true
        } else {
            hasPendingCall = false
            pendingCall = null
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startPending()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startPending() {
        if (hasPendingCall) vm.start(trigger = "call", say = pendingCall) else vm.start()
        hasPendingCall = false
        pendingCall = null
    }

    private fun setCalls(on: Boolean) {
        vm.settings.listenForCalls = on
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ControlService.start(this)
        } else {
            ControlService.stop(this)
        }
    }

    override fun onStart() {
        super.onStart()
        if (vm.settings.listenForCalls && vm.settings.configured) ControlService.start(this)
    }
}

@Composable
private fun LunaScreen(vm: LunaViewModel, onTalk: () -> Unit, onCallsToggled: (Boolean) -> Unit) {
    val active by vm.active.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val widgets by vm.widgets.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Luna", fontSize = 30.sp, color = MaterialTheme.colorScheme.onBackground)
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
        }

        // Widgets the agent has put on the screen (A2UI surfaces).
        widgets?.let { w ->
            val surfaces by w.processor.activeSurfaces.collectAsStateWithLifecycle()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                surfaces.forEach { surface -> A2uiSurface(surfaceModel = surface, modifier = Modifier.fillMaxWidth()) }
            }
        }

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Button(
                onClick = { if (active) vm.stop() else onTalk() },
                modifier = Modifier.size(220.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Color(0xFFFFB4A2) else MaterialTheme.colorScheme.primary,
                    contentColor = Color(0xFF12263A),
                ),
            ) { Text(if (active) "Stop" else "Talk to Luna", fontSize = 24.sp) }
        }

        Text(status, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, minLines = 1)
        LazyColumn(Modifier.fillMaxWidth().height(160.dp)) {
            items(transcript) { line ->
                Text("${line.who}: ${line.text}", color = if (line.who == "You") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    if (showSettings) SettingsDialog(vm, onClose = { showSettings = false }, onCallsToggled = onCallsToggled)
}

@Composable
private fun SettingsDialog(vm: LunaViewModel, onClose: () -> Unit, onCallsToggled: (Boolean) -> Unit) {
    val s = vm.settings
    var server by remember { mutableStateOf(s.server) }
    var key by remember { mutableStateOf(s.key) }
    var calls by remember { mutableStateOf(s.listenForCalls) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste the setup link from `luna-web add-device`, or fill both fields.")
                OutlinedTextField(
                    value = server,
                    onValueChange = { v ->
                        val link = Protocol.parseLink(v)
                        if (link != null && v.contains('#')) {
                            server = link.server
                            link.key?.let { key = it }
                        } else {
                            server = v
                        }
                    },
                    label = { Text("Server address") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, label = { Text("Access key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ring for calls and reminders", modifier = Modifier.weight(1f))
                    Switch(checked = calls, onCheckedChange = { calls = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                s.server = server
                s.key = key
                if (calls != s.listenForCalls || calls) onCallsToggled(calls)
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
