package dev.luna.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTest {
    private fun t(key: String, time: Long) = Timed(key, time, key)

    @Test
    fun aWidgetSitsWhereItAppearedInTheConversation() {
        val messages = listOf(t("m1", 10), t("m2", 30), t("m3", 50))
        val widgets = listOf(t("w1", 40))
        assertEquals(listOf("m1", "m2", "w1", "m3"), Timeline.merge(messages, widgets).map { it.key })
    }

    @Test
    fun aTieKeepsTheMessageBeforeTheWidget() {
        assertEquals(listOf("m1", "w1"), Timeline.merge(listOf(t("m1", 20)), listOf(t("w1", 20))).map { it.key })
    }

    @Test
    fun emptyAndSingleGroupsWork() {
        assertEquals(emptyList<String>(), Timeline.merge<String>(emptyList(), emptyList()).map { it.key })
        assertEquals(listOf("w1"), Timeline.merge(emptyList(), listOf(t("w1", 1))).map { it.key })
    }
}
