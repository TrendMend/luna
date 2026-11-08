package dev.luna.assistant.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Luna over another app, after the Gemini overlay: one slim pill at the bottom (Luna, what the status is, mic, end) ringed by a
 * moving blue to violet to pink glow, with the app behind left visible. When there is something to read, the same pill grows
 * upward to hold it.
 */

private val GlowColors = intArrayOf(
    Color(0xFF4285F4).toArgb(), Color(0xFF9B72CB).toArgb(), Color(0xFFD96570).toArgb(), Color(0xFFF0A070).toArgb(),
    Color(0xFFD96570).toArgb(), Color(0xFF9B72CB).toArgb(), Color(0xFF4285F4).toArgb(),
)

/** A bright ring with a soft bloom outside it. The colours turn slowly around the card, and glow harder while someone speaks. */
private fun Modifier.glowRing(angle: Float, strength: Float, corner: Dp): Modifier = drawBehind {
    val cx = size.width / 2
    val cy = size.height / 2
    val shader = android.graphics.SweepGradient(cx, cy, GlowColors, null).apply {
        setLocalMatrix(android.graphics.Matrix().apply { postRotate(angle, cx, cy) })
    }
    val brush = ShaderBrush(shader)
    val r = corner.toPx()
    // widest and faintest first; where they overlap near the edge the glow is strongest
    for (i in 5 downTo 1) {
        drawRoundRect(
            brush = brush, topLeft = Offset.Zero, size = size, cornerRadius = CornerRadius(r),
            style = Stroke(width = (4 + i * 8).dp.toPx()), alpha = (0.10f + 0.08f * strength).coerceAtMost(0.3f),
        )
    }
    drawRoundRect(brush = brush, size = size, cornerRadius = CornerRadius(r), style = Stroke(width = 2.5.dp.toPx()), alpha = 0.8f + 0.2f * strength)
}

@Composable
fun OverlayContent(
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
    /** False hides the end button: the popup then closes by tapping outside it. */
    showEnd: Boolean = true,
) {
    val empty = feed.isEmpty()
    val spin = rememberInfiniteTransition(label = "glow").animateFloat(
        0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "angle",
    )
    val angle = if (animateOrb) spin.value else 25f
    val strength by animateFloatAsState(level.coerceIn(0f, 1f), tween(200), label = "strength")
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    val corner = if (empty) 36.dp else 30.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 14.dp) // room for the glow to bloom into
            .heightIn(max = maxHeight)
            .glowRing(angle, strength, corner)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.background)
            // taps on the card stay on the card (anywhere else closes Luna)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // (the size animation lives inside the card: animateContentSize clips, and the glow has to bloom outside)
        Column(Modifier.animateContentSize()) {
        if (!empty) Feed(feed, Modifier.weight(1f, fill = false).fillMaxWidth().padding(top = 8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            LunaOrb(orbState, level, Modifier.size(34.dp), animate = animateOrb)
            PillField(status, onSend, Modifier.weight(1f))
            if (cameraAvailable) {
                IconButton(onClick = onCamera) {
                    Icon(
                        if (cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff, "Camera",
                        tint = if (cameraOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (screenShareAvailable) {
                IconButton(onClick = onScreenShare) {
                    Icon(
                        Icons.Outlined.PresentToAll, "Share screen",
                        tint = if (screenShareOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = onMic,
                colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(if (micOn) Icons.Default.Mic else Icons.Default.MicOff, if (micOn) "Mute" else "Unmute")
            }
            if (showEnd) IconButton(
                onClick = onEnd,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.padding(start = 6.dp),
            ) {
                Icon(Icons.Default.CallEnd, "End")
            }
        }
        }
    }
}

/** The text box of the pill: no box of its own, and the status ("Listening") is its hint. */
@Composable
private fun PillField(hint: String, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
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
        placeholder = { Text(hint, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = {
            if (text.isNotBlank()) {
                IconButton(onClick = ::send) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message") }
            }
        },
        modifier = modifier,
    )
}
