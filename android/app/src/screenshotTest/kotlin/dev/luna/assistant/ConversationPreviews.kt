package dev.luna.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import dev.luna.assistant.ui.ConversationContent
import dev.luna.assistant.ui.FeedItem
import dev.luna.assistant.ui.OrbState
import dev.luna.assistant.ui.theme.LunaTheme

private val sample = listOf(
    FeedItem.You("1", "Is it going to rain today?"),
    FeedItem.Luna("2", "There's a good chance of showers this afternoon, around 60 percent. Highs of 14 degrees. You might want a coat if you go out."),
    FeedItem.You("3", "Turn on the bedroom fan please"),
    FeedItem.Widget("w1") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Turn the bedroom fan on?", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {}) { Text("Yes, turn it on") }
                OutlinedButton(onClick = {}) { Text("No") }
            }
        }
    },
    FeedItem.Luna("4", "Shall I turn it on? Tap yes or just say so."),
)

@Composable
private fun Screen(dark: Boolean, feed: List<FeedItem>, state: OrbState, level: Float, status: String, extras: Boolean = false) =
    LunaTheme(darkTheme = dark) {
        ConversationContent(
            orbState = state, level = level, status = status, feed = feed,
            micOn = true, onMic = {}, onSend = {}, onEnd = {},
            cameraAvailable = extras, screenShareAvailable = extras,
            animateOrb = false,
        )
    }

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun ConversationNewLight() = Screen(false, emptyList(), OrbState.Listening, 0.3f, "Listening")

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun ConversationSpeakingLight() = Screen(false, sample, OrbState.Speaking, 0.5f, "Luna is speaking")

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun ConversationSpeakingDark() = Screen(true, sample, OrbState.Speaking, 0.5f, "Luna is speaking", extras = true)


/** The press-and-hold overlay: some other app (a stand-in here) dimmed, with Luna's sheet over it. */
@Composable
private fun Overlay(dark: Boolean, feed: List<FeedItem>, state: OrbState, status: String) = LunaTheme(darkTheme = dark) {
    Box(Modifier.fillMaxSize().background(Color(0xFFF1EEE6))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Messages", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF222222))
            repeat(7) { Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)) }
        }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.28f))))
        ConversationContent(
            modifier = Modifier.align(Alignment.BottomCenter), compact = true,
            orbState = state, level = 0.4f, status = status, feed = feed,
            micOn = true, onMic = {}, onSend = {}, onEnd = {}, animateOrb = false,
        )
    }
}

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun OverlayNewLight() = Overlay(false, emptyList(), OrbState.Listening, "Listening")

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun OverlayConversationLight() = Overlay(false, sample, OrbState.Speaking, "Luna is speaking")

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun OverlayConversationDark() = Overlay(true, sample, OrbState.Speaking, "Luna is speaking")
