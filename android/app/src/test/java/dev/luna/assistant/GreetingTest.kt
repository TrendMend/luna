package dev.luna.assistant

import dev.luna.assistant.ui.Greeting
import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test
    fun followsTheTimeOfDay() {
        assertEquals("Hello", Greeting.forHour(3))
        assertEquals("Good morning", Greeting.forHour(5))
        assertEquals("Good morning", Greeting.forHour(11))
        assertEquals("Good afternoon", Greeting.forHour(12))
        assertEquals("Good afternoon", Greeting.forHour(16))
        assertEquals("Good evening", Greeting.forHour(17))
        assertEquals("Good evening", Greeting.forHour(23))
    }

    @Test
    fun usesTheNameWhenThereIsOne() {
        assertEquals("Good morning, Rulon", Greeting.forHour(9, "Rulon"))
        assertEquals("Good morning", Greeting.forHour(9, null))
        assertEquals("Good morning", Greeting.forHour(9, "  "))
    }
}
