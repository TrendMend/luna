package dev.luna.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.luna.assistant.Widgets

/** The widgets the agent has put on the screen, as a card over the call. Shows nothing while there are none. */
@Composable
fun WidgetsOverlay(widgets: Widgets, modifier: Modifier = Modifier) {
    val surfaces by widgets.processor.activeSurfaces.collectAsState()
    if (surfaces.isEmpty()) return
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    Surface(
        modifier = modifier.padding(16.dp).fillMaxWidth().heightIn(max = maxHeight),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            surfaces.forEach { surface -> A2uiSurface(surfaceModel = surface, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
