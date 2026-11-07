package dev.luna.assistant

/** What the connected agent says it can do, from the attributes it publishes (docs/protocol.md). */
object Capabilities {
    const val ATTR_VISION = "luna.vision"

    /** The agent's `luna.vision` attribute: "camera", "screen", or "camera,screen". Anything else, or nothing, means neither. */
    fun vision(attribute: String?): Set<String> =
        attribute.orEmpty().split(',').map { it.trim() }.filter { it == "camera" || it == "screen" }.toSet()
}
