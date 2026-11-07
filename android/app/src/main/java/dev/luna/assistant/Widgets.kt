package dev.luna.assistant

import android.content.Context
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.model.processor.A2uiMessageProcessor
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The A2UI basic catalog drawn with Material 3. Video and audio players are left out on purpose (the worker never sends them). */
fun lunaCatalog(context: Context): A2uiCatalog = materialA2uiBasicCatalogV1(
    image = MaterialA2uiBasicCatalogV1Defaults.image { url, description, scale, modifier, onError ->
        AsyncImage(
            model = url,
            contentDescription = description,
            contentScale = scale,
            modifier = modifier,
            onError = { state -> onError(state.result.throwable) },
        )
    },
    video = MaterialA2uiBasicCatalogV1Defaults.video { _, _, _ -> },
    audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer { _, _, _, _ -> },
    functions = A2uiBasicCatalogV1.Functions(context.applicationContext),   // openUrl -> ACTION_VIEW
)

/**
 * One conversation's widgets: an A2UI processor fed from the `luna.ui` stream. Taps and errors come out of
 * [outbound] already shaped as the JSON the worker expects on `luna.ui.action`. Create one per conversation and [close] it.
 */
class Widgets(context: Context, scope: CoroutineScope, private val outbound: (String) -> Unit) {
    val processor: A2uiMessageProcessor = A2uiMessageProcessor(catalogs = listOf(lunaCatalog(context)))
    private val parser = A2uiMessageParser()
    private val jobs: List<Job> = listOf(
        scope.launch { processor.collectMessages() },
        scope.launch {
            processor.outboundEvents.collect { event ->
                when (event) {
                    is A2uiClientEventMessage -> outbound(
                        Protocol.actionJson(event.type, event.surfaceId, event.componentId, event.timestamp, event.context),
                    )
                    is A2uiClientErrorMessage -> outbound(Protocol.errorJson(event.code, event.surfaceId, event.message))
                    else -> Unit
                }
            }
        },
    )

    /** Text from the `luna.ui` stream: one A2UI message per line. A bad line is reported back and the others still apply. */
    fun receive(text: String) {
        for (line in Protocol.splitMessages(text)) processor.processInput(parser, line)
    }

    fun close() = jobs.forEach { it.cancel() }
}
