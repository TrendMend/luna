package dev.luna.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilitiesTest {
    @Test
    fun visionIsReadFromTheAgentAttribute() {
        assertEquals(setOf("camera", "screen"), Capabilities.vision("camera,screen"))
        assertEquals(setOf("screen"), Capabilities.vision(" screen "))
        assertEquals(setOf("camera"), Capabilities.vision("camera,teleport"))
    }

    @Test
    fun noAttributeMeansNoButtons() {
        assertEquals(emptySet<String>(), Capabilities.vision(null))
        assertEquals(emptySet<String>(), Capabilities.vision(""))
        assertEquals(emptySet<String>(), Capabilities.vision("everything"))
    }
}
