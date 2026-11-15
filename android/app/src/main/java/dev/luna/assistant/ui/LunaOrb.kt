package dev.luna.assistant.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import dev.luna.assistant.ui.theme.IndigoSoft
import dev.luna.assistant.ui.theme.MoonGold
import dev.luna.assistant.ui.theme.Violet

/** What Luna is doing, as the orb shows it. */
enum class OrbState { Idle, Connecting, Listening, Thinking, Speaking }

/**
 * Luna's voice as a small moon. Quiet, it is a thin crescent; as she listens and speaks the unlit part fills in and the disc swells
 * with the loudness of the voice ([level], 0 to 1). It breathes slowly all the time so it never looks frozen.
 *
 * [animate] is off in previews and screenshot tests so the picture is the same every run.
 */
@Composable
fun LunaOrb(state: OrbState, level: Float, modifier: Modifier = Modifier, animate: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "orb")
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = EaseInOut), RepeatMode.Reverse),
        label = "breathe",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing)),
        label = "spin",
    )
    val clamped = level.coerceIn(0f, 1f)
    val target = when (state) {
        OrbState.Speaking -> 0.40f + 0.60f * clamped
        OrbState.Listening -> 0.25f + 0.35f * clamped
        OrbState.Thinking -> 0.30f
        OrbState.Connecting -> 0.12f
        OrbState.Idle -> 0.08f
    }
    val energy by animateFloatAsState(target, spring(stiffness = Spring.StiffnessMediumLow), label = "energy")
    val breath = if (animate) breathe else 0.5f
    val angle = if (animate) spin else 20f

    val earthshine = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier.aspectRatio(1f)) {
        val c = center
        val outer = size.minDimension / 2f
        val radius = outer * (0.62f + 0.05f * breath + 0.22f * energy)

        // Glow behind the disc
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Violet.copy(alpha = 0.10f + 0.40f * energy), Color.Transparent),
                center = c,
                radius = outer,
            ),
            radius = outer,
            center = c,
        )

        // The unlit part of the moon, only a hairline
        drawCircle(color = earthshine.copy(alpha = 0.55f), radius = radius, center = c, style = Stroke(width = 1.5.dp.toPx()))

        // The lit part: the disc minus a shadow disc that slides off to the right as the voice gets louder
        val shadowShift = radius * (0.12f + 1.95f * energy)
        val lit = Path.combine(
            PathOperation.Difference,
            Path().apply { addOval(Rect(c, radius)) },
            Path().apply { addOval(Rect(c + Offset(shadowShift, 0f), radius)) },
        )
        clipPath(lit) {
            // A slowly turning blend of indigo, violet and moon-gold (linear, so there is no pinch point at the centre)
            val a = Math.toRadians(angle.toDouble())
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat()) * radius
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(IndigoSoft, Violet, MoonGold),
                    start = c - dir,
                    end = c + dir,
                ),
                radius = radius,
                center = c,
            )
            // A soft light from the upper left, so it reads as a sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.50f), Color.Transparent),
                    center = c + Offset(-radius * 0.32f, -radius * 0.36f),
                    radius = radius * 1.15f,
                ),
                radius = radius,
                center = c,
            )
        }
    }
}
