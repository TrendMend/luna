package dev.luna.assistant.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.luna.assistant.viewmodel.VoiceAssistantViewModel

/**
 * Luna over whatever the person was doing: the app behind stays clear (only its lower edge darkens a little), tapping it closes Luna, and the conversation
 * is a glowing pill at the bottom that grows upward (see OverlayContent).
 */
@Composable
fun AssistantOverlay(viewModel: VoiceAssistantViewModel, onDismiss: () -> Unit, wakeWord: String? = null) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val dim by animateFloatAsState(if (shown) 0.28f else 0f, tween(300), label = "dim")

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = dim)))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        VoiceAssistant(
            viewModel = viewModel,
            trigger = if (wakeWord != null) "wake_word" else null,
            say = null,
            wakeWord = wakeWord,
            modifier = Modifier.fillMaxSize(),
            overlay = true,
            onEndCall = onDismiss,
        )
    }
}
