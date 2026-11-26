package com.ninerouter.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComboRotationTest {
    @Test
    fun roundRobinRotatesAfterEachRequest() {
        ComboRotation.reset()
        val models = listOf("a", "b", "c")
        assertEquals(models, ComboRotation.order(models, "combo:test", 1))
        assertEquals(listOf("b", "c", "a"), ComboRotation.order(models, "combo:test", 1))
        assertEquals(listOf("c", "a", "b"), ComboRotation.order(models, "combo:test", 1))
        assertEquals(models, ComboRotation.order(models, "combo:test", 1))
    }

    @Test
    fun stickyLimitKeepsTheSameStartForConsecutiveRequests() {
        ComboRotation.reset()
        val models = listOf("a", "b", "c")
        assertEquals(models, ComboRotation.order(models, "combo:sticky", 2))
        assertEquals(models, ComboRotation.order(models, "combo:sticky", 2))
        assertEquals(listOf("b", "c", "a"), ComboRotation.order(models, "combo:sticky", 2))
    }

    @Test
    fun singleModelAndEmptyListsAreUnchanged() {
        ComboRotation.reset()
        assertEquals(listOf("only"), ComboRotation.order(listOf("only"), "combo:one", 1))
        assertEquals(emptyList<String>(), ComboRotation.order(emptyList(), "combo:none", 1))
    }

    @Test
    fun smartSessionCanBeClearedPerCombo() {
        SmartSessions.clear("combo:a")
        SmartSessions.clear("combo:b")
        SmartSessions.put("combo:a", "session-1", "model-a")
        SmartSessions.put("combo:b", "session-1", "model-b")
        assertEquals("model-a", SmartSessions.get("combo:a", "session-1"))
        SmartSessions.clear("combo:a")
        assertNull(SmartSessions.get("combo:a", "session-1"))
        assertEquals("model-b", SmartSessions.get("combo:b", "session-1"))
        SmartSessions.clear("combo:b")
    }
}
