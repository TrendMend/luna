package dev.luna.assistant

import androidx.compose.foundation.layout.Arrangement
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
