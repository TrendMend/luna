package dev.luna.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.a2ui.model.processor.A2uiSurfaceModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.luna.assistant.Timed
import dev.luna.assistant.Timeline
import dev.luna.assistant.Widgets
import io.livekit.android.compose.types.ReceivedMessage
import io.livekit.android.room.Room

/** A row of the conversation: something said or typed, or a widget the agent put there. */
private sealed interface Entry {
    data class Message(val message: ReceivedMessage) : Entry
    data class Widget(val surface: A2uiSurfaceModel) : Entry
}

@Composable
fun ChatLog(room: Room, messages: List<ReceivedMessage>, widgets: Widgets, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        // Messages and widgets in one list, in the order they happened. A widget stays in the conversation until the agent closes it.
        val surfaces by widgets.processor.activeSurfaces.collectAsState()
        val firstSeen by widgets.firstSeen.collectAsState()
        val now = System.currentTimeMillis()
        val entries = Timeline.merge(
            messages.map { Timed<Entry>("m:${it.id}", it.timestamp, Entry.Message(it)) },
            surfaces.map { Timed<Entry>("w:${it.id}", firstSeen[it.id] ?: now, Entry.Widget(it)) },
        )
        val displayTranscriptions = entries.asReversed()
        val lazyListState = rememberLazyListState()

        // Scroll to bottom when new transcriptions come in.
        LaunchedEffect(entries.size) {
            lazyListState.animateScrollToItem(0)
        }
        LazyColumn(
            userScrollEnabled = true,
            state = lazyListState,
            reverseLayout = true,
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    // Fade top
                    val colors = arrayOf(
                        0.0f to Color.Transparent,
                        0.15f to Color.Black,
                        1.0f to Color.Black,
                    )
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = colors
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            items(
                items = displayTranscriptions,
                key = { entry -> entry.key },
            ) { entry ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .animateItem()
                ) {
                    when (val item = entry.item) {
                        is Entry.Widget -> Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            tonalElevation = 3.dp,
                        ) {
                            Box(Modifier.padding(16.dp)) {
                                A2uiSurface(surfaceModel = item.surface, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        is Entry.Message -> {
                            val message = item.message
                            if (message.fromParticipant?.identity == room.localParticipant.identity) {
                                UserMessage(
                                    message = message,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
                            } else {
                                // Agent transcription or chat message
                                Text(
                                    text = message.message,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}