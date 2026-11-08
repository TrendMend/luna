package dev.luna.assistant.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.PresentToAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One row of the conversation. */
sealed interface FeedItem {
    val key: String

    /** Something the person said or typed. */
    data class You(override val key: String, val text: String) : FeedItem

    /** Something Luna said. */
    data class Luna(override val key: String, val text: String) : FeedItem

    /** A widget Luna put in the conversation (A2UI), drawn by [content]. */
    data class Widget(override val key: String, val content: @Composable () -> Unit) : FeedItem
}

/**
 * The conversation: Luna's orb and what she is doing, the feed of messages and widgets, and the controls. It takes plain data, so
 * the same screen is drawn by the app and by the preview images ([dev.luna.assistant] screenshot tests).
 */
@Composable
fun ConversationContent(
    orbState: OrbState,
    level: Float,
    status: String,
    feed: List<FeedItem>,
    micOn: Boolean,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    cameraAvailable: Boolean = false,
    cameraOn: Boolean = false,
    onCamera: () -> Unit = {},
    screenShareAvailable: Boolean = false,
    screenShareOn: Boolean = false,
    onScreenShare: () -> Unit = {},
    animateOrb: Boolean = true,
    /** The overlay shown over another app (press and hold): a bottom sheet instead of a whole screen. */
    compact: Boolean = false,
    /** In the compact popup: whether it has an end button (without one, tapping outside closes it). */
    showEnd: Boolean = true,
) {
    if (compact) {
        OverlayContent(
            orbState, level, status, feed, micOn, onMic, onSend, onEnd, modifier,
            cameraAvailable, cameraOn, onCamera, screenShareAvailable, screenShareOn, onScreenShare, animateOrb, showEnd,
        )
        return
    }
    val empty = feed.isEmpty()
    Column(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding(),
    ) {
        // Luna: big while the conversation is new, smaller once there is something to read
        val orbSize by animateDpAsState(if (empty) 240.dp else 128.dp, spring(), label = "orbSize")
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (empty) Modifier.weight(1f) else Modifier.padding(top = 16.dp, bottom = 8.dp)),
        ) {
            LunaOrb(orbState, level, Modifier.size(orbSize), animate = animateOrb)
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (!empty) Feed(feed, Modifier.weight(1f).fillMaxWidth())

        Controls(
            onSend, micOn, onMic, onEnd, cameraAvailable, cameraOn, onCamera, screenShareAvailable, screenShareOn, onScreenShare,
        )
    }
}

@Composable
private fun Controls(
    onSend: (String) -> Unit,
    micOn: Boolean,
    onMic: () -> Unit,
    onEnd: () -> Unit,
    cameraAvailable: Boolean,
    cameraOn: Boolean,
    onCamera: () -> Unit,
    screenShareAvailable: Boolean,
    screenShareOn: Boolean,
    onScreenShare: () -> Unit,
) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MessageField(onSend)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    icon = if (micOn) Icons.Default.Mic else Icons.Default.MicOff,
                    label = if (micOn) "Mute" else "Unmute",
                    onClick = onMic,
                    modifier = Modifier.weight(1f),
                    selected = !micOn,
                )
                if (cameraAvailable) {
                    ActionButton(
                        icon = if (cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        label = "Camera",
                        onClick = onCamera,
                        modifier = Modifier.weight(1f),
                        selected = cameraOn,
                    )
                }
                if (screenShareAvailable) {
                    ActionButton(
                        icon = Icons.Outlined.PresentToAll,
                        label = if (screenShareOn) "Sharing" else "Share",
                        onClick = onScreenShare,
                        modifier = Modifier.weight(1f),
                        selected = screenShareOn,
                    )
                }
                ActionButton(
                    icon = Icons.Default.CallEnd,
                    label = "End",
                    onClick = onEnd,
                    modifier = Modifier.weight(1f),
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError,
                )
            }
        }
}

@Composable
internal fun Feed(feed: List<FeedItem>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(feed.size) { if (feed.isNotEmpty()) state.animateScrollToItem(0) }
    LazyColumn(
        state = state,
        reverseLayout = true,
        modifier = modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // Older messages fade out under the orb instead of being cut off
                drawRect(
                    brush = Brush.verticalGradient(0f to Color.Transparent, 0.10f to Color.Black, 1f to Color.Black),
                    blendMode = BlendMode.DstIn,
                )
            },
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(items = feed.asReversed(), key = { it.key }) { item ->
            when (item) {
                is FeedItem.You -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 28.dp, bottomEnd = 8.dp),
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                    }
                }

                is FeedItem.Luna -> Text(
                    item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth().padding(end = 24.dp),
                )

                is FeedItem.Widget -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.padding(20.dp)) { item.content() }
                }
            }
        }
    }
}

@Composable
private fun MessageField(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        if (text.isNotBlank()) {
            onSend(text.trim())
            text = ""
        }
    }
    TextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Type a message", style = MaterialTheme.typography.bodyLarge) },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = {
            IconButton(onClick = ::send, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A big, labelled control: the icon over a plain word, at least 72dp tall. */
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    container: Color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.defaultMinSize(minHeight = 72.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}
