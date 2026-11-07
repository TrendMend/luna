package dev.luna.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.luna.assistant.ui.LunaOrb
import dev.luna.assistant.ui.OrbState
import dev.luna.assistant.ui.theme.LunaTheme

@Composable
private fun OrbStates(dark: Boolean) = LunaTheme(darkTheme = dark) {
    Column(
        Modifier.background(MaterialTheme.colorScheme.background).fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            Triple("Idle", OrbState.Idle, 0f), Triple("Connecting", OrbState.Connecting, 0f),
            Triple("Listening", OrbState.Listening, 0.4f), Triple("Thinking", OrbState.Thinking, 0f),
            Triple("Speaking, quiet", OrbState.Speaking, 0.1f), Triple("Speaking, loud", OrbState.Speaking, 1f),
        ).chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { (label, state, level) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LunaOrb(state, level, Modifier.size(112.dp), animate = false)
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
        }
    }
}

@PreviewTest
@Preview(widthDp = 380, heightDp = 330)
@Composable
fun OrbStatesLight() = OrbStates(dark = false)

@PreviewTest
@Preview(widthDp = 380, heightDp = 330)
@Composable
fun OrbStatesDark() = OrbStates(dark = true)
