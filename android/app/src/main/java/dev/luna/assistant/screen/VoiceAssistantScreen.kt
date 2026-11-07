package dev.luna.assistant.screen

import android.app.Activity
import android.content.Context.MEDIA_PROJECTION_SERVICE
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import dev.luna.assistant.Capabilities
import dev.luna.assistant.Protocol
import dev.luna.assistant.Status
import dev.luna.assistant.Timed
import dev.luna.assistant.Timeline
import dev.luna.assistant.rememberCanEnableCamera
import dev.luna.assistant.rememberCanEnableMic
import dev.luna.assistant.requirePermissions
import dev.luna.assistant.ui.ConversationContent
import dev.luna.assistant.ui.FeedItem
import dev.luna.assistant.viewmodel.VoiceAssistantViewModel
import io.livekit.android.annotations.Beta
import io.livekit.android.compose.local.SessionScope
import io.livekit.android.compose.local.requireRoom
import io.livekit.android.compose.state.AgentState
import io.livekit.android.compose.state.SessionOptions
import io.livekit.android.compose.state.rememberAgent
import io.livekit.android.compose.state.rememberLocalMedia
import io.livekit.android.compose.state.rememberSession
import io.livekit.android.compose.state.rememberSessionMessages
import io.livekit.android.compose.types.ReceivedMessage
import io.livekit.android.compose.types.TrackReference
import io.livekit.android.compose.ui.audio.AudioVisualizer
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import io.livekit.android.token.TokenRequestOptions
import io.livekit.android.util.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class VoiceAssistantRoute(
    /** How the conversation started: null for the button, "call" when the house rang the phone. */
    val trigger: String? = null,
    /** For a call: the words Luna opens with. */
    val say: String? = null,
)

@Composable
fun VoiceAssistantScreen(
    viewModel: VoiceAssistantViewModel,
    trigger: String?,
    say: String?,
    onEndCall: () -> Unit,
) {
    VoiceAssistant(viewModel = viewModel, trigger = trigger, say = say, modifier = Modifier, onEndCall = onEndCall)
}

@OptIn(Beta::class, ExperimentalPermissionsApi::class)
@Composable
fun VoiceAssistant(
    viewModel: VoiceAssistantViewModel,
    trigger: String?,
    say: String?,
    modifier: Modifier = Modifier,
    onEndCall: () -> Unit
) {
    var requestedAudio by remember { mutableStateOf(true) } // Turn on audio by default.
    var requestedVideo by remember { mutableStateOf(false) }

    requirePermissions(requestedAudio, requestedVideo)

    val canEnableMic by rememberCanEnableMic()
    val canEnableVideo by rememberCanEnableCamera()

    val session = rememberSession(
        tokenSource = viewModel.tokenSource,
        options = SessionOptions(
            room = viewModel.room,
            // Tells the agent this device can draw widgets, and (for a call) how this conversation started and what to say first.
            tokenRequestOptions = TokenRequestOptions(participantAttributes = Protocol.conversationAttributes(trigger, say)),
        )
    )

    val context = LocalContext.current

    SessionScope(session = session) { session ->

        // Start the session when we have at least microphone permissions.
        // Permission removals kill the app, so this is a one-way transition.
        LaunchedEffect(canEnableMic) {
            if (!canEnableMic) {
                return@LaunchedEffect
            }

            val result = session.start()

            // Handle if the session fails to connect.
            if (result.isFailure) {
                Toast.makeText(context, "Couldn't connect to Luna.", Toast.LENGTH_SHORT).show()
                onEndCall()
            }
        }

        // The agent hung up (it closes the room) or the connection ended: leave instead of waiting for an agent.
        LaunchedEffect(session) {
            session.waitUntilConnected()
            session.waitUntilDisconnected()
            onEndCall()
        }

        // End the session when leaving the screen.
        DisposableEffect(Unit) {
            onDispose {
                session.end()
            }
        }

        val room = requireRoom()

        // LocalMedia provides state information about the user's local devices
        val localMedia = rememberLocalMedia()
        val isMicEnabled by localMedia::isMicrophoneEnabled
        val isCameraEnabled by localMedia::isCameraEnabled
        val isScreenShareEnabled by localMedia::isScreenShareEnabled

        LaunchedEffect(canEnableMic, requestedAudio) {
            session.waitUntilConnected()
            localMedia.setMicrophoneEnabled(canEnableMic && requestedAudio)
        }

        LaunchedEffect(canEnableVideo, requestedVideo) {
            session.waitUntilConnected()
            localMedia.setCameraEnabled(canEnableVideo && requestedVideo)
        }

        // SessionMessages handles all transcriptions and chat messages
        val sessionMessages = rememberSessionMessages()

        // Agent provides state information about the agent participant.
        val agent = rememberAgent()

        // The agent says what it can be shown (luna.vision); the camera and screen-share buttons follow that.
        val agentParticipant = agent.agentParticipant
        val agentAttributes by remember(agentParticipant) {
            agentParticipant?.let { it::attributes.flow } ?: MutableStateFlow(emptyMap<String, String>())
        }.collectAsState()
        val vision = Capabilities.vision(agentAttributes[Capabilities.ATTR_VISION])

        val coroutineScope = rememberCoroutineScope { Dispatchers.IO }
        val screenSharePermissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val data = result.data
                if (result.resultCode != Activity.RESULT_OK || data == null) {
                    return@rememberLauncherForActivityResult
                }
                coroutineScope.launch {
                    // Agents only support one video stream at a time.
                    requestedVideo = false
                    localMedia.setScreenShareEnabled(true, ScreenCaptureParams(data))
                }
            }

        val state = agent.agentState
        val level = rememberVoiceLevel(agent.audioTrack, localMedia.microphoneTrack, speaking = state == AgentState.SPEAKING)

        Box(modifier = modifier) {
            ConversationContent(
                orbState = Status.orb(state, isMicEnabled),
                level = level,
                status = Status.text(state, isMicEnabled),
                feed = rememberFeed(sessionMessages.messages, room.localParticipant.identity?.value, viewModel),
                micOn = isMicEnabled,
                onMic = { requestedAudio = !requestedAudio },
                onSend = { text -> coroutineScope.launch { sessionMessages.send(text) } },
                onEnd = onEndCall,
                cameraAvailable = "camera" in vision,
                cameraOn = isCameraEnabled,
                onCamera = {
                    requestedVideo = !requestedVideo
                    if (requestedVideo) {
                        // Agents only support one video stream at a time.
                        coroutineScope.launch { localMedia.setScreenShareEnabled(false) }
                    }
                },
                screenShareAvailable = "screen" in vision,
                screenShareOn = isScreenShareEnabled,
                onScreenShare = {
                    if (!isScreenShareEnabled) {
                        // Screenshare permission needs to be requested each time.
                        val manager = context.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenSharePermissionLauncher.launch(manager.createScreenCaptureIntent())
                    } else {
                        coroutineScope.launch { localMedia.setScreenShareEnabled(false) }
                    }
                },
            )
        }
    }
}

/** How loud it is right now (0 to 1): Luna's voice while she speaks, otherwise the person's microphone. */
@Composable
private fun rememberVoiceLevel(agentTrack: TrackReference?, micTrack: TrackReference?, speaking: Boolean): Float {
    var agentLevel by remember { mutableFloatStateOf(0f) }
    var micLevel by remember { mutableFloatStateOf(0f) }
    AudioVisualizer(audioTrackRef = agentTrack, bandCount = 8) { amplitudes ->
        SideEffect { agentLevel = Status.level(amplitudes) }
    }
    AudioVisualizer(audioTrackRef = micTrack, bandCount = 8) { amplitudes ->
        SideEffect { micLevel = Status.level(amplitudes) }
    }
    return if (speaking) agentLevel else micLevel
}

/** Messages and widgets in one list, in the order they happened. A widget stays until the agent closes it. */
@Composable
private fun rememberFeed(messages: List<ReceivedMessage>, myIdentity: String?, viewModel: VoiceAssistantViewModel): List<FeedItem> {
    val widgets = viewModel.widgets
    val surfaces by widgets.processor.activeSurfaces.collectAsState()
    val firstSeen by widgets.firstSeen.collectAsState()
    val now = System.currentTimeMillis()
    return Timeline.merge(
        messages.map {
            val mine = it.fromParticipant?.identity?.value == myIdentity
            val key = "m:${it.id}"
            Timed<FeedItem>(key, it.timestamp, if (mine) FeedItem.You(key, it.message) else FeedItem.Luna(key, it.message))
        },
        surfaces.map { surface ->
            Timed<FeedItem>(
                "w:${surface.id}", firstSeen[surface.id] ?: now,
                FeedItem.Widget("w:${surface.id}") { A2uiSurface(surfaceModel = surface, modifier = Modifier.fillMaxWidth()) },
            )
        },
    ).map { it.item }
}
