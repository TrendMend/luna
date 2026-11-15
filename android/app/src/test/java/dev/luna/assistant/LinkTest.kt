package dev.luna.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LinkTest {
    @Test
    fun parsesSetupLinks() {
        assertEquals(Link("https://luna.example.com", "abc123"), Link.parse("https://luna.example.com/#key=abc123"))
        assertEquals(Link("http://10.0.0.5:8080", null), Link.parse(" http://10.0.0.5:8080/ "))
        assertEquals(Link("https://h", "k"), Link.parse("https://h/?x=1#a=b&key=k"))
        assertNull(Link.parse("javascript:alert(1)"))
        assertNull(Link.parse("not a link"))
        assertNotNull(Link.parse("https://h#key="))
        assertNull(Link.parse("https://h#key=")!!.key)
    }
}
