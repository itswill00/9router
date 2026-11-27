package com.ninerouter.android.core

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleLogTest {
    @Before
    fun setUp() {
        ConsoleLog.clear()
    }

    @After
    fun tearDown() {
        ConsoleLog.clear()
    }

    @Test
    fun `keeps the newest two hundred lines`() {
        repeat(205) { index -> ConsoleLog.info("[TEST] line $index") }

        val lines = ConsoleLog.snapshot()
        assertEquals(ConsoleLog.MAX_LINES, lines.size)
        assertTrue(lines.first().message.endsWith("line 5"))
        assertTrue(lines.last().message.endsWith("line 204"))
    }

    @Test
    fun `renders the level tag and clock`() {
        ConsoleLog.error("[GATEWAY] failed")

        val line = ConsoleLog.snapshot().single()
        assertEquals(ConsoleLog.Level.ERROR, line.level)
        assertTrue(line.text.contains("[ERROR]"))
        assertTrue(line.text.contains("[GATEWAY] failed"))
        assertTrue(line.clock.matches(Regex("""\d{2}:\d{2}:\d{2}""")))
    }

    @Test
    fun `clear removes buffered lines`() {
        ConsoleLog.warn("one")
        ConsoleLog.clear()
        assertTrue(ConsoleLog.snapshot().isEmpty())
    }
}
